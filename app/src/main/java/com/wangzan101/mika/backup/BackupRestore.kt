package com.wangzan101.mika.backup

import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindowState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/** 备份是更新的 App 做的,这个版本读不了。 */
class BackupTooNewException : IOException("backup was made by a newer app")

/** 手机空间不够放下要恢复的内容。 */
class BackupNoSpaceException : IOException("not enough free space")

/** 导入后要告诉用户的结果。 */
data class RestoreOutcome(val ok: Boolean, val setLanguage: Boolean = false, val language: String? = null)

/** 导入时改掉的内容:恢复数据不应该等于恢复对手机的控制权。 */
internal object BackupSanitize {
    private val LENIENT = Json { ignoreUnknownKeys = true }
    private val SETTLED = setOf(TriggerWindowState.Delivered, TriggerWindowState.Skipped, TriggerWindowState.Failed)

    /** 定时任务全部暂停;没走完的时段不留,否则启动时的善后会替它们补发。走完的留着,一次性任务不会因此重跑。 */
    fun triggers(text: String): String {
        val snapshot = LENIENT.decodeFromString(TriggerSnapshot.serializer(), text)
        return Json.encodeToString(
            TriggerSnapshot.serializer(),
            TriggerSnapshot(snapshot.triggers.map { it.copy(enabled = false) }, snapshot.windows.filter { it.state in SETTLED }),
        )
    }

    /** 连接电脑、远程网关导入后都先关着:否则一启动就开始监听,或和旧手机抢同一个配对。 */
    fun connection(text: String): String =
        JsonObject(Json.parseToJsonElement(text).jsonObject + mapOf("enabled" to JsonPrimitive(false))).toString()

    /** 经网关解锁手机的许可和配对时的默认一样回到关。 */
    fun relay(text: String): String =
        JsonObject(Json.parseToJsonElement(text).jsonObject + mapOf("enabled" to JsonPrimitive(false), "deviceAccess" to JsonPrimitive(false))).toString()

    const val ASK_MODE = """{"autonomous":false}"""
}

@Serializable
private data class RestorePlan(
    val replace: List<String>,
    val remove: List<String>,
    val prefs: List<String>,
    val setLanguage: Boolean,
    val language: String? = null,
)

/**
 * 导入分两步。[inspect] 和 [stage] 在 App 运行时做,只写 `files/.restore/`,不动现有数据;
 * [applyPending] 在下次进程启动的最早阶段做,这时还没有谁打开过这些文件,不用担心内存里的状态和磁盘对不上。
 * 中途被杀:下次启动接着做。中途出错:放回原来的文件。
 */
internal object BackupRestore {
    private const val DIR = ".restore"
    private const val MIN_FREE_BYTES = 64L * 1024 * 1024
    private const val MAX_SECRET_BYTES = 64 * 1024 * 1024
    private val PLAN_JSON = Json { ignoreUnknownKeys = true }

    /** 只读文件最前面的说明:校验密码和版本,告诉用户里面有什么。 */
    fun inspect(input: InputStream, password: String, versionCode: Int): BackupManifest = input.use {
        BackupReader(BackupCrypto.decrypting(it, password)).use { reader -> checked(reader.manifest(), versionCode) }
    }

    /**
     * 解开备份,把选中的分类放进暂存目录,密钥用这台设备的 Keystore 重新加密。
     * 读完整个文件、确认完整之后才写 READY,之前出任何错现有数据都不受影响。
     */
    fun stage(env: BackupEnv, input: InputStream, password: String, groups: Set<BackupGroup>, versionCode: Int) {
        val root = File(env.filesDir, DIR)
        deleteTree(root)
        val staged = File(root, "staged").apply { mkdirs() }
        try {
            input.use {
                BackupReader(BackupCrypto.decrypting(it, password)).use { reader ->
                    val manifest = checked(reader.manifest(), versionCode)
                    reader.files { path, executable, content ->
                        stageOne(env, staged, path, executable, content, groups)
                    }
                    File(staged, BackupCatalog.APPROVALS).writeText(BackupSanitize.ASK_MODE)
                    writePlan(root, staged, groups, manifest)
                }
            }
        } catch (e: EOFException) {
            deleteTree(root)
            throw BackupDamagedException("backup is cut short", e)
        } catch (e: Exception) {
            deleteTree(root)
            throw e
        }
    }

    private fun checked(manifest: BackupManifest, versionCode: Int): BackupManifest {
        if (manifest.format != BACKUP_FORMAT || manifest.appVersionCode > versionCode) throw BackupTooNewException()
        return manifest
    }

