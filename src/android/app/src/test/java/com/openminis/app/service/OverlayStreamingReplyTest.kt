package com.openminis.app.service

import com.openminis.app.ProductionSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-streaming-reply] The capsule's reply rows fill while the
 * model is still writing, not only once the turn ends.
 *
 * The line count the user picks (3..6) buys rows that sat empty for the whole
 * run: [AgentForegroundService]'s busy branch passed `replyExcerpt = null`, so
 * a 6-line pill carried the task title and the step and nothing else until the
 * task finished. The text now comes from [SessionActivityTracker] on every
 * throttled streaming publish.
 *
 * Driven through the real tracker — `appContext` is null in a JVM test, so the
 * service calls inside setActive fall through to logged no-ops.
 */
class OverlayStreamingReplyTest {

    private val svc = ProductionSources.read("service/AgentForegroundService.kt")
        .replace("\r\n", "\n")
    private val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        .replace("\r\n", "\n")

    @After
    fun cleanup() {
        SessionActivityTracker.setInactive("streaming-reply-test")
        SessionActivityTracker.clearOverlayTasks()
    }

    // ── the excerpt itself ────────────────────────────────────────────────

    @Test
    fun `a streamed reply collapses to one paragraph`() {
        SessionActivityTracker.publishStreamingReply("first line\n\n  second line  \nthird")
        assertEquals("first line second line third", SessionActivityTracker.streamingReplyExcerpt.value)
    }

    @Test
    fun `the budget is the 140 chars the tallest capsule can actually carry`() {
        val long = "x".repeat(200)
        SessionActivityTracker.publishStreamingReply(long)
        val excerpt = SessionActivityTracker.streamingReplyExcerpt.value!!
        // 140 chars of body plus the ellipsis, and nothing beyond it.
        assertEquals(141, excerpt.length)
        assertEquals(140, excerpt.indexOf('…'))
    }

    @Test
    fun `blank and absent text clear the row instead of leaving an empty bubble`() {
        SessionActivityTracker.publishStreamingReply("something")
        assertEquals("something", SessionActivityTracker.streamingReplyExcerpt.value)
        SessionActivityTracker.publishStreamingReply("   \n  \n")
        assertNull(SessionActivityTracker.streamingReplyExcerpt.value)
        SessionActivityTracker.publishStreamingReply(null)
        assertNull(SessionActivityTracker.streamingReplyExcerpt.value)
    }

    @Test
    fun `the next run starts blank so the last reply cannot bleed into it`() {
        SessionActivityTracker.publishStreamingReply("the previous answer")
        SessionActivityTracker.setActive("streaming-reply-test")
        assertNull(SessionActivityTracker.streamingReplyExcerpt.value)
    }

    @Test
    fun `dismissing the capsule drops the live text too`() {
        SessionActivityTracker.publishStreamingReply("the answer so far")
        SessionActivityTracker.dismissOverlay()
        assertNull(SessionActivityTracker.streamingReplyExcerpt.value)
    }

    @Test
    fun `the live and finished excerpts are built the same way`() {
        // Both rows carry the same sentence in the same pill, so a user who
        // watches a reply stream and then lingers must not see it reflow.
        val text = "alpha\nbeta\n" + "g".repeat(200)
        SessionActivityTracker.publishStreamingReply(text)
        val live = SessionActivityTracker.streamingReplyExcerpt.value
        SessionActivityTracker.publishLastReply("streaming-reply-test", text)
        assertEquals(SessionActivityTracker.lastReplyExcerpt.value, live)
    }

    // ── the wiring, so the data path cannot silently unhook ───────────────

    @Test
    fun `the busy branch hands the live text to the capsule`() {
        // The file has two `if (isBusy)` blocks; the render one is the branch
        // that calls controller.show.
        val busy = svc.substringAfter("if (isBusy) {\n                controller.show(")
        assertTrue("busy render branch not found", busy != svc && busy.isNotEmpty())
        val slice = busy.substringBefore("} else if (")
        assertTrue(slice.contains("replyExcerpt = state.streamingReplyExcerpt,"))
        // The old hard-coded null is what left the rows empty for the whole run.
        assertFalse(slice.contains("replyExcerpt = null"))
    }

    @Test
    fun `the flow is combined and read at its own index`() {
        assertTrue(
            svc.contains("                SessionActivityTracker.streamingReplyExcerpt,\n            ) { values: Array<Any?> ->"),
        )
        assertTrue(svc.contains("streamingReplyExcerpt = values[19] as String?"))
    }

    @Test
    fun `the view model publishes at the throttled gate and not per token`() {
        // Inside the local publish() of updateAssistantMessage — the single
        // place every live text push goes through, coalesced by the throttle.
        val gateStart = "fun publish(text: String, blocks: List<AssistantBlock>, awaiting: Boolean) {"
        val gate = vm.substringAfter(gateStart)
        assertTrue("publish() gate not found in ChatViewModel", gate != vm && gate.isNotEmpty())
        assertTrue(
            gate.substringBefore("\n            }").contains("SessionActivityTracker.publishStreamingReply(text)"),
        )
    }
}
