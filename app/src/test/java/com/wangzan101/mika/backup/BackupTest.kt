package com.wangzan101.mika.backup

import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindow
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.KeyGenerator

class BackupTest {
    @get:Rule val temp = TemporaryFolder()

    /** 一台假手机:密钥和 SharedPreferences 都只属于它自己,换一台就解不开。 */
    private inner class Phone(name: String) : BackupEnv {
        override val filesDir: File = temp.newFolder(name)
        val prefs = mutableMapOf<String, Map<String, Any?>>()
        private val ciphers = mutableMapOf<String, KeyCipher>()
        var failWrites = false

        override fun cipher(alias: String): KeyCipher = ciphers.getOrPut(alias) {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            AesGcmKeyCipher { key }
        }
        override fun readPrefs(name: String): Map<String, Any?> = prefs[name].orEmpty()
        override fun writePrefs(name: String, values: Map<String, Any?>) {
            check(!failWrites) { "disk full" }
            prefs[name] = values
        }

        fun write(path: String, text: String) = File(filesDir, path).apply { parentFile!!.mkdirs(); writeText(text) }
        fun read(path: String) = File(filesDir, path).readText()
        fun exists(path: String) = File(filesDir, path).exists()
        fun writeSecret(path: String, alias: String, text: String) =
            File(filesDir, path).apply { parentFile!!.mkdirs(); writeBytes(cipher(alias).encrypt(text.toByteArray())) }
        fun readSecret(path: String, alias: String) = String(cipher(alias).decrypt(File(filesDir, path).readBytes()))
    }

    private val defaults = BackupGroup.entries.filter { it.defaultOn }.toSet()

    private fun backup(from: BackupEnv, groups: Set<BackupGroup> = defaults, password: String = PASSWORD, code: Int = 5): ByteArray {
        val out = ByteArrayOutputStream()
        BackupExporter.export(from, out, password, groups, code, "0.1", "ja", 1_000L, TEST_ITERATIONS)
        return out.toByteArray()
    }

    private fun restore(to: BackupEnv, bytes: ByteArray, groups: Set<BackupGroup> = defaults, password: String = PASSWORD): RestoreOutcome? {
        BackupRestore.stage(to, ByteArrayInputStream(bytes), password, groups, 5)
        return BackupRestore.applyPending(to)
    }

    // ---------- 往返 ----------

