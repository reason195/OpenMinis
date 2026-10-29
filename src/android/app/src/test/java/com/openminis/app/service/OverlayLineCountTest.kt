package com.openminis.app.service

import com.openminis.app.ProductionSources
import com.openminis.app.data.repository.BackgroundSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-line-count] The capsule's height is derived from the line
 * count the user picks in Settings (3..6), one line for the task, one for the
 * step, the rest for the reply — and [T-android-overlay-no-icon] it carries no
 * icon: the identity circle, its plate and the ring that traced it are gone, so
 * their width is the text's.
 *
 * [capsuleHeightDp] is the production function itself, not a port: it lives in
 * the companion object and needs no Context. The source-grep guards below use
 * LF-normalised text on purpose — a `contains("a\nb")` against
 * [ProductionSources.read] fails on a CRLF checkout, which has already made
 * five unrelated assertions red on Windows.
 */
class OverlayLineCountTest {

    private val src = ProductionSources.read("service/ToolOverlayController.kt")
        .replace("\r\n", "\n")

    // ── height ────────────────────────────────────────────────────────────

    @Test
    fun `each choice is 16dp taller than the last and 3 lines matches the old pill`() {
        // 12dp of vertical padding + a 19dp title line + (n-1) 16dp body lines.
        assertEquals(63, ToolOverlayController.capsuleHeightDp(3))
        assertEquals(79, ToolOverlayController.capsuleHeightDp(4))
        assertEquals(95, ToolOverlayController.capsuleHeightDp(5))
        assertEquals(111, ToolOverlayController.capsuleHeightDp(6))
    }

    @Test
    fun `the growth is linear in the lines, not in a hand-tuned table`() {
        // From 4 up: capsuleHeightDp clamps below 3, so 3 - 1 is not a step.
        for (n in 4..6) {
            assertEquals(
                "line $n",
                ToolOverlayController.capsuleHeightDp(n - 1) + 16,
                ToolOverlayController.capsuleHeightDp(n),
            )
        }
    }

    @Test
    fun `out-of-range counts clamp instead of producing a 2-line or 200-line card`() {
        assertEquals(ToolOverlayController.capsuleHeightDp(3), ToolOverlayController.capsuleHeightDp(0))
        assertEquals(ToolOverlayController.capsuleHeightDp(6), ToolOverlayController.capsuleHeightDp(99))
    }

    @Test
    fun `the corner stays a pill when short and stops being an oval when tall`() {
        // radius = min(height/2, 32dp): 3 lines is 31.5 -> a true pill, 6 lines
        // would be 55.5 at half-height, which is an oval, so it caps at 32.
        fun radius(lines: Int) = minOf(ToolOverlayController.capsuleHeightDp(lines) / 2f, 32f)
        assertEquals(31.5f, radius(3), 0.001f)
        assertEquals(32f, radius(6), 0.001f)
        assertTrue("6 lines must not be a full pill", radius(6) < ToolOverlayController.capsuleHeightDp(6) / 2f)
    }

    // ── the repository's side of the contract ─────────────────────────────

    @Test
    fun `the settings range the repository offers matches what the capsule draws`() {
        assertEquals(3, BackgroundSettingsRepository.MIN_OVERLAY_LINES)
        assertEquals(6, BackgroundSettingsRepository.MAX_OVERLAY_LINES)
        assertEquals(3, BackgroundSettingsRepository.DEFAULT_OVERLAY_LINES)
        assertEquals(
            ToolOverlayController.OVERLAY_MIN_LINES,
            BackgroundSettingsRepository.MIN_OVERLAY_LINES,
        )
        assertEquals(
            ToolOverlayController.OVERLAY_MAX_LINES,
            BackgroundSettingsRepository.MAX_OVERLAY_LINES,
        )
    }

    // ── no icon ───────────────────────────────────────────────────────────

    @Test
    fun `the capsule builds no logo, plate or ring`() {
        for (gone in listOf(
            "RotatingRingView", "logoStack", "plateView", "ringView", "logoView",
            "applyLogoFor", "applySoulIdentityIcon", "emojiBitmap", "startRingAnimation",
            "ringAnimator", "LOGO_SIZE_DP", "GLYPH_INSET_FRACTION", "OverlayRingGeometry",
            "OverlayMargins", "ic_launcher",
        )) {
            assertFalse("'$gone' is gone from the overlay", src.contains(gone))
        }
        // The container's only children are the text column and the X button.
        assertEquals(
            "textCol and close are the only container children",
            2,
            Regex("container\\.addView\\(").findAll(src).count(),
        )
    }

    @Test
    fun `the reply row owns every line past the title and the step`() {
        assertTrue(src.contains("maxLines = (lineCount - 2).coerceAtLeast(1)"))
        assertTrue(src.contains("rv.maxLines = (lineCount - 2).coerceAtLeast(1)"))
        // Row 2 is the step line again: it stopped absorbing the reply.
        val status = src.substringAfter("val status = TextView(context).apply {").substringBefore("statusView = status")
        assertTrue("step line is single-line", status.contains("maxLines = 1"))
        assertFalse("step line no longer doubles as the reply", status.contains("maxLines = 2"))
    }

    @Test
    fun `the text budget subtracts only the close button and paddings`() {
        assertTrue(src.contains("private const val CAPSULE_NON_TEXT_DP = 44"))
        assertTrue(src.contains("private const val CAPSULE_PADDING_START_DP = 16"))
        // 18 close + 10 its margin + 16 right pad.
        assertEquals(44, 18 + 10 + 16)
        assertTrue(src.contains("val maxW = (fixedCapsuleWidthPx() - dpToPx(CAPSULE_PADDING_START_DP + CAPSULE_NON_TEXT_DP))"))
        // All three rows share the cap, so none of them can push the box open.
        assertTrue(src.contains("replyView?.maxWidth = maxW"))
    }

    @Test
    fun `a shown capsule is resized when the setting changes`() {
        val set = src.substringAfter("fun setLineCount(lines: Int) {").substringBefore("    fun show(")
        assertTrue("ignores a repeat value", set.contains("if (safe == lineCount) return"))
        assertTrue("clamps", set.contains("lines.coerceIn(OVERLAY_MIN_LINES, OVERLAY_MAX_LINES)"))
        assertTrue("re-applies the window height", set.contains("params.height = height"))
        assertTrue("re-applies the corner radius", set.contains("v.background = capsuleBackground(v.resources.displayMetrics.density)"))
        // The service pushes it on every state pass, before any show()/hide().
        val svc = ProductionSources.read("service/AgentForegroundService.kt").replace("\r\n", "\n")
        assertTrue(svc.contains("controller.setLineCount(state.overlayLines)"))
        assertTrue(svc.contains("backgroundRepo.overlayLineCount,"))
    }
}
