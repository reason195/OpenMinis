package com.openminis.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-overlay-wide-screen-width] The floating tool capsule must stay a
 * pill, not a banner, on wide screens.
 *
 * Reported 2026-09-18: in landscape and on tablets the capsule stretched to
 * "巨长", nearly spanning the display. A cap already existed from the June fix
 * (ec2c759e7, T-android-overlay-landscape-width-rotation-drift) — so this was
 * neither a regression nor a missing tablet branch. The cap was simply set too
 * loose: 400dp is roughly DOUBLE the portrait-phone capsule, so the pill still
 * grew noticeably whenever the screen widened.
 *
 * The rule the ceiling enforces is that a wide screen gets a capsule of about
 * the SAME absolute width as a portrait phone — never a proportionally wider
 * one. [T-android-overlay-enlarged] then grew the capsule deliberately (the
 * report flipped to "too small to show anything"), and both ends moved: 0.72 of
 * the display, 300dp ceiling. The portrait reference is no longer 196dp but
 * 282dp, and landscape sits 18dp above it rather than 204dp.
 *
 * [capsuleWidthDp] mirrors `ToolOverlayController.fixedCapsuleWidthPx()`;
 * that function needs a Context and live displayMetrics, so (as with the other
 * controller-adjacent suites here) the DECISION is reproduced and the source
 * constants are asserted against it.
 */
class OverlayCapsuleWidthTest {

    private val fraction = 0.72f
    private val capFraction = 0.80f
    private val capDp = 300
    private val floorDp = 200

    /** Mirror of fixedCapsuleWidthPx(), in dp. */
    private fun capsuleWidthDp(screenDp: Int): Int {
        val cap = minOf(screenDp * capFraction, capDp.toFloat())
        return maxOf(minOf(screenDp * fraction, cap), floorDp.toFloat()).toInt()
    }

    /**
     * Text budget the rows get: capsule minus the 16dp leading pad and the 44dp
     * of trailing chrome (close glyph 18 + its margin 10 + right pad 16).
     * [T-android-overlay-no-icon] The identity circle's 34dp + 6dp margin and
     * the ring-clearance padding dropped out when the icon did.
     */
    private fun textBudgetDp(screenDp: Int) = maxOf(capsuleWidthDp(screenDp) - 60, 80)

    // ── the reported bug ──────────────────────────────────────────────────

    @Test
    fun `landscape phone keeps the capsule well inside the screen`() {
        // 851dp = Pixel 4a rotated. The 400dp ceiling that was reported made
        // this a banner across nearly half the display.
        val w = capsuleWidthDp(851)
        assertEquals(300, w)
        assertTrue("capsule must stay under 40% of a landscape screen", w < 851 * 0.40f)
    }

    @Test
    fun `tablet landscape stays compact`() {
        // The configuration the June fix never visibly helped: 400dp on a
        // 1280dp screen still read as a banner.
        val w = capsuleWidthDp(1280)
        assertEquals(300, w)
        assertTrue("must be under a quarter of a tablet landscape screen", w < 1280 / 4)
    }

    @Test
    fun `tablet portrait is capped too, not just landscape`() {
        // sw800 portrait: 0.72 of 800dp is 576dp, so without the ceiling a
        // tablet is as bad as a landscape phone even without rotating. This is
        // the "missing tablet branch" the report suspected — covered by the
        // same ceiling rather than a separate configuration.
        assertEquals(300, capsuleWidthDp(800))
    }

    // ── what the enlargement bought ───────────────────────────────────────

    @Test
    fun `portrait phones get the bigger capsule`() {
        // The 2026-09 report is the reason the ceiling exists; the 2026-10 one
        // ("too small") is the reason the fraction moved. Both still hold: the
        // phone widths below bind on the fraction, not the ceiling.
        assertEquals(282, capsuleWidthDp(393))   // Pixel 4a, was 196
        assertEquals(295, capsuleWidthDp(411))   // Xiaomi / HyperOS, was 205
        assertTrue("phone portrait must not hit the ceiling", capsuleWidthDp(411) < capDp)
    }

