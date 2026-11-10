package com.wangzan101.mika.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.RecommendedTaskError
import com.wangzan101.mika.settings.RecommendedTaskStore
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.theme.MikaTheme
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 旧版快捷操作的一条:内置的只存 [id],用户写的存 [text]。 */
@Serializable
private data class LegacyQuickAction(val id: String? = null, val text: String? = null)

/** 长按菜单对列表的排序和旧数据的解析;不碰界面和安卓,单测直接测。 */
internal object RecommendedTaskOrder {
    fun moved(list: List<RecommendedTask>, id: String, delta: Int): List<RecommendedTask> {
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** 存储层的 add 只能加到末尾,这里把刚加的那条挪到长按的那条后面。 */
    fun lastPlacedAfter(list: List<RecommendedTask>, afterId: String): List<RecommendedTask> {
        val after = list.indexOfFirst { it.id == afterId }
        if (after < 0) return list
        return list.toMutableList().apply { add(after + 1, removeAt(lastIndex)) }
    }

    /** 存坏了或一条都认不出时返回空;新版本去掉的内置条目直接略过。 */
    fun legacyTexts(raw: String, builtin: Map<String, String>): List<String> =
        runCatching { Json.decodeFromString(ListSerializer(LegacyQuickAction.serializer()), raw) }.getOrDefault(emptyList())
            .mapNotNull { it.text ?: it.id?.let(builtin::get) }
            .filter { it.isNotBlank() }
            .take(RecommendedTaskStore.MAX_TASKS)

    /** 一段文字同时当标题和提示词,和内置任务的存法一样。 */
    fun fromText(text: String): RecommendedTask {
        val words = text.trim()
        return RecommendedTask(UUID.randomUUID().toString(), words.take(RecommendedTaskStore.TITLE_MAX), words.take(RecommendedTaskStore.PROMPT_MAX))
    }
}

/**
 * 旧版首页快捷操作存在 chat_examples 里,有这个键就说明用户改过。导入后删掉键,只做一次;
 * 从旧备份恢复出这组数据时会再导入一次。写入失败时留着,下次再试。
 */
private fun importLegacyQuickActions(context: Context, store: RecommendedTaskStore) {
    val prefs = context.applicationContext.getSharedPreferences("chat_examples", Context.MODE_PRIVATE)
    val raw = prefs.getString("examples", null) ?: return
    val builtin = mapOf(
        "follow" to tr(R.string.chat_example_follow),
        "group_summary" to tr(R.string.chat_example_group_summary),
        "alarms" to tr(R.string.chat_example_alarms),
    )
    val texts = RecommendedTaskOrder.legacyTexts(raw, builtin)
    if (texts.isNotEmpty() && store.rewrite { texts.map(RecommendedTaskOrder::fromText) } != null) return
    prefs.edit().remove("examples").apply()
}

/** 首页推荐任务一行的点按和长按;[onLongClick] 为 null 时只能点。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.recommendationClick(onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier {
    val haptic = LocalHapticFeedback.current
    return combinedClickable(
        role = Role.Button,
        onLongClickLabel = stringResource(R.string.recommendations_edit),
        onLongClick = onLongClick?.let { longClick ->
            {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                longClick()
            }
        },
        onClick = onClick,
    )
}

private sealed interface MenuDialog {
    data class Edit(val task: RecommendedTask) : MenuDialog
    data class Add(val afterId: String) : MenuDialog
    data object Restore : MenuDialog
}

/**
 * 长按首页推荐任务后的操作面板和它引出的弹窗;[acting] 是长按的那条,没长按时为 null。
 * 和设置页是同一份列表。这里至少留一条,否则首页上就没有可以长按的地方了。
 */
@Composable
internal fun RecommendedTaskMenu(store: RecommendedTaskStore, tasks: List<RecommendedTask>, acting: RecommendedTask?, onClose: () -> Unit) {
    val context = LocalContext.current
    remember(store) { importLegacyQuickActions(context, store) }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<MenuDialog?>(null) }
    val saveFailed = stringResource(R.string.recommendations_save_failed)

    fun persist(change: () -> RecommendedTaskError?) {
        scope.launch {
            if (withContext(Dispatchers.IO) { change() } != null) Toast.makeText(context, saveFailed, Toast.LENGTH_SHORT).show()
        }
    }

