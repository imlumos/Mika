package com.wangzan101.mika.ui

import com.wangzan101.mika.ui.ChatCopy.Example
import com.wangzan101.mika.ui.ChatCopy.ExampleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChatExamplesTest {
    private val alarm = Example(ExampleKind.Device, "定闹钟", "alarms")
    private val jobs = Example(ExampleKind.Web, "投简历", "jobs")
    private val mine = Example(ExampleKind.Custom, "查快递")

    @Test fun neverSavedOrUnreadableFallsBackToBuiltin() {
        val builtin = listOf(alarm, jobs)
        assertEquals(builtin, ExampleList.decode(null, builtin))
        assertEquals(builtin, ExampleList.decode("not json", builtin))
        assertEquals(builtin, ExampleList.decode("""[{"id":"gone"}]""", builtin))
    }

    /** 内置的只存 id:换了界面语言,读回来的是新语言的文字。 */
    @Test fun builtinFollowsTheLanguageAndCustomKeepsItsWords() {
        val raw = ExampleList.encode(listOf(jobs, mine))
        val english = Example(ExampleKind.Web, "Apply to jobs", "jobs")
        assertEquals(listOf(english, mine), ExampleList.decode(raw, listOf(alarm.copy(text = "Set alarms"), english)))
    }

    @Test fun builtinRemovedByANewVersionIsSkipped() {
        val raw = ExampleList.encode(listOf(alarm, mine))
        assertEquals(listOf(mine), ExampleList.decode(raw, listOf(jobs)))
    }

    @Test fun editingBecomesCustomUnlessTheWordsStayTheSame() {
        val list = listOf(alarm, jobs)
        assertSame(list, ExampleList.edited(list, 0, " 定闹钟 "))
        assertEquals(listOf(alarm, Example(ExampleKind.Custom, "找工作")), ExampleList.edited(list, 1, " 找工作\n"))
    }

    @Test fun addRemoveAndMove() {
        val list = listOf(alarm, jobs)
        assertEquals(listOf(alarm, mine, jobs), ExampleList.added(list, 0, " 查快递 "))
        assertEquals(listOf(alarm, jobs, mine), ExampleList.added(list, 1, "查快递"))
        assertEquals(listOf(jobs), ExampleList.removed(list, 0))
        assertEquals(listOf(jobs, alarm), ExampleList.moved(list, 0, 1))
        assertEquals(listOf(jobs, alarm), ExampleList.moved(list, 1, -1))
    }
}