    @Test
    fun `a wide screen is barely wider than a phone`() {
        // The invariant the ceiling exists to keep, restated for the enlarged
        // capsule: absolute growth from a phone to a tablet landscape is small,
        // so rotating or a bigger display cannot re-create the banner.
        val phone = capsuleWidthDp(393)
        for (wide in listOf(851, 914, 800, 1280, 1350)) {
            assertTrue(
                "wide screen $wide dp ${capsuleWidthDp(wide)} vs phone $phone",
                capsuleWidthDp(wide) - phone <= 20,
            )
        }
    }

    @Test
    fun `tiny screens keep their floor`() {
        // 0.72 of 260dp is 187dp, below the 200dp floor.
        assertEquals(floorDp, capsuleWidthDp(260))
    }

    @Test
    fun `the narrow-screen fraction still guards the ceiling`() {
        // On a hypothetical 300dp-wide display 300dp would be the whole screen;
        // the 0.80 factor is what stops the ceiling being applied blindly.
        // (The floor still binds at that width, but the ordering must hold.)
        val cap = minOf(300 * capFraction, capDp.toFloat())
        assertEquals(240f, cap)
        assertTrue("the 0.80 fraction must bind below the dp ceiling", cap < capDp)
    }

    @Test
    fun `wide screens never get a smaller text budget than a portrait phone`() {
        // Narrowing the pill must not introduce truncation that portrait
        // phones don't already have — otherwise the fix trades one visual
        // defect for another.
        val phone = textBudgetDp(393)
        for (wide in listOf(851, 914, 800, 1280, 1350)) {
            assertTrue(
                "wide screen $wide dp budget ${textBudgetDp(wide)} must be >= phone $phone",
                textBudgetDp(wide) >= phone,
            )
        }
    }

    @Test
    fun `the capsule really fits more text than the two-row one it replaced`() {
        // Before: a 196dp pill with 76dp of chrome (logo + close + paddings),
        // one 10sp line for the reply. Now: 282dp with 60dp of chrome — the
        // 34dp logo circle and its padding are gone — and the reply owns every
        // line past the first two.
        val before = 196 - 76
        val budget = capsuleWidthDp(393) - 60
        // 12sp glyphs are 1.2x the width of the old 10sp ones, so compare in
        // "old-character" units to keep the ratio honest.
        val oneLineNow = budget / 1.2f
        assertTrue("reply line $oneLineNow vs $before", oneLineNow > before)
        // At the tallest setting the reply row alone is four lines.
        assertTrue("6 lines gives ${budget * 4} vs $before", budget * 4 > before * 4)
    }

    // ── the source must agree with the arithmetic above ───────────────────

    @Test
    fun `source constants match the values these tests pin`() {
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt")
        assertTrue("missing ToolOverlayController source", src.exists())
        val text = src.readText()
        assertTrue(
            "CAPSULE_WIDTH_CAP_DP must be $capDp — a looser ceiling is the bug",
            text.contains("CAPSULE_WIDTH_CAP_DP = $capDp"),
        )
        // Literal match, not interpolated: Kotlin renders 0.80f as "0.8f",
        // while the source writes "0.80f". Asserting the interpolated form
        // fails on formatting rather than on the value it means to pin.
        assertTrue(text.contains("CAPSULE_WIDTH_CAP_FRACTION = 0.80f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FRACTION = 0.72f"))
        assertTrue(text.contains("CAPSULE_WIDTH_FLOOR_DP = $floorDp"))
    }

    @Test
    fun `the capsule width is applied to both the window and the text rows`() {
        // The WM container and the label maxWidth must come from the SAME
        // function, or a capped box would still be pushed open by its text.
        val src = File("src/main/java/com/openminis/app/service/ToolOverlayController.kt").readText()
        assertTrue(
            "text rows must derive their budget from fixedCapsuleWidthPx()",
            src.contains("val maxW = (fixedCapsuleWidthPx()"),
        )
        assertTrue(
            "rotation must recompute the width rather than keep the stale one",
            src.substringAfter("fun onConfigurationChanged()").contains("fixedCapsuleWidthPx()"),
        )
    }
}