    private fun stageOne(env: BackupEnv, staged: File, path: String, executable: Boolean, content: InputStream, groups: Set<BackupGroup>) {
        if (path.startsWith(PREFS_PREFIX)) {
            val prefs = BackupCatalog.prefs.firstOrNull { PREFS_PREFIX + it.name + ".json" == path }
                ?: throw BackupDamagedException("unexpected entry")
            if (prefs.group !in groups) return
            val values = PrefsCodec.decode(readLimited(content).toString(Charsets.UTF_8)).mapValues { (key, value) ->
                val alias = prefs.encrypted[key] ?: return@mapValues value
                Base64.getEncoder().encodeToString(env.cipher(alias).encrypt((value as String).toByteArray(Charsets.UTF_8)))
            }.filterKeys { it !in prefs.dropped }
            target(staged, path).writeText(PrefsCodec.encode(values))
            return
        }
        val item = BackupCatalog.itemFor(path) ?: throw BackupDamagedException("unexpected entry")
        if (item.group !in groups) return
        val file = target(staged, path)
        val rewritten = when {
            item.secret != null || path == BackupCatalog.TRIGGERS || path == BackupCatalog.MCP ->
                rewrite(env, item, path, readLimited(content))
            else -> null
        }
        if (rewritten != null) {
            file.writeBytes(rewritten)
        } else {
            file.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = content.read(buffer)
                    if (n < 0) break
                    if (file.usableSpace < MIN_FREE_BYTES) throw BackupNoSpaceException()
                    out.write(buffer, 0, n)
                }
            }
            if (executable) file.setExecutable(true, true)
        }
    }

    private fun rewrite(env: BackupEnv, item: BackupItem, path: String, plain: ByteArray): ByteArray {
        val text by lazy { plain.toString(Charsets.UTF_8) }
        val clean = when (path) {
            BackupCatalog.TRIGGERS -> BackupSanitize.triggers(text).toByteArray(Charsets.UTF_8)
            BackupCatalog.MCP -> BackupSanitize.connection(text).toByteArray(Charsets.UTF_8)
            BackupCatalog.RELAY -> BackupSanitize.relay(text).toByteArray(Charsets.UTF_8)
            else -> plain
        }
        return when (val form = item.secret) {
            null -> clean
            is SecretForm.WholeFile -> env.cipher(form.alias).encrypt(clean)
            is SecretForm.JsonValues -> {
                val cipher = env.cipher(form.alias)
                JsonObject(Json.parseToJsonElement(clean.toString(Charsets.UTF_8)).jsonObject.mapValues { (_, value) ->
                    JsonPrimitive(Base64.getEncoder().encodeToString(cipher.encrypt(value.jsonPrimitive.content.toByteArray(Charsets.UTF_8))))
                }).toString().toByteArray(Charsets.UTF_8)
            }
        }
    }

    private fun readLimited(content: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = content.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > MAX_SECRET_BYTES) throw BackupDamagedException("entry is too large")
        }
    }

    private fun target(staged: File, path: String): File = File(staged, path).apply { parentFile!!.mkdirs() }

    private fun writePlan(root: File, staged: File, groups: Set<BackupGroup>, manifest: BackupManifest) {
        val selected = BackupCatalog.items.filter { it.group in groups }.map { it.path } + BackupCatalog.APPROVALS
        val (replace, remove) = selected.partition { File(staged, it).exists() }
        val settings = BackupGroup.Settings in groups
        val plan = RestorePlan(
            replace, remove,
            BackupCatalog.prefs.filter { it.group in groups && File(staged, PREFS_PREFIX + it.name + ".json").isFile }.map { it.name },
            setLanguage = settings, language = if (settings) manifest.language else null,
        )
        File(root, "plan.json").writeText(PLAN_JSON.encodeToString(RestorePlan.serializer(), plan))
        // 最后一步:有了它,下次启动才会替换
        File(root, "READY.tmp").apply { writeText("1") }.renameTo(File(root, "READY"))
    }

    /** 放弃还没应用的导入(用户在确认前退出,或准备阶段之后取消)。 */
    fun discard(env: BackupEnv) = deleteTree(File(env.filesDir, DIR))

    /** 有准备好的导入就换上;返回 null 表示这次启动没有导入。旧文件的删除放到后台线程。 */
    fun applyPending(env: BackupEnv, cleanup: (() -> Unit) -> Unit = { it() }): RestoreOutcome? {
        val root = File(env.filesDir, DIR)
        val ready = File(root, "READY")
        if (!ready.isFile) {
            if (root.exists()) cleanup { deleteTree(root) }
            return null
        }
        val plan = runCatching { PLAN_JSON.decodeFromString(RestorePlan.serializer(), File(root, "plan.json").readText()) }.getOrElse {
            ready.delete()
            cleanup { deleteTree(root) }
            return RestoreOutcome(false)
        }
        val staged = File(root, "staged")
        val old = File(root, "old")
        val oldPrefs = plan.prefs.associateWith(env::readPrefs)
        val ok = try {
            plan.remove.forEach { aside(env, old, it) }
            plan.replace.forEach { path ->
                val incoming = File(staged, path)
                // 暂存的已经不在,说明上次被杀之前换过了
                if (Files.exists(incoming.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    aside(env, old, path)
                    move(incoming, File(env.filesDir, path))
                }
            }
            plan.prefs.forEach { env.writePrefs(it, PrefsCodec.decode(File(staged, PREFS_PREFIX + "$it.json").readText())) }
            true
        } catch (e: Exception) {
            rollback(env, plan, old)
            oldPrefs.forEach { (name, values) -> runCatching { env.writePrefs(name, values) } }
            false
        }
        ready.delete()
        cleanup { deleteTree(root) }
        return if (ok) RestoreOutcome(true, plan.setLanguage, plan.language) else RestoreOutcome(false)
    }

    private fun aside(env: BackupEnv, old: File, path: String) {
        val current = File(env.filesDir, path)
        val saved = File(old, path)
        if (Files.exists(current.toPath(), LinkOption.NOFOLLOW_LINKS) && !saved.exists()) move(current, saved)
    }

    private fun rollback(env: BackupEnv, plan: RestorePlan, old: File) {
        (plan.replace + plan.remove).forEach { path ->
            val current = File(env.filesDir, path)
            val saved = File(old, path)
            runCatching {
                if (saved.exists()) {
                    deleteTree(current)
                    move(saved, current)
                } else if (path in plan.replace) {
                    // 原来没有这一项:只删这次换上去的
                    deleteTree(current)
                }
            }
        }
    }

    private fun move(from: File, to: File) {
        to.parentFile!!.mkdirs()
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    /** 删整棵目录,不跟进符号链接。 */
    fun deleteTree(root: File) {
        if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
