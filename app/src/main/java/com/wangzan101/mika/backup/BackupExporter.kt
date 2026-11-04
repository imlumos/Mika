package com.wangzan101.mika.backup

import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/** 备份读写要用到的 App 环境;单测给一份不碰安卓的。 */
internal interface BackupEnv {
    val filesDir: File
    fun cipher(alias: String): KeyCipher
    fun readPrefs(name: String): Map<String, Any?>
    fun writePrefs(name: String, values: Map<String, Any?>)
}

internal const val BACKUP_FORMAT = 1
internal const val PREFS_PREFIX = "prefs/"

/** 导出一次的结果:[skipped] 是解不开、没放进去的项。 */
data class BackupExportResult(val skipped: List<String>)

internal object BackupExporter {
    /** 各分类现在占多少字节(解密前的大小,够让用户估计);界面在勾选前显示。 */
    fun sizes(env: BackupEnv): Map<BackupGroup, Long> = BackupGroup.entries.associateWith { group ->
        BackupCatalog.items.filter { it.group == group }.sumOf { item ->
            var total = 0L
            walk(File(env.filesDir, item.path)) { _, attrs -> total += attrs.size() }
            total
        }
    }

    fun export(
        env: BackupEnv,
        out: OutputStream,
        password: String,
        groups: Set<BackupGroup>,
        versionCode: Int,
        versionName: String,
        language: String?,
        nowMs: Long,
        iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
    ): BackupExportResult {
        // 说明在文件最前面,要写上没放进去的项,所以先把密钥都解开
        val skipped = mutableListOf<String>()
        val secrets = BackupCatalog.items
            .filter { it.group in groups && it.secret != null && File(env.filesDir, it.path).isFile }
            .mapNotNull { item ->
                val plain = runCatching { plainSecret(env, item, skipped) }.getOrNull()
                if (plain == null) skipped += item.path
                plain?.let { item.path to it }
            }.toMap()
        val prefs = BackupCatalog.prefs.filter { it.group in groups }.map { p ->
            PREFS_PREFIX + p.name + ".json" to exportPrefs(env, p, skipped).toByteArray(Charsets.UTF_8)
        }
        val manifest = BackupManifest(BACKUP_FORMAT, versionCode, versionName, nowMs, groups.map { it.name }, language, skipped)

        BackupWriter(BackupCrypto.encrypting(out, password, iterations)).use { writer ->
            writer.manifest(manifest)
            prefs.forEach { (path, bytes) -> writer.file(path, false, ByteArrayInputStream(bytes)) }
            BackupCatalog.items.filter { it.group in groups }.forEach { item ->
                if (item.secret != null) {
                    secrets[item.path]?.let { writer.file(item.path, false, ByteArrayInputStream(it)) }
                } else {
                    val root = File(env.filesDir, item.path)
                    walk(root) { file, _ ->
                        val path = env.filesDir.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                        Files.newInputStream(file).use { writer.file(path, Files.isExecutable(file) && item.dir, it) }
                    }
                }
            }
            writer.finish()
        }
        return BackupExportResult(skipped)
    }

    private fun plainSecret(env: BackupEnv, item: BackupItem, skipped: MutableList<String>): ByteArray {
        val file = File(env.filesDir, item.path)
        return when (val form = item.secret!!) {
            is SecretForm.WholeFile -> env.cipher(form.alias).decrypt(file.readBytes())
            is SecretForm.JsonValues -> {
                val cipher = env.cipher(form.alias)
                val plain = Json.parseToJsonElement(file.readText()).jsonObject.mapNotNull { (key, value) ->
                    val text = runCatching { String(cipher.decrypt(Base64.getDecoder().decode(value.jsonPrimitive.content)), Charsets.UTF_8) }.getOrNull()
                    if (text == null) skipped += "${item.path}#$key"
                    text?.let { key to JsonPrimitive(it) }
                }.toMap()
                JsonObject(plain).toString().toByteArray(Charsets.UTF_8)
            }
        }
    }

    private fun exportPrefs(env: BackupEnv, prefs: BackupPrefs, skipped: MutableList<String>): String {
        val values = env.readPrefs(prefs.name).filterKeys { it !in prefs.dropped }.mapNotNull { (key, value) ->
            val alias = prefs.encrypted[key] ?: return@mapNotNull key to value
            val plain = runCatching { String(env.cipher(alias).decrypt(Base64.getDecoder().decode(value as String)), Charsets.UTF_8) }.getOrNull()
            if (plain == null) skipped += "${prefs.name}#$key"
            plain?.let { key to it }
        }.toMap()
        return PrefsCodec.encode(values)
    }

    /** 走一遍文件或目录里的普通文件。不跟进符号链接:工作区里的链接可能指向目录外面。 */
    fun walk(root: File, onFile: (Path, BasicFileAttributes) -> Unit) {
        if (!Files.exists(root.toPath())) return
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile) onFile(file, attrs)
                return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
    }
}

/** SharedPreferences 的值和 JSON 互转;带类型,读回来和原来一样。 */
internal object PrefsCodec {
    fun encode(values: Map<String, Any?>): String = buildJsonObject {
        values.forEach { (key, value) ->
            val typed = when (value) {
                is Boolean -> "b" to JsonPrimitive(value)
                is Int -> "i" to JsonPrimitive(value)
                is Long -> "l" to JsonPrimitive(value)
                is Float -> "f" to JsonPrimitive(value)
                is String -> "s" to JsonPrimitive(value)
                is Set<*> -> "S" to JsonArray(value.map { JsonPrimitive(it as String) })
                else -> null
            }
            if (typed != null) put(key, buildJsonObject { put("t", JsonPrimitive(typed.first)); put("v", typed.second) })
        }
    }.toString()

    fun decode(text: String): Map<String, Any?> = Json.parseToJsonElement(text).jsonObject.mapValues { (_, element) ->
        val o = element.jsonObject
        val v = o.getValue("v")
        when (o.getValue("t").jsonPrimitive.content) {
            "b" -> v.jsonPrimitive.boolean
            "i" -> v.jsonPrimitive.int
            "l" -> v.jsonPrimitive.long
            "f" -> v.jsonPrimitive.float
            "s" -> v.jsonPrimitive.content
            "S" -> v.jsonArray.map { it.jsonPrimitive.content }.toSet()
            else -> throw IllegalArgumentException("unknown prefs type")
        }
    }
}
