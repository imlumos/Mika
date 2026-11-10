package com.wangzan101.mika.backup

import com.wangzan101.mika.core.record.RunMetricsStore
import com.wangzan101.mika.settings.RecommendedTaskStore

/** 备份按这些分类选;[defaultOn] 为假的要用户自己勾。 */
enum class BackupGroup(val defaultOn: Boolean = true) {
    Settings, Models, Connections, Personal, Conversations, Browser,
    Workspaces(false), RunRecords(false), DeviceUnlock(false),
}

/** 存在 Keystore 里的密钥怎么解:整个文件一份密文,或 JSON 里每个值一份 Base64 密文。 */
internal sealed interface SecretForm {
    val alias: String
    data class WholeFile(override val alias: String) : SecretForm
    data class JsonValues(override val alias: String) : SecretForm
}

/** 一项备份内容:[path] 相对 App 的 files 目录,[dir] 为真时是整个目录。 */
internal data class BackupItem(val group: BackupGroup, val path: String, val dir: Boolean = false, val secret: SecretForm? = null)

/**
 * 一组 SharedPreferences。[dropped] 里的键不进备份:跟设备走的、或给 Mika 放权的,导入后回到默认。
 * [encrypted] 里的键值是 Keystore 密文,导出时解开、导入时用新设备的密钥重新加密。
 */
internal data class BackupPrefs(
    val group: BackupGroup,
    val name: String,
    val dropped: Set<String> = emptySet(),
    val encrypted: Map<String, String> = emptyMap(),
)

/** 备份的白名单:导出只读这些,导入只写这些,文件里写的别的路径一律拒绝。 */
internal object BackupCatalog {
    const val APPROVALS = "approvals.json"
    const val TRIGGERS = "triggers-v3.json"
    const val MCP = "settings/mcp.json"
    const val RELAY = "settings/remote-relay.bin"

    val items: List<BackupItem> = listOf(
        BackupItem(BackupGroup.Settings, "app-access.json"),
        BackupItem(BackupGroup.Settings, "sandbox-settings.json"),
        BackupItem(BackupGroup.Settings, "memory_settings.json"),
        BackupItem(BackupGroup.Settings, RecommendedTaskStore.FILE),
        BackupItem(BackupGroup.Settings, "settings/net-proxy.bin", secret = SecretForm.WholeFile("mika-net-proxy")),

        BackupItem(BackupGroup.Models, "settings/providers.json"),
        BackupItem(BackupGroup.Models, "settings/provider-keys.json", secret = SecretForm.JsonValues("mika-provider-keys")),
        BackupItem(BackupGroup.Models, "settings/chatgpt-host.json"),

        BackupItem(BackupGroup.Connections, MCP),
        BackupItem(BackupGroup.Connections, "settings/mcp-token.bin", secret = SecretForm.WholeFile("mika-mcp-token")),
        BackupItem(BackupGroup.Connections, RELAY, secret = SecretForm.WholeFile("mika-remote-relay")),
        BackupItem(BackupGroup.Connections, "settings/mcp-servers.json"),
        BackupItem(BackupGroup.Connections, "settings/mcp-server-secrets.bin", secret = SecretForm.WholeFile("mika-mcp-servers")),

        BackupItem(BackupGroup.Personal, "memory/memory.bin", secret = SecretForm.WholeFile("mika-memory")),
        BackupItem(BackupGroup.Personal, "memory/profile.bin", secret = SecretForm.WholeFile("mika-memory")),
        BackupItem(BackupGroup.Personal, "skills", dir = true),
        BackupItem(BackupGroup.Personal, "personal", dir = true),
        BackupItem(BackupGroup.Personal, "knowledge/app-drafts", dir = true),
        BackupItem(BackupGroup.Personal, "routes", dir = true),

        BackupItem(BackupGroup.Conversations, "conversations", dir = true),
        BackupItem(BackupGroup.Conversations, TRIGGERS),
        BackupItem(BackupGroup.Conversations, "usage-v1.json"),
        BackupItem(BackupGroup.Conversations, RunMetricsStore.FILE_NAME),

        BackupItem(BackupGroup.Browser, "web", dir = true),

        BackupItem(BackupGroup.Workspaces, "sandbox/work", dir = true),
        BackupItem(BackupGroup.RunRecords, "runs", dir = true),
        BackupItem(BackupGroup.DeviceUnlock, "settings/device-access.bin", secret = SecretForm.WholeFile("mika-device-access")),
    )

    val prefs: List<BackupPrefs> = listOf(
        // 首页已改用推荐任务,这组不再有人读;旧备份里带着它,去掉会被当成损坏文件拒绝导入
        BackupPrefs(BackupGroup.Settings, "chat_examples"),
        BackupPrefs(BackupGroup.Settings, "trace_settings"),
        BackupPrefs(
            BackupGroup.Settings, "feature_flags",
            // 系统语音服务是这台设备的情况;self_config 让 Mika 能改自己的设置,和自主模式一样由用户重新打开
            dropped = setOf("speech_system_silent", "speech_system_silent_strikes", "speech_system_service", "self_config"),
            encrypted = mapOf("speech_doubao_credentials_encrypted" to "mika-speech-keys"),
        ),
    )

    /** 明确不备份的 SharedPreferences:都是这台设备的运行状态、授权或询问记录。 */
    val excludedPrefs: Set<String> = setOf(
        "apk-update", "builtin_shell", "builtin_shell_recovery", "phone_setup", "builtin_helper",
        // 语言在安卓 13 起存在系统里,单独写进备份说明
        "app_language",
        "task_recovery", "triggers", "attach", "onboarding", "voice_input",
        // 给 Mika 放 root 权限,要用户在这台手机上重新授权
        "root",
        // 自动更新开关跟这台设备走
        "automatic-updates",
        // API Key 按服务商分键存 Keystore 密文,目录的固定键映射解不开
        "web_search",
    )

    /** 明确不备份的文件:运行中的账本、缓存,和导入时另行重置的审批模式。 */
    val excludedFiles: Set<String> = setOf(
        APPROVALS, "children-v3.json", "runs-v3.json", "outbox-v3.json", "update-v1.json",
        "settings/mcp-usb.json", "sandbox", "harness-traces", "adb",
        // 微信绑定能从外面给手机派任务,要用户重新扫码
        "wechat",
        // 排队中的追问和未发送草稿的临时副本,都属于这台手机上正在进行的事
        "followups-v1.json", "shared-drafts", "library-attachments",
    )

    /** 路径属于哪一项;不在白名单里、或想跳出目录的返回 null。 */
    fun itemFor(path: String): BackupItem? {
        if (path.isEmpty() || path.startsWith("/") || '\\' in path || '\u0000' in path) return null
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return items.firstOrNull { path == it.path || (it.dir && path.startsWith(it.path + "/")) }
    }
}