    @Test fun roundTripRestoresDataAndReencryptsSecretsForTheNewPhone() {
        val a = Phone("a")
        a.write("conversations/c1.json", """{"id":"c1"}""")
        a.write("skills/personal/x/SKILL.md", "skill")
        a.writeSecret("settings/mcp-server-secrets.bin", "mika-mcp-servers", "server-secret")
        a.writeSecret("memory/memory.bin", "mika-memory", "[memory]")
        a.write(
            "settings/provider-keys.json",
            """{"p1":"${Base64.getEncoder().encodeToString(a.cipher("mika-provider-keys").encrypt("sk-1".toByteArray()))}"}""",
        )
        a.prefs["chat_examples"] = mapOf("examples" to "[]")
        a.prefs["feature_flags"] = mapOf(
            "parallel_max" to 2, "keep_screen_on" to false, "capsule_card_height" to 0.5f, "self_config" to true,
            "speech_system_service" to "com.other",
            "speech_doubao_credentials_encrypted" to Base64.getEncoder().encodeToString(a.cipher("mika-speech-keys").encrypt("doubao".toByteArray())),
        )

        val b = Phone("b")
        b.write("conversations/old.json", "gone after restore")
        val outcome = restore(b, backup(a))

        assertEquals(RestoreOutcome(true, setLanguage = true, language = "ja"), outcome)
        assertEquals("""{"id":"c1"}""", b.read("conversations/c1.json"))
        assertFalse("导入是替换,不是合并", b.exists("conversations/old.json"))
        assertEquals("skill", b.read("skills/personal/x/SKILL.md"))
        assertEquals("server-secret", b.readSecret("settings/mcp-server-secrets.bin", "mika-mcp-servers"))
        assertEquals("[memory]", b.readSecret("memory/memory.bin", "mika-memory"))
        val keys = Json.parseToJsonElement(b.read("settings/provider-keys.json")).toString()
        val stored = Regex(""""p1":"([^"]+)"""").find(keys)!!.groupValues[1]
        assertEquals("sk-1", String(b.cipher("mika-provider-keys").decrypt(Base64.getDecoder().decode(stored))))
        assertEquals("[]", b.prefs.getValue("chat_examples")["examples"])

        val flags = b.prefs.getValue("feature_flags")
        assertEquals(2, flags["parallel_max"])
        assertEquals(false, flags["keep_screen_on"])
        assertEquals(0.5f, flags["capsule_card_height"])
        assertNull("放权的开关不跟着备份走", flags["self_config"])
        assertNull("这台设备的情况不跟着备份走", flags["speech_system_service"])
        val doubao = flags.getValue("speech_doubao_credentials_encrypted") as String
        assertEquals("doubao", String(b.cipher("mika-speech-keys").decrypt(Base64.getDecoder().decode(doubao))))
        assertFalse(File(b.filesDir, ".restore/READY").exists())
    }

    @Test fun fileContentIsEncrypted() {
        val a = Phone("a")
        a.write("conversations/c1.json", "very-secret-conversation".repeat(200))
        val text = String(backup(a), Charsets.ISO_8859_1)
        assertFalse(text.contains("very-secret"))
        assertFalse(text.contains("conversations/c1"))
    }

    @Test fun largeFilesSurviveChunking() {
        val a = Phone("a")
        val big = ByteArray(3 * 1024 * 1024 + 17).also { java.util.Random(1).nextBytes(it) }
        File(a.filesDir, "web").mkdirs()
        File(a.filesDir, "web/icon.bin").writeBytes(big)
        val b = Phone("b")
        restore(b, backup(a))
        assertArrayEquals(big, File(b.filesDir, "web/icon.bin").readBytes())
    }

    // ---------- 分类 ----------

    @Test fun optionalGroupsStayOutUnlessChosen() {
        val a = Phone("a")
        a.write("sandbox/work/c-1/out.txt", "work")
        a.write("runs/r1/a.json", "run")
        a.writeSecret("settings/device-access.bin", "mika-device-access", "1234")

        val plain = Phone("b")
        restore(plain, backup(a))
        assertFalse(plain.exists("sandbox/work/c-1/out.txt"))
        assertFalse(plain.exists("runs/r1/a.json"))
        assertFalse(plain.exists("settings/device-access.bin"))

        val all = Phone("c")
        restore(all, backup(a, BackupGroup.entries.toSet()), BackupGroup.entries.toSet())
        assertEquals("work", all.read("sandbox/work/c-1/out.txt"))
        assertEquals("run", all.read("runs/r1/a.json"))
        assertEquals("1234", all.readSecret("settings/device-access.bin", "mika-device-access"))
    }

    @Test fun groupsLeftOutOfTheImportKeepTheirCurrentData() {
        val a = Phone("a")
        a.write("conversations/c1.json", "from backup")
        a.write("web/browser_favorites.json", "from backup")
        val b = Phone("b")
        b.write("conversations/c1.json", "kept")
        b.write("web/browser_favorites.json", "kept")

        restore(b, backup(a), setOf(BackupGroup.Browser))
        assertEquals("kept", b.read("conversations/c1.json"))
        assertEquals("from backup", b.read("web/browser_favorites.json"))
    }

    // ---------- 导入后的安全重置 ----------

    @Test fun importTurnsOffEverythingThatActsOnThePhone() {
        val a = Phone("a")
        a.write("approvals.json", """{"autonomous":true}""")
        a.write("settings/mcp.json", """{"enabled":true,"port":9000}""")
        a.writeSecret("settings/remote-relay.bin", "mika-remote-relay", """{"enabled":true,"gateway":"https://g","deviceAccess":true,"deviceToken":"t"}""")
        val trigger = TriggerDefinition(id = "t1", threadId = "c1", goal = "g", nextAtMs = 5, enabled = true)
        a.write(
            "triggers-v3.json",
            Json.encodeToString(
                TriggerSnapshot.serializer(),
                TriggerSnapshot(
                    listOf(trigger),
                    listOf(
                        TriggerWindow("t1", 1, 1, state = TriggerWindowState.Delivered),
                        TriggerWindow("t1", 2, 2, state = TriggerWindowState.Claimed),
                    ),
                ),
            ),
        )
        val b = Phone("b")
        b.write("approvals.json", """{"autonomous":true}""")
        restore(b, backup(a))

        assertEquals("""{"autonomous":false}""", b.read("approvals.json"))
        val snapshot = Json.decodeFromString(TriggerSnapshot.serializer(), b.read("triggers-v3.json"))
        assertFalse(snapshot.triggers.single().enabled)
        assertEquals("没走完的时段不留,走完的留着", listOf(TriggerWindowState.Delivered), snapshot.windows.map { it.state })
        assertTrue(b.read("settings/mcp.json").contains(""""enabled":false"""))
        assertTrue("端口保留", b.read("settings/mcp.json").contains(""""port":9000"""))
        val relay = b.readSecret("settings/remote-relay.bin", "mika-remote-relay")
        assertTrue(relay.contains(""""enabled":false"""))
        assertTrue(relay.contains(""""deviceAccess":false"""))
        assertTrue(relay.contains(""""deviceToken":"t""""))
    }

    // ---------- 不该成功的 ----------

    @Test fun wrongPasswordChangesNothing() {
        val a = Phone("a")
        a.write("conversations/c1.json", "x")
        val bytes = backup(a)
        val b = Phone("b")
        b.write("conversations/c1.json", "kept")
        assertThrows(BackupPasswordException::class.java) { BackupRestore.inspect(ByteArrayInputStream(bytes), "not-the-password", 5) }
        assertThrows(BackupPasswordException::class.java) { BackupRestore.stage(b, ByteArrayInputStream(bytes), "not-the-password", defaults, 5) }
        assertNull(BackupRestore.applyPending(b))
        assertEquals("kept", b.read("conversations/c1.json"))
    }

    @Test fun truncatedOrChangedFileIsRefusedAndChangesNothing() {
        val a = Phone("a")
        val big = ByteArray(3 * 1024 * 1024).also { java.util.Random(2).nextBytes(it) }
        File(a.filesDir, "web").mkdirs()
        File(a.filesDir, "web/icon.bin").writeBytes(big)
        val bytes = backup(a)
        val b = Phone("b")
        b.write("web/keep.json", "kept")

        val cut = bytes.copyOf(bytes.size - 100)
        assertThrows(BackupDamagedException::class.java) { BackupRestore.stage(b, ByteArrayInputStream(cut), PASSWORD, defaults, 5) }
        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertThrows(BackupDamagedException::class.java) { BackupRestore.stage(b, ByteArrayInputStream(flipped), PASSWORD, defaults, 5) }

        assertNull(BackupRestore.applyPending(b))
        assertEquals("kept", b.read("web/keep.json"))
    }

    @Test fun backupMissingItsEndMarkerIsRefused() {
        val out = ByteArrayOutputStream()
        BackupWriter(BackupCrypto.encrypting(out, PASSWORD, TEST_ITERATIONS)).use { w ->
            w.manifest(BackupManifest(BACKUP_FORMAT, 1, "0.1", 1, listOf("Browser")))
            // 没写结束标记就关了:像导出到一半失败
        }
        val b = Phone("b")
        assertThrows(BackupDamagedException::class.java) {
            BackupRestore.stage(b, ByteArrayInputStream(out.toByteArray()), PASSWORD, defaults, 5)
        }
        assertNull(BackupRestore.applyPending(b))
    }

    @Test fun backupFromNewerAppIsRefused() {
        val bytes = backup(Phone("a"), code = 6)
        assertThrows(BackupTooNewException::class.java) { BackupRestore.inspect(ByteArrayInputStream(bytes), PASSWORD, 5) }
    }

    @Test fun pathsOutsideTheWhitelistAreRefused() {
        for (bad in listOf("../evil", "web/../../evil", "/etc/passwd", "shared_prefs/x.xml", "web\\x", "approvals.json", "sandbox/rootfs/x", "prefs/other.json")) {
            val out = ByteArrayOutputStream()
            val w = BackupWriter(BackupCrypto.encrypting(out, PASSWORD, TEST_ITERATIONS))
            w.manifest(BackupManifest(BACKUP_FORMAT, 1, "0.1", 1, emptyList()))
            w.file(bad, false, ByteArrayInputStream("x".toByteArray()))
            w.finish()
            val b = Phone("bad${bad.hashCode().toUInt()}")
            assertThrows(bad, BackupDamagedException::class.java) {
                BackupRestore.stage(b, ByteArrayInputStream(out.toByteArray()), PASSWORD, BackupGroup.entries.toSet(), 5)
            }
            assertNull(bad, BackupRestore.applyPending(b))
        }
    }

    @Test fun symlinksAreNotFollowedOnExport() {
        val a = Phone("a")
        val outside = temp.newFile("outside.txt").apply { writeText("private") }
        File(a.filesDir, "web").mkdirs()
        Files.createSymbolicLink(File(a.filesDir, "web/link").toPath(), outside.toPath())
        a.write("web/real.json", "real")
        val b = Phone("b")
        restore(b, backup(a))
        assertEquals("real", b.read("web/real.json"))
        assertFalse(b.exists("web/link"))
    }

    // ---------- 换上时出错、被杀 ----------

    @Test fun failureWhileApplyingPutsTheOldFilesBack() {
        val a = Phone("a")
        a.write("conversations/c1.json", "new")
        a.prefs["chat_examples"] = mapOf("examples" to "[]")
        val bytes = backup(a)
        val b = Phone("b")
        b.write("conversations/c1.json", "old")
        b.write("approvals.json", """{"autonomous":true}""")
        BackupRestore.stage(b, ByteArrayInputStream(bytes), PASSWORD, defaults, 5)
        b.failWrites = true

        assertEquals(RestoreOutcome(false), BackupRestore.applyPending(b))
        assertEquals("old", b.read("conversations/c1.json"))
        assertEquals("""{"autonomous":true}""", b.read("approvals.json"))
        assertFalse(b.exists("web"))
        assertFalse("备份里有、原来没有的也撤掉", b.exists("settings/mcp.json"))
        assertFalse(File(b.filesDir, ".restore").exists())
        assertNull(BackupRestore.applyPending(b))
    }

    @Test fun killedWhileApplyingFinishesOnNextStart() {
        val a = Phone("a")
        a.write("conversations/c1.json", "new")
        a.write("web/browser_favorites.json", "new")
        val bytes = backup(a)
        val b = Phone("b")
        b.write("conversations/c1.json", "old")
        BackupRestore.stage(b, ByteArrayInputStream(bytes), PASSWORD, defaults, 5)
        // 假装上次走到一半:对话已经换上,旧的在 old/ 里,暂存的那份没了
        val root = File(b.filesDir, ".restore")
        File(root, "old").mkdirs()
        Files.move(File(b.filesDir, "conversations").toPath(), File(root, "old/conversations").toPath())
        Files.move(File(root, "staged/conversations").toPath(), File(b.filesDir, "conversations").toPath())

        assertEquals(true, BackupRestore.applyPending(b)?.ok)
        assertEquals("new", b.read("conversations/c1.json"))
        assertEquals("new", b.read("web/browser_favorites.json"))
    }

    @Test fun abandonedStagingIsCleanedUpWithoutTouchingData() {
        val b = Phone("b")
        b.write("conversations/c1.json", "kept")
        File(b.filesDir, ".restore/staged/conversations").mkdirs()
        assertNull(BackupRestore.applyPending(b))
        assertFalse(File(b.filesDir, ".restore").exists())
        assertEquals("kept", b.read("conversations/c1.json"))
    }

    // ---------- 其他 ----------

    @Test fun undecryptableSecretIsSkippedAndReported() {
        val a = Phone("a")
        File(a.filesDir, "settings").mkdirs()
        File(a.filesDir, "settings/mcp-token.bin").writeBytes(ByteArray(40))
        a.write("conversations/c1.json", "x")
        val out = ByteArrayOutputStream()
        val result = BackupExporter.export(a, out, PASSWORD, defaults, 5, "0.1", null, 1L, TEST_ITERATIONS)
        assertEquals(listOf("settings/mcp-token.bin"), result.skipped)
        val manifest = BackupRestore.inspect(ByteArrayInputStream(out.toByteArray()), PASSWORD, 5)
        assertEquals(listOf("settings/mcp-token.bin"), manifest.skipped)
        assertEquals(defaults.map { it.name }.toSet(), manifest.groups.toSet())
        assertEquals("x", Phone("b").let { restore(it, out.toByteArray()); it.read("conversations/c1.json") })
    }

    @Test fun differentBackupsOfTheSameDataDiffer() {
        val a = Phone("a")
        assertNotEquals(backup(a).toList(), backup(a).toList())
    }

    @Test fun prefsCodecKeepsTypes() {
        val values = mapOf("b" to true, "i" to 3, "l" to (1L shl 40), "f" to 0.25f, "s" to "x", "set" to setOf("a", "b"))
        assertEquals(values, PrefsCodec.decode(PrefsCodec.encode(values)))
    }

    /**
     * 新增的存储要明确是否备份:在白名单或排除名单里都找不到时这里失败。
     * 只认源码里写成字面量的名字,够覆盖现有写法。
     */
    @Test fun everyStoreIsClassified() {
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/java/com/wangzan101/mika") }
            .plus(generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "src/main/java/com/wangzan101/mika") })
            .first { it.isDirectory }
        val texts = source.walkTopDown().filter { it.extension == "kt" }.associateWith { it.readText() }
        val prefsNames = texts.values.flatMap { text ->
            Regex("""PREFS\s*=\s*"([^"]+)"|getSharedPreferences\("([^"]+)"""").findAll(text).map { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }.toList()
        }.toSet()
        val knownPrefs = BackupCatalog.prefs.map { it.name }.toSet() + BackupCatalog.excludedPrefs
        assertEquals("这些 SharedPreferences 还没决定备不备份", emptySet<String>(), prefsNames - knownPrefs)

        val filesDirNames = texts.values.flatMap { text ->
            Regex("""File\((?:[\w.]+\.)?(?:filesDir|noBackupFilesDir),\s*"([^"$]+)"\)""").findAll(text).map { it.groupValues[1] }.toList()
        }.toSet()
        val settingsNames = texts.filterKeys { it.parentFile.name == "settings" }.values.flatMap { text ->
            Regex("""File\(dir,\s*"([^"$]+)"\)""").findAll(text).map { "settings/" + it.groupValues[1] }.toList()
        }.toSet()
        val known = BackupCatalog.items.map { it.path }.toSet() + BackupCatalog.excludedFiles + "settings" + "adb/adbkey.bin"
        assertEquals("这些文件还没决定备不备份", emptySet<String>(), (filesDirNames + settingsNames).filter { it !in known && BackupCatalog.itemFor(it) == null }.toSet())
        assertNotNull(BackupCatalog.itemFor("settings/providers.json"))
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val TEST_ITERATIONS = 100_000
    }
}