    acting?.let { task ->
        val index = tasks.indexOfFirst { it.id == task.id }
        ActionSheet(
            task.title,
            buildList {
                add(SheetAction(tr(R.string.edit_it)) { dialog = MenuDialog.Edit(task) })
                if (index > 0) add(SheetAction(tr(R.string.recommendation_move_up)) { persist { store.rewrite { RecommendedTaskOrder.moved(it, task.id, -1) } } })
                if (index in 0 until tasks.lastIndex) add(SheetAction(tr(R.string.recommendation_move_down)) { persist { store.rewrite { RecommendedTaskOrder.moved(it, task.id, 1) } } })
                if (tasks.size < RecommendedTaskStore.MAX_TASKS) add(SheetAction(tr(R.string.recommendations_add)) { dialog = MenuDialog.Add(task.id) })
                if (tasks.map { it.title to it.prompt } != ChatCopy.recommendationSeeds()) add(SheetAction(tr(R.string.recommendation_restore)) { dialog = MenuDialog.Restore })
                if (tasks.size > 1) add(SheetAction(tr(R.string.confirm_delete), destructive = true) { persist { store.delete(task.id) } })
            },
            onClose,
        )
    }
    when (val d = dialog) {
        is MenuDialog.Edit -> TaskTextDialog(stringResource(R.string.recommendations_edit), d.task, { dialog = null }) { title, prompt ->
            store.update(d.task.id, title, prompt)
        }
        is MenuDialog.Add -> TaskTextDialog(stringResource(R.string.recommendations_add), null, { dialog = null }) { title, prompt ->
            store.add(title, prompt) ?: store.rewrite { RecommendedTaskOrder.lastPlacedAfter(it, d.afterId) }
        }
        MenuDialog.Restore -> MikaDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.recommendation_restore)) },
            text = { Text(stringResource(R.string.recommendation_restore_message)) },
            confirmButton = {
                TextButton({
                    dialog = null
                    persist { store.rewrite { ChatCopy.recommendationSeeds().map { (title, _) -> RecommendedTaskOrder.fromText(title) } } }
                }) { Text(stringResource(R.string.recommendation_restore)) }
            },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

/** 标题和提示词相同(内置的、长按添加的)时只露一个输入框;在设置里分开写过的两个都给。 */
@Composable
private fun TaskTextDialog(heading: String, initial: RecommendedTask?, onDismiss: () -> Unit, save: (String, String) -> RecommendedTaskError?) {
    val separateTitle = initial != null && initial.title != initial.prompt
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(initial?.prompt.orEmpty()) }
    var error by remember { mutableStateOf<RecommendedTaskError?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MikaDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(heading) },
        text = {
            Column {
                if (separateTitle) {
                    OutlinedTextField(
                        title, { title = it; error = null }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text(stringResource(R.string.recommendations_title_label)) }, singleLine = true,
                    )
                }
                OutlinedTextField(
                    prompt, { prompt = it; error = null }, Modifier.fillMaxWidth().keepsKeyboard().testTag("example-text"),
                    label = if (separateTitle) ({ Text(stringResource(R.string.recommendations_prompt_label)) }) else null,
                    placeholder = { Text(ChatCopy.EMPTY_PROMPT) },
                )
                error?.let { Text(taskErrorText(it), Modifier.padding(top = 8.dp), color = MikaTheme.colors.bad) }
            }
        },
        confirmButton = {
            TextButton({
                val titleToSave = if (separateTitle) title else prompt
                val promptToSave = prompt
                saving = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { save(titleToSave, promptToSave) }
                    saving = false
                    if (result == null) onDismiss() else error = result
                }
            }, enabled = !saving && prompt.isNotBlank() && (!separateTitle || title.isNotBlank())) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton({ if (!saving) onDismiss() }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun taskErrorText(error: RecommendedTaskError): String = when (error) {
    RecommendedTaskError.TooLong -> stringResource(R.string.recommendations_too_long)
    RecommendedTaskError.TooMany -> stringResource(R.string.recommendations_too_many, RecommendedTaskStore.MAX_TASKS)
    else -> stringResource(R.string.recommendations_save_failed)
}
