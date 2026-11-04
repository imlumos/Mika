package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.ui.ChatCopy.Example
import com.wangzan101.mika.ui.ChatCopy.ExampleKind
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.MikaDialog
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.keepsKeyboard
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 存一条示例:内置的只存 [id],文字跟着界面语言走;用户写的存 [text]。 */
@Serializable
private data class StoredExample(val id: String? = null, val text: String? = null)

/** 对示例列表的增删改排和存取;不碰界面和安卓,单测直接测。 */
internal object ExampleList {
    /** 改文字。内容没变时保留原样,内置的那条不会因此失去翻译和图标。 */
    fun edited(list: List<Example>, index: Int, text: String): List<Example> {
        val words = text.trim()
        if (words == list[index].text) return list
        return list.toMutableList().apply { this[index] = Example(ExampleKind.Custom, words) }
    }

    fun added(list: List<Example>, after: Int, text: String): List<Example> =
        list.toMutableList().apply { add(after + 1, Example(ExampleKind.Custom, text.trim())) }

    fun removed(list: List<Example>, index: Int): List<Example> = list.filterIndexed { i, _ -> i != index }

    fun moved(list: List<Example>, index: Int, delta: Int): List<Example> =
        list.toMutableList().apply { add(index + delta, removeAt(index)) }

    fun encode(list: List<Example>): String = Json.encodeToString(
        ListSerializer(StoredExample.serializer()),
        list.map { if (it.id != null) StoredExample(id = it.id) else StoredExample(text = it.text) },
    )

    /** 没存过、存坏了或一条都认不出时用内置的;新版本去掉的内置条目直接略过。 */
    fun decode(raw: String?, builtin: List<Example>): List<Example> {
        val stored = raw?.let {
            runCatching { Json.decodeFromString(ListSerializer(StoredExample.serializer()), it) }.getOrNull()
        } ?: return builtin
        val byId = builtin.associateBy { it.id }
        return stored.mapNotNull { s -> s.text?.let { Example(ExampleKind.Custom, it) } ?: byId[s.id] }.ifEmpty { builtin }
    }
}

/** 用户排好的示例;存在本机。 */
internal class ExampleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<Example> = ExampleList.decode(prefs.getString(EXAMPLES, null), ChatCopy.examples)

    fun save(list: List<Example>) {
        prefs.edit().putString(EXAMPLES, ExampleList.encode(list)).apply()
    }

    fun reset() {
        prefs.edit().remove(EXAMPLES).apply()
    }

    private companion object {
        const val PREFS = "chat_examples"
        const val EXAMPLES = "examples"
    }
}

private sealed interface ExampleDialog {
    data class Edit(val index: Int) : ExampleDialog
    data class Add(val after: Int) : ExampleDialog
    data object Reset : ExampleDialog
}

/**
 * 长按示例后的操作面板和它引出的弹窗;[acting] 是长按的第几条,没长按时为 null。
 * 至少留一条:否则首页上就没有可以长按的地方,加不回来了。
 */
@Composable
internal fun ExampleEditor(
    examples: List<Example>,
    acting: Int?,
    onClose: () -> Unit,
    onChange: (List<Example>) -> Unit,
    onReset: () -> Unit,
) {
    var dialog by remember { mutableStateOf<ExampleDialog?>(null) }
    acting?.let { i ->
        ActionSheet(
            examples[i].text,
            buildList {
                add(SheetAction(tr(R.string.edit_it)) { dialog = ExampleDialog.Edit(i) })
                if (i > 0) add(SheetAction(tr(R.string.chat_example_move_up)) { onChange(ExampleList.moved(examples, i, -1)) })
                if (i < examples.lastIndex) add(SheetAction(tr(R.string.chat_example_move_down)) { onChange(ExampleList.moved(examples, i, 1)) })
                add(SheetAction(tr(R.string.chat_example_add)) { dialog = ExampleDialog.Add(i) })
                if (examples != ChatCopy.examples) add(SheetAction(tr(R.string.chat_example_reset)) { dialog = ExampleDialog.Reset })
                if (examples.size > 1) add(SheetAction(tr(R.string.confirm_delete), destructive = true) { onChange(ExampleList.removed(examples, i)) })
            },
            onClose,
        )
    }
    when (val d = dialog) {
        is ExampleDialog.Edit -> ExampleTextDialog(stringResource(R.string.chat_example_edit), examples[d.index].text, { dialog = null }) {
            onChange(ExampleList.edited(examples, d.index, it))
            dialog = null
        }
        is ExampleDialog.Add -> ExampleTextDialog(stringResource(R.string.chat_example_add), "", { dialog = null }) {
            onChange(ExampleList.added(examples, d.after, it))
            dialog = null
        }
        ExampleDialog.Reset -> MikaDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.chat_example_reset)) },
            text = { Text(stringResource(R.string.chat_example_reset_message)) },
            confirmButton = {
                TextButton({
                    onReset()
                    dialog = null
                }) { Text(stringResource(R.string.chat_example_reset)) }
            },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        null -> Unit
    }
}

@Composable
private fun ExampleTextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().keepsKeyboard().testTag("example-text"),
                placeholder = { Text(ChatCopy.EMPTY_PROMPT) },
            )
        },
        confirmButton = { TextButton({ onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
