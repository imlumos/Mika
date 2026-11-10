package com.wangzan101.mika.ui

import com.wangzan101.mika.settings.RecommendedTask
import com.wangzan101.mika.settings.RecommendedTaskError
import com.wangzan101.mika.settings.RecommendedTaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecommendedTaskMenuTest {
    @get:Rule val tmp = TemporaryFolder()

    private val a = RecommendedTask("a", "甲", "甲")
    private val b = RecommendedTask("b", "乙", "乙")
    private val c = RecommendedTask("c", "丙", "丙")

    @Test fun movesWithinBoundsOnly() {
        val list = listOf(a, b, c)
        assertEquals(listOf(b, a, c), RecommendedTaskOrder.moved(list, "a", 1))
        assertEquals(listOf(a, c, b), RecommendedTaskOrder.moved(list, "c", -1))
        assertSame(list, RecommendedTaskOrder.moved(list, "a", -1))
        assertSame(list, RecommendedTaskOrder.moved(list, "c", 1))
        assertSame(list, RecommendedTaskOrder.moved(list, "没有", 1))
    }

    @Test fun addedTaskLandsRightAfterTheLongPressedOne() {
        assertEquals(listOf(a, c, b), RecommendedTaskOrder.lastPlacedAfter(listOf(a, b, c), "a"))
        assertEquals(listOf(a, b, c), RecommendedTaskOrder.lastPlacedAfter(listOf(a, b, c), "b"))
    }

    @Test fun legacyListKeepsOrderAndDropsRetiredBuiltins() {
        val raw = """[{"text":"查快递"},{"id":"gone"},{"id":"alarms"},{"text":"  "}]"""
        assertEquals(listOf("查快递", "定闹钟"), RecommendedTaskOrder.legacyTexts(raw, mapOf("alarms" to "定闹钟")))
    }

    @Test fun damagedLegacyDataIsIgnored() {
        assertEquals(emptyList<String>(), RecommendedTaskOrder.legacyTexts("not json", emptyMap()))
    }

    @Test fun oversizedLegacyTextStillFitsTheStoreLimits() {
        val task = RecommendedTaskOrder.fromText("字".repeat(RecommendedTaskStore.PROMPT_MAX + 1))
        assertEquals(RecommendedTaskStore.TITLE_MAX, task.title.length)
        assertEquals(RecommendedTaskStore.PROMPT_MAX, task.prompt.length)
    }

    @Test fun rewriteSurvivesReload() {
        val file = File(tmp.root, RecommendedTaskStore.FILE)
        val first = RecommendedTaskStore(file).apply { ensureLoaded { listOf("甲" to "甲", "乙" to "乙") } }
        assertNull(first.rewrite { it.reversed() })

        val second = RecommendedTaskStore(file).apply { ensureLoaded { emptyList() } }
        assertEquals(listOf("乙", "甲"), second.tasks.value.map { it.title })
    }

    @Test fun rewriteRefusedWhileFileIsUnreadable() {
        val file = File(tmp.root, RecommendedTaskStore.FILE).apply { writeText("not json") }
        val store = RecommendedTaskStore(file).apply { ensureLoaded { emptyList() } }
        assertEquals(RecommendedTaskError.Unreadable, store.rewrite { listOf(a) })
        assertEquals("not json", file.readText())
    }
}
