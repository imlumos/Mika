package com.wangzan101.mika.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.backup.AndroidBackupEnv
import com.wangzan101.mika.backup.BackupDamagedException
import com.wangzan101.mika.backup.BackupExporter
import com.wangzan101.mika.backup.BackupGroup
import com.wangzan101.mika.backup.BackupManifest
import com.wangzan101.mika.backup.BackupNoSpaceException
import com.wangzan101.mika.backup.BackupPasswordException
import com.wangzan101.mika.backup.BackupRestore
import com.wangzan101.mika.backup.BackupTooNewException
import com.wangzan101.mika.backup.restartApp
import com.wangzan101.mika.i18n.AppLanguage
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_PASSWORD = 8
private val DOWNLOADS: Uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")

/** 系统的保存框默认打开「下载」,用户可以换到别的位置。 */
private class CreateBackupDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS)
}

private enum class BackupPage { Home, Export, Import }

/** 设置 › 数据备份:把配置和数据导出成一个加密文件,以后再导入。实际的读写见 `backup/`。 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by (context.applicationContext as MikaApp).taskState.busy.collectAsState()
    var page by rememberSaveable { mutableStateOf(BackupPage.Home) }
    var working by remember { mutableStateOf(false) }
    val back = { if (!working) { if (page == BackupPage.Home) onBack() else page = BackupPage.Home } }
    BackHandler(onBack = back)
    PageScaffold(stringResource(R.string.backup_title), back) {
        if (busy) FooterNote(stringResource(R.string.backup_busy))
        when (page) {
            BackupPage.Home -> GroupCard {
                ListRow(MikaIcons.Share, stringResource(R.string.backup_export), stringResource(R.string.backup_export_sub), first = true,
                    onClick = { page = BackupPage.Export }) { RowChevron() }
                ListRow(MikaIcons.Doc, stringResource(R.string.backup_import), stringResource(R.string.backup_import_sub),
                    onClick = { page = BackupPage.Import }) { RowChevron() }
            }
            BackupPage.Export -> ExportPage(busy, working) { working = it }
            BackupPage.Import -> ImportPage(busy, working) { working = it }
        }
        if (working) FooterNote(stringResource(R.string.backup_working))
    }
}

@Composable
private fun ExportPage(busy: Boolean, working: Boolean, setWorking: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    var groups by remember { mutableStateOf(BackupGroup.entries.filter { it.defaultOn }.toSet()) }
    var sizes by remember { mutableStateOf<Map<BackupGroup, Long>>(emptyMap()) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        sizes = withContext(Dispatchers.IO) { runCatching { BackupExporter.sizes(AndroidBackupEnv(app)) }.getOrDefault(emptyMap()) }
    }
    val save = rememberLauncherForActivityResult(CreateBackupDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        setWorking(true)
        error = null
        val chosen = groups
        val secret = password
        app.backupScope.launch {
            val result = runCatching {
                checkNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { out ->
                    BackupExporter.export(
                        AndroidBackupEnv(app), out, secret, chosen, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME,
                        AppLanguage.current(app).tag, System.currentTimeMillis(),
                    )
                }
            }
            // 没写完的文件留着只会让人以为有备份
            if (result.isFailure) runCatching { DocumentsContract.deleteDocument(app.contentResolver, uri) }
            withContext(Dispatchers.Main) {
                setWorking(false)
                result.onSuccess {
                    val message = if (it.skipped.isEmpty()) app.getString(R.string.backup_exported)
                    else app.getString(R.string.backup_exported_skipped, it.skipped.size)
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                    password = ""
                    again = ""
                }.onFailure { error = app.getString(R.string.backup_export_failed) }
            }
        }
    }

    GroupLabel(stringResource(R.string.backup_export_what))
    GroupPicker(BackupGroup.entries, groups, sizes, !working) { groups = it }
    FooterNote(stringResource(R.string.backup_not_included))

    GroupLabel(stringResource(R.string.backup_password))
    PasswordField(password, stringResource(R.string.backup_password), !working) { password = it }
    PasswordField(again, stringResource(R.string.backup_password_again), !working) { again = it }
    val problem = when {
        password.isNotEmpty() && password.length < MIN_PASSWORD -> stringResource(R.string.backup_password_short)
        again.isNotEmpty() && again != password -> stringResource(R.string.backup_password_mismatch)
        else -> null
    }
    FooterNote(problem ?: error ?: stringResource(R.string.backup_password_warn))
    MikaButton(
        stringResource(R.string.backup_choose_place),
        onClick = { save.launch("Mika-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".mikabak") },
        Modifier.fillMaxWidth().padding(top = 12.dp),
        enabled = !busy && !working && groups.isNotEmpty() && password.length >= MIN_PASSWORD && password == again,
    )
}

@Composable
private fun ImportPage(busy: Boolean, working: Boolean, setWorking: (Boolean) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MikaApp
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var manifest by remember { mutableStateOf<BackupManifest?>(null) }
    var groups by remember { mutableStateOf(emptySet<BackupGroup>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) {
            uri = it
            manifest = null
            password = ""
            error = null
        }
    }

    MikaButton(
        stringResource(R.string.backup_choose_file), onClick = { open.launch(arrayOf("*/*")) },
        Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = !working,
    )
    val chosen = uri
    if (chosen != null && manifest == null) {
        GroupLabel(stringResource(R.string.backup_enter_password))
        PasswordField(password, stringResource(R.string.backup_password_enter), !working) { password = it }
        MikaButton(
            stringResource(R.string.backup_open),
            onClick = {
                setWorking(true)
                error = null
                val secret = password
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { BackupRestore.inspect(checkNotNull(app.contentResolver.openInputStream(chosen)), secret, BuildConfig.VERSION_CODE) }
                    }
                    setWorking(false)
                    result.onSuccess { m ->
                        manifest = m
                        // 解锁凭据即使备份里有也不默认恢复
                        groups = BackupGroup.entries.filter { it.name in m.groups && it != BackupGroup.DeviceUnlock }.toSet()
                    }.onFailure { error = app.getString(importError(it)) }
                }
            },
            Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !working && password.isNotEmpty(),
        )
    }
    manifest?.let { m ->
        FooterNote(stringResource(R.string.backup_info, DateFormat.getDateTimeInstance().format(Date(m.createdAtMs)), m.appVersionName))
        GroupLabel(stringResource(R.string.backup_import_what))
        GroupPicker(BackupGroup.entries.filter { it.name in m.groups }, groups, emptyMap(), !working) { groups = it }
        FooterNote(stringResource(R.string.backup_not_included))
        MikaButton(
            stringResource(R.string.backup_import_go), onClick = { confirming = true },
            Modifier.fillMaxWidth().padding(top = 12.dp), enabled = !busy && !working && groups.isNotEmpty(),
        )
    }
    error?.let { FooterNote(it) }

    if (confirming && chosen != null) {
        MikaDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(stringResource(R.string.backup_confirm_body)) },
            confirmButton = {
                TextButton({
                    confirming = false
                    setWorking(true)
                    error = null
                    val selected = groups
                    val secret = password
                    app.backupScope.launch {
                        val env = AndroidBackupEnv(app)
                        val result = runCatching {
                            BackupRestore.stage(env, checkNotNull(app.contentResolver.openInputStream(chosen)), secret, selected, BuildConfig.VERSION_CODE)
                            // 准备的这段时间里定时任务可能开工了;重启会打断它
                            check(!app.taskState.busy.value) { "a task started" }
                        }
                        withContext(Dispatchers.Main) {
                            result.onSuccess { restartApp(app) }.onFailure {
                                BackupRestore.discard(env)
                                setWorking(false)
                                error = app.getString(importError(it))
                            }
                        }
                    }
                }) { Text(stringResource(R.string.backup_import_go)) }
            },
            dismissButton = { TextButton({ confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun importError(e: Throwable): Int = when (e) {
    is BackupPasswordException -> R.string.backup_wrong_password
    is BackupDamagedException -> R.string.backup_damaged
    is BackupTooNewException -> R.string.backup_too_new
    is BackupNoSpaceException -> R.string.backup_no_space
    else -> R.string.backup_import_failed
}

/** 分类的开关;打开手机解锁凭据要再确认一次。[sizes] 没有时不显示大小。 */
@Composable
private fun GroupPicker(
    shown: List<BackupGroup>, selected: Set<BackupGroup>, sizes: Map<BackupGroup, Long>, enabled: Boolean, onChange: (Set<BackupGroup>) -> Unit,
) {
    val context = LocalContext.current
    var confirmUnlock by remember { mutableStateOf(false) }
    GroupCard {
        shown.forEachIndexed { i, group ->
            val on = group in selected
            ListRow(
                null, stringResource(group.title), stringResource(group.subtitle), first = i == 0,
                detail = sizes[group]?.let { Formatter.formatShortFileSize(context, it) },
                onClick = if (!enabled) null else ({
                    when {
                        on -> onChange(selected - group)
                        group == BackupGroup.DeviceUnlock -> confirmUnlock = true
                        else -> onChange(selected + group)
                    }
                }),
            ) { MikaSwitch(on, null, enabled = enabled) }
        }
    }
    if (confirmUnlock) {
        MikaDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text(stringResource(R.string.backup_unlock_title)) },
            text = { Text(stringResource(R.string.backup_unlock_body)) },
            confirmButton = {
                TextButton({
                    onChange(selected + BackupGroup.DeviceUnlock)
                    confirmUnlock = false
                }) { Text(stringResource(R.string.backup_unlock_ok)) }
            },
            dismissButton = { TextButton({ confirmUnlock = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun PasswordField(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OutlinedTextField(
            value, onChange, Modifier.fillMaxWidth().keepsKeyboard(), enabled = enabled, singleLine = true,
            label = { Text(label) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
}

private val BackupGroup.title: Int get() = when (this) {
    BackupGroup.Settings -> R.string.backup_group_settings
    BackupGroup.Models -> R.string.backup_group_models
    BackupGroup.Connections -> R.string.backup_group_connections
    BackupGroup.Personal -> R.string.backup_group_personal
    BackupGroup.Conversations -> R.string.backup_group_conversations
    BackupGroup.Browser -> R.string.backup_group_browser
    BackupGroup.Workspaces -> R.string.backup_group_workspaces
    BackupGroup.RunRecords -> R.string.backup_group_runs
    BackupGroup.DeviceUnlock -> R.string.backup_group_unlock
}

private val BackupGroup.subtitle: Int get() = when (this) {
    BackupGroup.Settings -> R.string.backup_group_settings_sub
    BackupGroup.Models -> R.string.backup_group_models_sub
    BackupGroup.Connections -> R.string.backup_group_connections_sub
    BackupGroup.Personal -> R.string.backup_group_personal_sub
    BackupGroup.Conversations -> R.string.backup_group_conversations_sub
    BackupGroup.Browser -> R.string.backup_group_browser_sub
    BackupGroup.Workspaces -> R.string.backup_group_workspaces_sub
    BackupGroup.RunRecords -> R.string.backup_group_runs_sub
    BackupGroup.DeviceUnlock -> R.string.backup_group_unlock_sub
}
