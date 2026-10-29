package com.openminis.app.service

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Chronometer
import android.widget.TextView
import com.openminis.app.MinisApp
import com.openminis.app.agent.SoulMetadata
import com.openminis.app.agent.SoulStore
import com.openminis.app.ui.chat.friendlyToolTitleFor
import com.openminis.app.R
import kotlin.math.abs

/**
 * T-bg-overlay phase 2: floating tool-status overlay.
 *
 * Adds a draggable capsule via [WindowManager] using
 * TYPE_APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW). The capsule is
 * pure text — same data the FGS notification reads
 * (`SessionActivityTracker.currentToolName` / `currentToolStatus`):
 *
 *   row 1     the task's title (Soul name until a session has one)
 *   row 2     the step: the model's tool_title, or generating / thinking /
 *             the countdown to the next scheduled run
 *   row 3 ..  the assistant's reply, led by the outcome ✓/✗
 *
 * [T-android-overlay-line-count] How many lines rows 3+ get is the user's
 * choice in Settings (3..6 total), which also sets the window height.
 *
 * [T-android-overlay-no-icon] It used to lead with the Minis launcher icon
 * clipped to a circle, with a thin stroke ring rotating around it while a tool
 * ran. The user asked for the icon out; the ring traced that circle, so it
 * went too, and running state is carried by the status line and the clock.
 *
 * Owned by [AgentForegroundService]; created on service start, destroyed
 * on service stop. Visibility is driven by the service's collector:
 *
 *   - Minis foreground OR no tool running OR user toggle OFF OR no perm → hidden
 *   - otherwise → shown, content updated on each tool-status change
 *
 * Position persists in [com.openminis.app.data.repository.BackgroundSettingsRepository]
 * across process restarts; first run defaults to the bottom-left corner
 * (10 dp from each edge). Tap dismisses to bring Minis back to the
 * foreground; drag moves the capsule.
 */
class ToolOverlayController(private val context: Context) {

    companion object {
        private const val TAG = "ToolOverlayController"
        private const val DRAG_SLOP_DP = 8f

        // [T-android-overlay-no-icon] The leading identity circle is gone —
        // the user asked for the icon back out of the capsule, which hands its
        // 34dp plus 6dp margin plus 15dp of ring-clearance padding straight to
        // the text. The spinner ring traced that circle and the plate sat
        // behind it, so both go with it; running state is carried by the status
        // line and the ticking clock instead. The outcome still gets its ✓/✗,
        // which leads the reply line.

        // [T-android-overlay-two-row] Tool glyph on row 2. Slightly larger
        // than the text beside it so the icon reads at a glance without
        // making the row taller than the text's own line height.
        private const val TOOL_ICON_SIZE_DP = 16
        private const val EDGE_PADDING_DP = 10

        // [T-android-overlay-line-count] The capsule is as tall as the number
        // of text lines the user picked in Settings (3..6): one line for the
        // task title, one for the current step, the rest for the reply.
        //
        // Height is DERIVED, never pinned per choice, so the arithmetic below
        // is the single source of truth. Measured line boxes on a 440dpi
        // device (2.75x, font scale 1.0): 12sp measured 40px ≈ 14.5dp and 14sp
        // 44px ≈ 16dp, and the rule the old CAPSULE_HEIGHT_DP comment used is
        // 1.33dp per sp — which is where TITLE_LINE_DP and BODY_LINE_DP come
        // from, rounded up so a wrapped line cannot be clipped by the fixed
        // window height.
        //
        //     lines 3  12 + 19 + 2 x 16 = 63dp
        //     lines 4  12 + 19 + 3 x 16 = 79dp
        //     lines 5  12 + 19 + 4 x 16 = 95dp
        //     lines 6  12 + 19 + 5 x 16 = 111dp
        //
        // [T-android-overlay-dimensions-stable] The height is fixed FOR A GIVEN
        // choice rather than wrapping the content: a capsule that grew every
        // time a reply arrived was the jitter this file's history keeps
        // warning about. Changing the setting re-lays-out a shown capsule
        // ([setLineCount]), which is a user action, not a state flip.
        internal const val OVERLAY_MIN_LINES = 3
        internal const val OVERLAY_MAX_LINES = 6
        private const val TITLE_LINE_DP = 19f
        private const val BODY_LINE_DP = 16f
        private const val CAPSULE_VERTICAL_PADDING_DP = 6

        /** Window height for [lines] text lines, in dp. */
        internal fun capsuleHeightDp(lines: Int): Int =
            (2 * CAPSULE_VERTICAL_PADDING_DP + TITLE_LINE_DP +
                BODY_LINE_DP * (lines.coerceIn(OVERLAY_MIN_LINES, OVERLAY_MAX_LINES) - 1))
                .toInt()

        // [T-android-overlay-pill-shape] Corner radius: a true pill while the
        // card is short, then held at this value so a 6-line card stays a
        // rounded rectangle instead of becoming an oval.
        private const val CAPSULE_CORNER_RADIUS_DP = 32f

        // Leading padding. It used to be 15dp so the spinner ring was as far
        // from the left edge as from its top and bottom; with no ring there is
        // nothing to centre against, so it matches the trailing side again.
        private const val CAPSULE_PADDING_START_DP = 16
        // Everything in the capsule's row that is not text, besides the leading
        // padding: close glyph 18 + its left margin 10 + right padding 16 (the
        // inset that clears the pill's rounded corner).
        private const val CAPSULE_NON_TEXT_DP = 44

        /**
         * [T-android-overlay-multitask-pill] Nominal height of the "n of m"
         * chip, used only to derive its corner radius (radius = half height,
         * the same rule the capsule itself follows). The view is WRAP_CONTENT,
         * so this does not size it: 11sp text plus 1dp of vertical padding
         * lands within a dp or two of 18, and any radius at or above half the
         * real height renders as a full pill anyway.
         */
        private const val TASK_PILL_HEIGHT_DP = 18
        // [T-android-overlay-fixed-half-width] User-facing requirement:
        // the capsule has a FIXED width — no jitter as content cycles
        // through spinner / completed / reply / error. Applied to the
        // WindowManager.LayoutParams.width AND to the text-row maxWidth
        // so labels ellipsize within the same envelope instead of
        // pushing the box outward.
        //
        // [T-android-overlay-enlarged] "Fixed" is what this is about; the
        // fraction was not the earlier half-screen value because the capsule
        // is now the larger one the user asked for: 0.72 of the display width.
        private const val CAPSULE_WIDTH_FRACTION = 0.72f
        private const val CAPSULE_WIDTH_FLOOR_DP = 200
        // [T-android-overlay-landscape-width-rotation-drift] Upper bound on
        // the capsule width. In landscape `widthPixels` is large, so the
        // width fraction alone produced a pill that visually spanned most of
        // the screen.
        //
        // [T-android-overlay-wide-screen-width] The ceiling was 400dp, which
        // still read as "巨长" on landscape phones and tablets. 400dp is about
        // DOUBLE the portrait-phone capsule, so the pill grew markedly whenever
        // the screen got wider — which is exactly the complaint, even though
        // the cap was technically doing its job.
        //
        // The rule the ceiling enforces is that a wide screen gets a pill of
        // roughly the SAME absolute size as a portrait phone, not a
        // proportionally wider one. [T-android-overlay-enlarged] raised both
        // ends together (portrait 196dp -> 283dp), so the ceiling moved with
        // them to 300dp — 17dp above a Pixel portrait, still nowhere near the
        // 47%-of-the-screen banner that was reported. Measured result:
        //
        //   phone portrait  393dp -> 283dp (72%, was 196dp/50%)
        //   phone landscape 851dp -> 300dp (35%, was 240dp)
        //   tablet portrait 800dp -> 300dp (38%, was 240dp)
        //   tablet landscape 1280dp -> 300dp (23%, was 240dp)
        //
        // The 0.80 fraction is kept for very narrow screens where 300dp could
        // otherwise exceed the display.
        private const val CAPSULE_WIDTH_CAP_FRACTION = 0.80f
        private const val CAPSULE_WIDTH_CAP_DP = 300
        private val SUCCESS_COLOR = Color.parseColor("#4CAF50")
        private val FAILURE_COLOR = Color.parseColor("#E53935")
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRepo =
        (context.applicationContext as MinisApp).backgroundSettingsRepository

    /** [T-overlay-orientation-position] One saved position per orientation. */
    private val positionStore = object : OverlayPosition.Store {
        override fun get(orientation: OverlayPosition.Orientation): OverlayPosition.Point? =
            backgroundRepo.getOverlayPosition(landscape = orientation == OverlayPosition.Orientation.LANDSCAPE)
                ?.let { OverlayPosition.Point(it.first, it.second) }

        override fun set(orientation: OverlayPosition.Orientation, point: OverlayPosition.Point) =
            backgroundRepo.setOverlayPosition(point.x, point.y, landscape = orientation == OverlayPosition.Orientation.LANDSCAPE)
    }

    private var view: View? = null
    private var labelView: TextView? = null
    private var statusView: TextView? = null

    /** [T-android-overlay-multitask] Row 1 trailing "n of m" indicator. */
    private var taskIndexView: TextView? = null

    /**
     * [T-android-overlay-multitask-pill] The chip and the clock, which share
     * the capsule's right-hand column. Held together so both can be measured
     * and given the same width, which is what keeps the clock centred under
     * the chip rather than merely near it.
     */
    private var rightColumnViews: List<View> = emptyList()
    // [T-android-overlay-chronometer] Self-driving elapsed clock for the
    // capsule. A Chronometer, not a TextView: the capsule is only repainted
    // when the observed tool state CHANGES, and a long tool call changes
    // nothing for minutes — so any text-based timer here would sit frozen,
    // which is the reported complaint. Chronometer ticks itself on the UI
    // thread and needs no push from the service.
    private var elapsedView: Chronometer? = null
    // [T-android-overlay-two-row] Row 2 is now "tool icon + tool title"
    // instead of a plain status string, so the row needs its own container
    // (to show/hide both children together) and a handle on the icon.
    private var statusRowView: LinearLayout? = null
    // [T-android-overlay-line-count] The reply row: everything past the title
    // and the current step. Its leading ✓/✗ is the outcome glyph, which used to
    // lead row 2 back when row 2 doubled as the reply line.
    private var replyRowView: LinearLayout? = null
    private var replyView: TextView? = null
    private var replyGlyphView: StatusGlyphView? = null

    /**
     * [T-android-overlay-line-count] Text lines the capsule reserves, owned by
     * Settings ([com.openminis.app.data.repository.BackgroundSettingsRepository.overlayLineCount])
     * and pushed in through [setLineCount]. Held as a plain field because the
     * height is baked into the WindowManager.LayoutParams at attach time, and
     * the rows' maxLines at build time.
     */
    private var lineCount: Int = OVERLAY_MIN_LINES
    private var closeView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    // [T-android-overlay-reply-status-34599] Session ID associated with
    // the current overlay capsule. The whole-capsule tap builds a
    // `minis://session/<id>` deep-link to land back in the right chat;
    // null falls through to "just bring MainActivity forward" so we
    // never strand the user when no session id was published.
    private var pendingSessionId: String? = null
    /**
     * [T-android-overlay-reply-status-34599] Called when the user
     * dismisses the overlay (X button or tap-to-open). Lets the
     * service-level observer wipe the lingered completion state so the
     * AND-gate flips `shouldShow` to false on the next emission. Wired
     * by [AgentForegroundService] right after construction.
     */
    var onDismissByUser: (() -> Unit)? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * [T-android-overlay-line-count] Apply the user's line-count choice.
     *
     * Called by [AgentForegroundService] before every `show()`, and it is a
     * no-op when the value did not move — the service re-emits on any observed
     * flow change, so without the guard a settings write would resize a
     * capsule that is already correct.
     *
     * A shown capsule is re-laid-out in place: the window height comes from
     * [lineCount], so the answer to "how many lines does the user want" is only
     * complete once the window itself is resized. The rows' maxLines are
     * re-applied by [updateContent] on the `show()` that follows.
     */
    fun setLineCount(lines: Int) {
        val safe = lines.coerceIn(OVERLAY_MIN_LINES, OVERLAY_MAX_LINES)
        if (safe == lineCount) return
        lineCount = safe
        mainHandler.post {
            val v = view ?: return@post
            val params = layoutParams ?: return@post
            val height = dpToPx(capsuleHeightDp(safe))
            if (params.height == height) return@post
            params.height = height
            v.background = capsuleBackground(v.resources.displayMetrics.density)
            try {
                windowManager.updateViewLayout(v, params)
            } catch (e: Throwable) {
                Log.w(TAG, "setLineCount relayout failed: ${e.message}")
            }
        }
    }

    /**
     * [T-port-sched-overlay-waiting] What the capsule shows while a chat waits
     * for its next scheduled run: [statusText] ("Next run · 2/3") and the run's
     * instant on the elapsedRealtime clock (null for an on-completion wait).
     */
    data class Waiting(val statusText: String, val nextRunElapsedMs: Long?)

    /**
     * Show or update the overlay. Safe to call repeatedly — content
     * updates if already shown, no-ops on permission denial.
     *
     * @param isRunning true while the turn is still working — either a tool
     *        is executing OR the model is streaming text. Drives the rotating
     *        ring and selects between row 2's working and completed variants.
     * @param isToolRunning true only for the first of those two: an actual
     *        tool is executing. [T-android-overlay-streaming-state] The two
     *        were previously collapsed into one boolean at this boundary, so
     *        the capsule could not tell "running shell_execute" from "the
     *        model is talking", and rendered a stale tool for the latter.
     * @param outcome typed outcome of the most recently finished tool.
     *        Only consulted when [isRunning] = false, where it picks
     *        row 1's outcome word and row 2's leading glyph:
     *          - Success → green ✓
     *          - Error / Timeout → red ✗
     *          - Cancelled / Unknown → glyph hidden (we don't have a
     *            confident signal to render); the outcome word still
     *            carries the result in text.
     *        [T-overlay-glyph-typed-outcome] Replaces the previous
     *        [looksLikeFailure] text heuristic which always rendered
     *        green because it scanned the stale "Running: foo" status.
     */
    fun show(
        toolName: String?,
        statusText: String,
        isRunning: Boolean = true,
        outcome: ToolOutcome = ToolOutcome.Unknown,
        replyExcerpt: String? = null,
        targetSessionId: String? = null,
        toolTitle: String? = null,
        isToolRunning: Boolean = isRunning,
        isThinking: Boolean = false,
        // [T-android-overlay-multitask] The focused task's own title and its
        // position among the live tasks. Passed in rather than read from the
        // tracker here so the capsule renders one CONSISTENT snapshot: reading
        // them separately is how a title and a bound session id drifted apart
        // on device (10:58:01 — session B's capsule showing A's tool title).
        sessionTitle: String? = null,
        taskIndex: Int = 0,
        taskTotal: Int = 0,
        // [T-port-sched-overlay-waiting] Set between two fires of a scheduled
        // task (with isRunning = false): the capsule shows the wait.
        waiting: Waiting? = null,
    ) {
        Log.d(
            TAG,
            "show() waiting=${waiting != null} toolName=$toolName toolTitle=${toolTitle?.take(40)} " +
                "status=${statusText.take(40)} " +
                "running=$isRunning toolRunning=$isToolRunning thinking=$isThinking outcome=$outcome " +
                "reply=${replyExcerpt?.take(20)} " +
                "sessionTitle=${sessionTitle?.take(24)} task=$taskIndex/$taskTotal " +
                "session=$targetSessionId perm=${hasOverlayPermission()}",
        )
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            try {
                pendingSessionId = targetSessionId
                if (view == null) {
                    Log.d(TAG, "show() attach() — view was null")
                    attach()
                }
                updateContent(
                    toolName, statusText, isRunning, outcome, replyExcerpt, toolTitle,
                    isToolRunning, isThinking, sessionTitle, taskIndex, taskTotal, waiting,
                )
            } catch (e: Throwable) {
                Log.w(TAG, "show failed: ${e.message}", e)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            try {
                windowManager.removeView(v)
            } catch (e: Throwable) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            // [T-android-overlay-chronometer] stop() before dropping the
            // reference: a started Chronometer holds a posted tick callback,
            // and the capsule is attached/detached many times per session.
            elapsedView?.stop()
            elapsedView = null
            view = null
            labelView = null
            statusView = null
            statusRowView = null
            replyView = null
            replyRowView = null
            replyGlyphView = null
            closeView = null
            layoutParams = null
            isShown = false
        }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            // [T-android-overlay-fixed-half-width] Pin width to half the
            // screen (floored on tiny screens) so the capsule no longer
            // expands/shrinks with content. Previously WRAP_CONTENT,
            // which let the box jitter as labels swapped between
            // states. Text rows inside ellipsize within this envelope —
            // see updateContent maxWidth assignment.
            fixedCapsuleWidthPx(),
            // [T-android-overlay-dimensions-stable] Fixed height for the chosen
            // line count, so the capsule doesn't jitter as content cycles
            // through spinner / completed / reply / error states.
            dpToPx(capsuleHeightDp(lineCount)),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // [T-overlay-position-restore] The remembered position may be
            // partly off-screen, with a negative x or y; only "never
            // dragged" gets the default.
            // [T-overlay-orientation-position] Remembered per orientation.
            val metrics = context.resources.displayMetrics
            val at = OverlayPosition.displayed(
                positionStore,
                defaultPosition(),
                metrics.widthPixels,
                metrics.heightPixels,
                width,
                height,
                systemBarAvoidance(metrics.widthPixels, metrics.heightPixels),
            )
            x = at.x
            y = at.y
        }
        layoutParams = params
        attachTouchListener(container, params)
        windowManager.addView(container, params)
        view = container
        isShown = true
    }

    /**
     * The capsule's fill. Its corner radius is the one thing that depends on
     * [lineCount], so [setLineCount] rebuilds it rather than the whole view.
     *
     * [T-android-overlay-pill-shape] Half the height while the card is short (a
     * true pill), then capped so a 6-line card stays a rounded rectangle
     * instead of becoming an oval.
     */
    private fun capsuleBackground(density: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = minOf(
            capsuleHeightDp(lineCount) / 2f,
            CAPSULE_CORNER_RADIUS_DP,
        ) * density
        setColor(Color.argb(230, 28, 28, 30))
        setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
    }

    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // [T-fa-locale] This window lives outside any Activity, so
            // LocaleWrap.forceLtr does not reach it; keep it unmirrored in
            // right-to-left languages (Persian) like the rest of the app.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            // Right padding is larger than the symmetric 6dp baseline so
            // the trailing X close button clears the pill's rounded corner.
            // [T-android-overlay-no-icon] Left padding used to be wider than
            // the right so the spinner ring was equidistant from the left edge
            // and from the top and bottom; with no ring it matches the right
            // side again, and the text starts at the same inset it ends at.
            setPadding(dpToPx(CAPSULE_PADDING_START_DP), dpToPx(CAPSULE_VERTICAL_PADDING_DP), dpToPx(14), dpToPx(CAPSULE_VERTICAL_PADDING_DP))
            background = capsuleBackground(density)
            elevation = 8f * density
            gravity = Gravity.CENTER_VERTICAL
            // [T-android-overlay-fixed-half-width] The capsule width is
            // now pinned at the WindowManager layer (see attach() →
            // params.width = fixedCapsuleWidthPx), so this LinearLayout
            // measures against the parent's fixed-size envelope rather
            // than its own intrinsic content. Keeping a small
            // minimumWidth as a defensive floor in case a future
            // refactor returns the WM container to WRAP_CONTENT.
            minimumWidth = dpToPx(120)
        }

        // [T-android-overlay-no-icon] The identity circle, its backing plate and
        // the spinner ring that traced them used to be stacked here as the
        // capsule's first child. The user asked for the icon gone, so the whole
        // stack is gone with it and `textCol` is the capsule's only content
        // child besides the trailing X.

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // [T-android-overlay-third-row-clipped] The column is
            // WRAP_CONTENT inside a FIXED-height parent and centred
            // vertically, so anything taller than the capsule overflows
            // SYMMETRICALLY — half a row bleeds off the top and half off the
            // bottom. Clipping to the parent's bounds turns that into a clean
            // truncation at the bottom edge instead of a row sliced through
            // its middle.
            //
            // NOTE on provenance: 38b912a27 added this clip (and raised
            // CAPSULE_HEIGHT_DP to 54) believing symmetric three-row overflow
            // was the cause of the reported "half-cut second line". On-device
            // measurement later disproved that — see the elapsed-Chronometer
            // comment below for the actual root cause; the completion capsule
            // was only ever rendering ONE row, so three-row overflow could not
            // have produced the symptom. The clip is kept anyway because it is
            // a genuine safety net for a case the height budget cannot cover:
            // the budget assumes the system font scale, and a user at 1.3x
            // accessibility scaling blows past any fixed dp value.
            clipToPadding = true
            clipChildren = true
            // weight=1 + width=0 makes this column eat all horizontal
            // space between the logo and the trailing X button. Without
            // this, short labels (e.g. "Done") let the column shrink
            // and the X is dragged inward; weight pins X to the right
            // edge regardless of text length.
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
        }

        // [T-android-overlay-clock-baseline] Row 1 seats the 14sp task title
        // beside the 11sp chip, so it must align them by TEXT BASELINE. A
        // vertical gravity would centre each child's BOX instead, which is not
        // the same thing at different text sizes and measured 2px out on
        // device. LinearLayout aligns by baseline by default, so the correct
        // move is to set no vertical gravity here at all.
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            // [T-android-overlay-enlarged] 12sp -> 14sp: the task title is the
            // line the user reads off-screen, and at 12sp it was the reported
            // "看不清 / 太小" of the capsule.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        labelView = label
        // [T-android-overlay-elapsed-vertical-wrap] weight=1 with width=0 so a
        // long label ellipsizes rather than widening the row. The original
        // reason was to stop the label starving the clock beside it (which
        // drove the clock to zero width and made it wrap vertically); the clock
        // has since moved to row 2, but the ellipsize-don't-widen behaviour is
        // still what this row wants.
        labelRow.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // [T-android-overlay-chronometer] Elapsed clock. Hidden until a run
        // supplies a base.
        // [T-android-overlay-task-total-timer] What it measures is the whole
        // task, not the current step: SessionActivityTracker anchors the run
        // start once per task, never per tool call.
        // [T-android-overlay-multitask] It is ADDED to row 2 further down (the
        // index took row 1's trailing slot), so it now sits under the index
        // rather than beside the title. Constructed here, next to the row-1
        // views, only because the label row's sizing lessons below are the
        // same ones it depends on.
        // SessionActivityTracker anchors currentRunStartedAtMs exactly once per
        // task — only on the idle -> active edge (`if (wasRunIdle)`), never
        // per tool call — so this reading accumulates across every tool call
        // and streaming stretch in the turn. Row 1, beside the assistant's
        // name, is where a task-level total belongs; row 2 describes the
        // current step and would have implied a per-step duration it never
        // measured.
        val elapsed = Chronometer(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            // [T-android-overlay-elapsed-vertical-wrap] A Chronometer is a
            // TextView, and an unconstrained TextView squeezed to zero width
            // does not clip — it WRAPS, one character per line. Measured on
            // device: label 297px + icon 33px already exceed the 328px column,
            // leaving nothing for the clock, which then rendered 0px wide and
            // 115px TALL. That single view caused both reported defects — it
            // was the "dashed vertical line" between the tick and the X, and
            // by inflating labelRow to 115px it pushed the status and reply
            // rows to height 0, which is why the completion capsule showed
            // only its title.
            //
            // maxLines=1 is the actual fix: it makes the view's height one
            // line regardless of available width, so it can never grow the
            // row. singleLine is deprecated; maxLines is the current spelling.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dpToPx(6)
                // [T-android-overlay-clock-baseline] No vertical gravity here:
                // a per-child gravity overrides the row's baseline alignment
                // for that child, which is the offset this fixes. This clock is
                // 11sp against the 12sp status text beside it, so the two do NOT
                // agree by size — leaving gravity off is what keeps the pair on
                // one baseline.
            }
            visibility = View.GONE
        }
        elapsedView = elapsed

        // [T-android-overlay-multitask] Row 1's trailing slot is the task
        // index ("\u2460 of 3"), not the clock. The two columns pair by scope:
        // row 1 is the TASK (its title, which of N it is), row 2 is the
        // current STEP (the tool, how long the task has run). Putting the
        // index beside the title is what makes "which of these am I looking
        // at" answerable without reading both rows.
        //
        // Hidden below two tasks: with a single run "\u2460 of 1" is pure noise
        // and costs the title characters it cannot spare.
        val taskIndex = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            text = ""
            maxLines = 1
            // No ellipsize: this is a handful of digits inside a fixed pill.
            // Truncating "2 of 10" to "2 of…" would be worse than letting the
            // title beside it give up the space, which weight=1 already does.
            // [T-android-overlay-multitask-pill] Outline only — no fill. It
            // echoes the capsule's own edge (1dp hairline, radius tracking half
            // the height) so it reads as a chip drawn ON the capsule; a filled
            // chip sat on top of the body as a second surface and drew more
            // attention than a task counter deserves. The stroke is brighter
            // than the capsule's (70 vs 40 alpha) because it has no fill to
            // separate it from the body behind it.
            val vPad = dpToPx(1)
            val hPad = dpToPx(6)
            setPadding(hPad, vPad, hPad, vPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TASK_PILL_HEIGHT_DP / 2f * density
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(1), Color.argb(70, 255, 255, 255))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dpToPx(6) }
            visibility = View.GONE
        }
        taskIndexView = taskIndex
        labelRow.addView(taskIndex)

        textCol.addView(labelRow)


        // [T-android-overlay-line-count] Second row: the STEP the turn is on.
        // Its content varies by state:
        //
        //   tool running — the model-supplied tool_title, e.g. "Open Baidu
        //                  home page". Replaces the old generic
        //                  "Running: shell_execute", which named the tool's
        //                  internal identifier rather than the work.
        //   streaming    — a localized "generating" line, for the stretch where
        //                  the model is producing text with no tool in flight.
        //   completed    — the step that just finished, still named here; the
        //                  reply moved down to its own row below.
        //
        // The row is text-only. It used to lead with the outcome ✓/✗, but that
        // glyph marks the RESULT, so it now leads the reply row instead — and
        // the tool's own icon is gone with the capsule's identity circle
        // ([T-android-overlay-no-icon]).
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // [T-android-overlay-multitask] The clock is back on this row, so
            // this is a 12sp status text beside an 11sp clock, and an explicit
            // gravity keeps the row's children centred.
            // [T-android-overlay-line-count] Centring rather than
            // baseline-aligning also keeps this row honest when the clock grows
            // a digit mid-run.
            gravity = Gravity.CENTER_VERTICAL
        }

        val status = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            // [T-android-overlay-line-count] Row 2 is the STEP line and stays
            // one line: what the extra lines the user picked are for is the
            // reply, not a second helping of tool title.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        statusView = status
        // weight=1 so a long tool_title ellipsizes instead of pushing the row
        // wider than the fixed capsule — the same lesson as the label row.
        statusRow.addView(
            status,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )

        // [T-android-overlay-multitask] The clock moved here from row 1 so the
        // right-hand edge reads index-over-clock. What it measures is
        // unchanged (the whole task, anchored once per run), but it now sits
        // beside the step it is timing rather than beside the identity.
        //
        // Added AFTER the weight=1 status text so the text ellipsizes and the
        // clock keeps its intrinsic width, which is the same ordering row 1
        // already relies on.
        //
        // [T-android-overlay-multitask-pill] The clock is CENTRED on the chip
        // above it, not right-aligned to it: the chip is the wider of the two
        // (it carries a border and 6dp of side padding), so aligning their
        // right edges would leave the clock visibly off-centre beneath it.
        //
        // Both views sit in a column of the same measured width for that to
        // hold at any content: left to wrap-content they only line up by
        // coincidence of today's strings, and "1:02:14" — an hour-long task —
        // is wider than "2 of 2" and would have pushed the clock back out.
        elapsed.gravity = Gravity.CENTER_HORIZONTAL
        statusRow.addView(elapsed)
        taskIndexView?.gravity = Gravity.CENTER_HORIZONTAL
        rightColumnViews = listOf(taskIndex, elapsed)

        statusRowView = statusRow
        textCol.addView(statusRow)

        // [T-android-overlay-line-count] Row 3+: the assistant's reply, which
        // is the only content long enough to want the extra lines. It gets
        // every line past the title and the step, so a 3-line capsule gives it
        // one and a 6-line capsule gives it four.
        //
        // [T-android-overlay-reply-row] Its leading glyph is the outcome
        // ✓/✗, which used to lead row 2 back when row 2 doubled as the reply
        // line. Reuses StatusGlyphView — the same tick/cross the label row
        // draws — so the two cannot drift apart in shape or stroke weight.
        val replyRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val replyGlyph = StatusGlyphView(context).apply {
            val s = dpToPx(TOOL_ICON_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                rightMargin = dpToPx(4)
                // A View with no text has no baseline for the row to align to,
                // so this one keeps an explicit vertical gravity of its own.
                gravity = Gravity.CENTER_VERTICAL
            }
            visibility = View.GONE
        }
        replyGlyphView = replyGlyph
        replyRow.addView(replyGlyph)

        val reply = TextView(context).apply {
            setTextColor(Color.argb(200, 220, 220, 220))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = ""
            maxLines = (lineCount - 2).coerceAtLeast(1)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        replyView = reply
        // weight=1 for the same reason as the other two rows: the reply is the
        // longest string the capsule carries, and it must wrap inside the
        // envelope rather than push the box.
        replyRow.addView(
            reply,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        replyRowView = replyRow
        textCol.addView(replyRow)

        container.addView(textCol)

        // [T-android-overlay-reply-status-34599] Trailing X button to
        // let the user dismiss the overlay without having to wait for
        // a foreground transition. Sized small so it doesn't dominate
        // the capsule; tap area widened by padding. Lives outside the
        // drag listener (separate setOnClickListener) so tapping it
        // doesn't initiate a drag.
        val closeBtn = CloseGlyphView(context).apply {
            // [T-android-overlay-enlarged] 14dp -> 18dp with the capsule; it
            // also widens the tap area of a button that was fiddly to hit.
            val s = dpToPx(18)
            layoutParams = LinearLayout.LayoutParams(s, s).apply {
                leftMargin = dpToPx(10)
                gravity = Gravity.CENTER_VERTICAL
            }
            setOnClickListener { onUserDismiss() }
            contentDescription = context.getString(R.string.overlay_dismiss)
        }
        closeView = closeBtn
        container.addView(closeBtn)

        return container
    }

    private fun updateContent(
        toolName: String?,
        statusText: String,
        isRunning: Boolean,
        outcome: ToolOutcome,
        replyExcerpt: String?,
        toolTitle: String?,
        isToolRunning: Boolean,
        isThinking: Boolean,
        // [T-android-overlay-multitask] Threaded through from show() so row 1
        // can name the task and number it. Both travel together — see show().
        sessionTitle: String?,
        taskIndex: Int,
        taskTotal: Int,
        waiting: Waiting? = null,
    ) {
        // [T-port-sched-overlay-waiting] Waiting for the next scheduled run:
        // no outcome word, no reply excerpt, row 2 says "Next run · 2/3" and
        // the clock counts down to the run.
        val isWaiting = waiting != null && !isRunning
        // [T-android-overlay-tool-title] The model supplies a per-call
        // tool_title (e.g. "Open Baidu home page", "Take screenshot of current
        // page"); it is what row 2 shows, falling back to a humanized tool name.
        // [T-android-overlay-no-idle] After a tool completes,
        // SessionActivityTracker resets currentToolStatus to "Idle" and
        // toolName to null — flowing that through verbatim would render a noisy
        // "Minis / Idle" capsule that tells the user nothing. When not running
        // and we have neither a toolTitle nor a real toolName, hide the tool
        // row so only the app/outcome line remains.
        val hasToolIdentity = !toolTitle.isNullOrBlank() || toolName != null
        val showStatusRow =
            isRunning || (hasToolIdentity && !statusText.equals("Idle", ignoreCase = true))

        val completionWord: String? = if (!isRunning && !isWaiting) when (outcome) {
            ToolOutcome.Success -> context.getString(R.string.overlay_completion_completed)
            ToolOutcome.Error -> context.getString(R.string.overlay_completion_failed)
            ToolOutcome.Timeout -> context.getString(R.string.overlay_completion_timeout)
            ToolOutcome.Cancelled -> context.getString(R.string.overlay_completion_cancelled)
            ToolOutcome.Unknown -> null
        } else null

        // [T-android-overlay-two-row] Row 1 is now the app/outcome line, not a
        // second copy of the tool title. Before this change row 1 showed
        // tool_title and row 2 showed "Running: <tool_name>"; with row 2
        // reworked to carry the tool icon + tool_title, leaving row 1 as-is
        // would have printed the same sentence twice. So the two rows split by
        // scope: row 1 says WHO is working and how it ended, row 2 says WHAT
        // the current call is doing.
        labelView?.let { lv ->
            // On completion the outcome word stands alone rather than as
            // "Minis — <outcome>": row 1 shares its width with the status tick
            // and the elapsed clock, and the localized words are long enough
            // (zh "执行完成" is four full-width characters) that the combined
            // string ellipsized to an unreadable "Minis — 执...". The app
            // identity is already carried by the logo to the left, so dropping
            // it here loses nothing.
            // [T-android-overlay-multitask] Row 1 names the TASK, not the
            // speaker. With one run the Soul name was a reasonable identity
            // line, but it is identical on every capsule, so with several
            // tasks live it cannot tell them apart — which is the whole point
            // of the row now that the capsule pages between them. The app logo
            // to the left still carries identity.
            //
            // [T-android-overlay-soul-identity] The Soul name survives as the
            // fallback for a session with no title yet (a brand-new chat),
            // keeping the row from ever rendering blank.
            //
            // The completion case is unchanged in spirit but no longer
            // replaces the title: the outcome is shown by the reply row's
            // leading glyph (StatusGlyphView), so a finished task keeps
            // its name instead of collapsing to a bare "执行完成" that says
            // nothing about WHICH task finished — unreadable once more than
            // one is on screen.
            val identityName = SoulStore.cachedMetadata.value.name
                .ifBlank { SoulMetadata.DEFAULT.name }
            val labelText: String? =
                sessionTitle?.takeIf { it.isNotBlank() } ?: completionWord ?: identityName
            if (labelText != null) {
                lv.text = labelText
                lv.visibility = View.VISIBLE
            } else {
                lv.text = ""
                lv.visibility = View.GONE
            }
        }
        // [T-android-overlay-multitask] The task chip on row 1: "2 of 3", or
        // "2/3" where the word does not belong.
        //
        // Shown only from two tasks up: at one it states the obvious and eats
        // characters the title needs, since this row's width is shared with it.
        //
        // Plain digits, not circled ones (\u2460\u2461\u2462). Those stop at 20, force a
        // fallback nobody can see coming, and render from a different font —
        // often a colour emoji on OEM builds — so they sat at the wrong optical
        // weight beside the clock below them. The chip's own outline is what
        // marks this as an indicator, so the glyph does not have to.
        //
        // The string itself is localized, so a locale can drop the word without
        // any of this logic changing.
        taskIndexView?.let { tv ->
            if (taskTotal >= 2 && taskIndex >= 1) {
                tv.text = context.getString(R.string.overlay_task_index, taskIndex, taskTotal)
                tv.visibility = View.VISIBLE
            } else {
                tv.text = ""
                tv.visibility = View.GONE
            }
        }
        // [T-android-overlay-line-count] The rows split by scope, and the reply
        // has its own row now instead of replacing the step line:
        //
        //   row 1  the TASK — its title, and "n of m" when several are live.
        //   row 2  the STEP — the model-supplied tool_title, else the humanized
        //          tool name (friendlyToolTitleFor), else the streaming /
        //          waiting word. Never the raw "Running: <name>".
        //   row 3+ the REPLY — the assistant's last message, which is what the
        //          extra lines the user picked are for.
        //
        // Before this, completion swapped row 2's content for the reply, so the
        // user could not see which step had just finished; with a reply row of
        // its own both read at once.
        val rowToolName = toolName
        val replyText = replyExcerpt?.takeIf { it.isNotBlank() }?.takeUnless { isWaiting }

        // [T-android-overlay-streaming-state] A turn has three working shapes,
        // not two, and the capsule tells them apart:
        //
        //   tool running — a named tool is executing; row 2 shows its title.
        //   streaming    — the model is producing text with no tool in flight;
        //                  row 2 says so (thinking vs generating).
        //   completed    — row 2 keeps the finished step's title, row 3 carries
        //                  the reply.
        //
        // Deciding streaming vs tool needs isToolRunning, not isRunning:
        // isRunning is true for BOTH. And it cannot be inferred from toolName
        // either, because the service passes lastToolName so the finished tool
        // can still be named — during a post-tool streaming stretch that name
        // is still set, which is how a stale tool line used to sit on a capsule
        // that was really just talking.
        val showStreamingVariant = isRunning && !isToolRunning

        val rowTitle: String? = when {
            isWaiting -> waiting!!.statusText
            // [T-android-live-update-content] Distinguish reasoning from
            // visible output, as the iOS Dynamic Island does.
            showStreamingVariant -> context.getString(
                if (isThinking) R.string.overlay_thinking else R.string.overlay_streaming,
            )
            !showStatusRow -> null
            !toolTitle.isNullOrBlank() -> toolTitle
            rowToolName != null -> friendlyToolTitleFor(rowToolName)
            else -> null
        }
        // [T-overlay-glyph-typed-outcome] Drive the glyph from the typed
        // outcome rather than scanning the (stale) status string. Cancelled /
        // Unknown draw nothing rather than render a guess, so a reply under
        // those outcomes shows as text alone rather than with a misleading
        // tick. Row 1 states the outcome in words either way.
        val replyGlyphState: Pair<StatusGlyphView.State, Int>? =
            if (replyText == null || isRunning) null else when (outcome) {
                ToolOutcome.Success -> StatusGlyphView.State.Success to SUCCESS_COLOR
                ToolOutcome.Error, ToolOutcome.Timeout ->
                    StatusGlyphView.State.Error to FAILURE_COLOR
                ToolOutcome.Cancelled, ToolOutcome.Unknown -> null
            }

        val showToolRow = rowTitle != null
        statusRowView?.visibility = if (showToolRow) View.VISIBLE else View.GONE
        statusView?.let { sv ->
            if (showToolRow) {
                sv.text = rowTitle
                sv.visibility = View.VISIBLE
            } else {
                sv.text = ""
                sv.visibility = View.GONE
            }
        }
        val showReplyRow = replyText != null
        replyRowView?.visibility = if (showReplyRow) View.VISIBLE else View.GONE
        replyView?.let { rv ->
            rv.text = replyText ?: ""
            // Re-applied per update because the setting can change between
            // attaches without the capsule being rebuilt.
            rv.maxLines = (lineCount - 2).coerceAtLeast(1)
        }
        replyGlyphView?.let { gv ->
            val st = replyGlyphState
            if (st != null) {
                gv.setState(st.first, st.second)
                gv.visibility = View.VISIBLE
            } else {
                gv.visibility = View.GONE
            }
        }
        // [T-android-overlay-fixed-half-width] maxWidth tracks the same fixed
        // envelope as the WM container, minus the leading pad and the trailing
        // close button, so text wraps or ellipsizes inside the box instead of
        // pushing it open.
        // [T-android-overlay-no-icon] The logo's 34dp + 6dp margin dropped out
        // of the subtraction when the icon did, which is where the reply row's
        // extra characters per line come from.
        val maxW = (fixedCapsuleWidthPx() - dpToPx(CAPSULE_PADDING_START_DP + CAPSULE_NON_TEXT_DP)).coerceAtLeast(dpToPx(80))
        labelView?.maxWidth = maxW
        statusView?.maxWidth = maxW
        replyView?.maxWidth = maxW

        // [T-android-overlay-reply-status-34599] The X close button is
        // useful only once the user has something to dismiss — while a
        // tool is mid-stream the overlay self-tears-down when the tool
        // finishes (no completion state yet to linger on), and the X
        // shouldn't double as a "cancel the run" affordance.
        closeView?.visibility = if (isRunning) View.GONE else View.VISIBLE

        // [T-android-overlay-chronometer] Drive the elapsed clock.
        //
        // Clock domain, and why it differs from the notification: Chronometer
        // interprets `base` as SystemClock.elapsedRealtime(), which is EXACTLY
        // what SessionActivityTracker stamps into currentRunStartedAtMs. So no
        // rebasing here — the opposite of AgentForegroundService, where
        // setWhen() is wall-clock and the same anchor MUST be converted. The
        // asymmetry is deliberate, not an oversight in one of the two.
        //
        // While running: anchor and start, so the capsule keeps counting
        // through a long tool call that pushes no state changes. When stopped:
        // stop() freezes the reading at the run's real duration rather than
        // blanking it, so the completion capsule still answers "how long did
        // that take". Falls back to hiding when there is no anchor (presence
        // only, nothing ever ran) rather than showing a bogus 00:00.
        val elapsed = elapsedView
        if (elapsed != null && isWaiting) {
            // [T-port-sched-overlay-waiting] Count DOWN to the next run (the
            // Chronometer ticks itself; base is on the elapsedRealtime clock),
            // and stop at zero rather than run into negative time if the fire
            // is late. No clock for an on-completion wait.
            val next = waiting!!.nextRunElapsedMs
            if (next != null && next > SystemClock.elapsedRealtime()) {
                elapsed.isCountDown = true
                if (elapsed.base != next) elapsed.base = next
                elapsed.setOnChronometerTickListener { c ->
                    if (c.isCountDown && c.base <= SystemClock.elapsedRealtime()) c.stop()
                }
                elapsed.visibility = View.VISIBLE
                elapsed.start()
            } else {
                elapsed.stop()
                elapsed.visibility = View.GONE
            }
        } else if (elapsed != null) {
            // Counting up again (running / completed): undo the waiting mode.
            if (elapsed.isCountDown) {
                elapsed.isCountDown = false
                elapsed.onChronometerTickListener = null
            }
            val runStart = SessionActivityTracker.currentRunStartedAtMs.value
            if (runStart != null) {
                if (isRunning) {
                    // Re-anchor only when it actually moved; assigning `base`
                    // unconditionally would restart the visible count on every
                    // unrelated state push.
                    if (elapsed.base != runStart) {
                        elapsed.base = runStart
                    }
                    elapsed.visibility = View.VISIBLE
                    elapsed.start()
                } else {
                    // [T-android-overlay-task-total-timer] Completed: freeze the
                    // reading and KEEP it. Back on row 1 the clock no longer
                    // competes with the reply excerpt for width (which is why
                    // it was hidden while it lived on row 2), and a task total
                    // is exactly the kind of summary that stays useful after
                    // the fact — "that took 00:35" is the one number the user
                    // could not have watched accumulate while backgrounded.
                    elapsed.stop()
                    // [T-sched-overlay-negative-clock] Back from the waiting
                    // countdown without a new run (the task was deleted or
                    // disabled mid-wait), `base` is still the next run's time,
                    // in the future: counting up from it read "-00:42". That
                    // run's duration is not known here any more, so no clock.
                    elapsed.visibility =
                        if (OverlayClock.completedShowsClock(elapsed.base, SystemClock.elapsedRealtime())) View.VISIBLE else View.GONE
                }
            } else {
                elapsed.stop()
                elapsed.visibility = View.GONE
            }
        }

        equaliseRightColumn()
    }

    /**
     * [T-android-overlay-multitask-pill] Gives the chip and the clock a common
     * width so the clock is centred UNDER the chip rather than beside it.
     *
     * Both are WRAP_CONTENT by nature and their strings differ in width ("2 of
     * 2" against "00:15", and an hour-long task reaches "1:02:14"), so left
     * alone they line up only by coincidence of the current content. Measuring
     * both and widening the narrower to match pins the relationship at any
     * content, including the elapsed clock growing a digit mid-run.
     *
     * Runs after every content update because the clock's own width changes as
     * it ticks. Costs two measure passes on two small text views; the capsule
     * already re-lays-out on each of these updates.
     */
    private fun equaliseRightColumn() {
        val views = rightColumnViews.filter { it.visibility == View.VISIBLE }
        // Nothing to align against when the chip is hidden (a single task), and
        // the clock should keep its natural width in that case.
        if (views.size < 2) {
            rightColumnViews.forEach { v ->
                if (v.layoutParams?.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                    v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                        width = LinearLayout.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            return
        }
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            // Measure at natural width, not the width a previous pass forced.
            if (lp.width != LinearLayout.LayoutParams.WRAP_CONTENT) {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                v.layoutParams = lp
            }
            v.measure(unspec, unspec)
            if (v.measuredWidth > widest) widest = v.measuredWidth
        }
        if (widest <= 0) return
        for (v in views) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            if (lp.width != widest) {
                lp.width = widest
                v.layoutParams = lp
            }
        }
    }

    private fun attachTouchListener(target: View, params: WindowManager.LayoutParams) {
        val slopPx = (DRAG_SLOP_DP * context.resources.displayMetrics.density).toInt()
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var dragging = false
        target.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > slopPx || abs(dy) > slopPx)) dragging = true
                    if (dragging) {
                        params.x = startParamX + dx
                        params.y = startParamY + dy
                        try {
                            windowManager.updateViewLayout(target, params)
                        } catch (e: Throwable) {
                            Log.w(TAG, "updateViewLayout failed: ${e.message}")
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // [T-overlay-orientation-position] Saved as dragged,
                        // unclamped, for the orientation it was dragged in.
                        val dm = context.resources.displayMetrics
                        OverlayPosition.dragEnded(positionStore, dm.widthPixels, dm.heightPixels, OverlayPosition.Point(params.x, params.y))
                        // [T-overlay-drag-snap] Shown where attach() would show
                        // it: the clamp (whole capsule below the status bar /
                        // cutout, off the gesture bar, at most half past a side)
                        // only ran on attach and on a configuration change, so a
                        // capsule dropped under the status bar or almost fully
                        // off screen stayed there, with nothing left to grab.
                        val at = OverlayPosition.displayed(
                            positionStore,
                            defaultPosition(),
                            dm.widthPixels,
                            dm.heightPixels,
                            fixedCapsuleWidthPx(),
                            dpToPx(capsuleHeightDp(lineCount)),
                            systemBarAvoidance(dm.widthPixels, dm.heightPixels),
                        )
                        if (at.x != params.x || at.y != params.y) {
                            params.x = at.x
                            params.y = at.y
                            try {
                                windowManager.updateViewLayout(target, params)
                            } catch (e: Throwable) {
                                Log.w(TAG, "drag snap relayout failed: ${e.message}")
                            }
                        }
                    } else {
                        onTap()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun onTap() {
        try {
            val sid = pendingSessionId
            val launchIntent = Intent(
                context,
                Class.forName("com.openminis.app.MainActivity"),
            ).apply {
                // [T-android-overlay-reply-status-34599] When we have a
                // tracked session, route the tap through the existing
                // `minis://session/<id>` deep-link so MainActivity's
                // DeepLinkHandler navigates to that chat. When sid is
                // null (e.g. completion observed before any session was
                // pushed), fall back to plain "bring to front".
                if (!sid.isNullOrBlank()) {
                    data = Uri.parse("minis://session/$sid")
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(launchIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "tap-to-foreground failed: ${e.message}")
        }
        onUserDismiss()
    }

    /**
     * [T-android-overlay-reply-status-34599] Shared dismissal path used
     * by both the X button and the tap-to-open-chat tap. Tears down
     * the window immediately for snappy feedback, then nudges the
     * service-level observer to clear lingered completion state so
     * `shouldShow` flips to false on the next emission and we don't
     * accidentally re-show during a future tool turn that has the
     * same outcome.
     */
    private fun onUserDismiss() {
        try { onDismissByUser?.invoke() } catch (_: Throwable) {}
        hide()
    }

    /**
     * [T-android-overlay-fixed-half-width] Resolve the fixed capsule
     * width: half of the current display width, floored at
     * CAPSULE_WIDTH_FLOOR_DP. Read live each time so a config change
     * (orientation, multi-window resize) picks up the new metrics on
     * the next attach. Existing attached views keep their original
     * width — orientation change recreates the overlay anyway.
     */
    private fun fixedCapsuleWidthPx(): Int {
        val dm = context.resources.displayMetrics
        // [T-android-overlay-landscape-width-rotation-drift] Cap the width at
        // min(screenWidth*0.70, 400dp) so landscape no longer stretches the
        // pill across the screen, then floor for tiny screens.
        val cap = minOf(
            (dm.widthPixels * CAPSULE_WIDTH_CAP_FRACTION).toInt(),
            dpToPx(CAPSULE_WIDTH_CAP_DP),
        )
        return (dm.widthPixels * CAPSULE_WIDTH_FRACTION).toInt()
            .coerceAtMost(cap)
            .coerceAtLeast(dpToPx(CAPSULE_WIDTH_FLOOR_DP))
    }

    /**
     * [T-android-overlay-landscape-width-rotation-drift] Re-clamp the
     * attached capsule into the current screen bounds after a
     * configuration change (orientation flip, multi-window resize). The
     * overlay is a WindowManager view — it is NOT recreated on rotation —
     * so a position saved while in landscape can land off-screen (or fully
     * out of view) once the device returns to portrait. Recompute the
     * width for the new metrics, clamp x/y so at least half of the capsule
     * stays on-screen, and push the new layout.
     *
     * [T-overlay-position-restore] Same rule as attach()
     * ([OverlayPosition.clamp]). It used to clamp to fully on-screen, so any
     * configuration change - a night-mode switch, not only a rotation -
     * dragged a capsule parked partly past an edge back inside and saved
     * that over the user's position.
     *
     * [T-overlay-orientation-position] Nothing is saved here any more. The
     * clamp used to be written back to the one stored position, so a trip
     * through landscape moved the portrait position for good (Alice Q2: 1796
     * came back as 946). Now this shows the new orientation's own position,
     * clamped for display only; only a drag writes.
     *
     * No-op when nothing is attached. Owned by [AgentForegroundService],
     * which forwards its own onConfigurationChanged here.
     */
    fun onConfigurationChanged() {
        mainHandler.post {
            val v = view ?: return@post
            val params = layoutParams ?: return@post
            val dm = context.resources.displayMetrics
            val width = fixedCapsuleWidthPx()
            val height = dpToPx(capsuleHeightDp(lineCount))
            val at = OverlayPosition.displayed(
                positionStore,
                defaultPosition(),
                dm.widthPixels,
                dm.heightPixels,
                width,
                height,
                systemBarAvoidance(dm.widthPixels, dm.heightPixels),
            )
            if (params.width == width && params.height == height &&
                params.x == at.x && params.y == at.y
            ) return@post
            params.width = width
            params.height = height
            params.x = at.x
            params.y = at.y
            try {
                windowManager.updateViewLayout(v, params)
            } catch (e: Throwable) {
                Log.w(TAG, "onConfigurationChanged relayout failed: ${e.message}")
            }
        }
    }

    /**
     * [T-bg-overlay-polish] First-paint default: bottom-left, 10 dp from the
     * left edge and 10 dp above the nav-bar region. We don't have an accurate
     * nav-bar height before attach, so use displayMetrics.heightPixels and
     * trust FLAG_LAYOUT_NO_LIMITS to keep us on-screen.
     */
    private fun defaultPosition(): OverlayPosition.Point {
        val metrics = context.resources.displayMetrics
        val padding = dpToPx(EDGE_PADDING_DP)
        val approxOverlayHeight = dpToPx(capsuleHeightDp(lineCount))
        val approxNavBar = dpToPx(48)
        return OverlayPosition.Point(
            padding,
            metrics.heightPixels - approxOverlayHeight - approxNavBar - padding,
        )
    }

    /**
     * [T-overlay-orientation-position] The navigation bar / home gesture
     * inset the capsule keeps clear of in landscape ([OverlayPosition.avoidFor]).
     * Read from the display's current window metrics (API 30+). Older
     * releases have no reliable inset outside an Activity, so they keep the
     * plain half-on-screen rule on the left, right and bottom.
     *
     * [T-overlay-no-top-hide] The top ([statusBarTop]) applies in both
     * orientations and on every API level.
     */
    private fun systemBarAvoidance(screenW: Int, screenH: Int): OverlayPosition.Avoid {
        val orientation = OverlayPosition.Orientation.of(screenW, screenH)
        val top = statusBarTop()
        if (orientation != OverlayPosition.Orientation.LANDSCAPE) return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        val insets = runCatching {
            windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.navigationBars() or
                    android.view.WindowInsets.Type.mandatorySystemGestures(),
            )
        }.onFailure { Log.w(TAG, "system bar insets unavailable: ${it.message}") }
            .getOrNull() ?: return OverlayPosition.avoidFor(orientation, 0, 0, 0, top)
        return OverlayPosition.avoidFor(orientation, insets.left, insets.right, insets.bottom, top)
    }

    /**
     * [T-overlay-no-top-hide] How far below the window origin the capsule's
     * top must stay: the status bar, or the display cutout when that is
     * taller. Errs on the safe side - where the window origin already sits
     * below a visible status bar, this leaves an extra gap of about one status
     * bar instead of risking the capsule under the bar, the cutout or the
     * pull-down gesture strip.
     *
     * API 30+: the display's window metrics, ignoring visibility, so a status
     * bar hidden by a fullscreen app still counts (its pull-down strip is
     * still there). API 26-29 (and if that read fails): the framework's
     * status_bar_height dimen, as there is no inset outside an Activity. 0
     * when neither is available, which still keeps the capsule below the top
     * edge.
     */
    private fun statusBarTop(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout(),
                ).top
            }.onFailure { Log.w(TAG, "status bar inset unavailable: ${it.message}") }
                .getOrNull()?.let { return it.coerceAtLeast(0) }
        }
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id == 0) return 0
        return runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0).coerceAtLeast(0)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private class CloseGlyphView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(220, 200, 200, 200)
        }
        private val path = Path()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.14f).coerceAtLeast(2f)
            path.reset()
            path.moveTo(w * 0.25f, h * 0.25f)
            path.lineTo(w * 0.75f, h * 0.75f)
            path.moveTo(w * 0.75f, h * 0.25f)
            path.lineTo(w * 0.25f, h * 0.75f)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * Lightweight ✓ / ✗ glyph rendered via Path so we don't need to
     * ship a new vector drawable resource. Color is provided by
     * [setState] so callers can swap success/failure tinting.
     */
    private class StatusGlyphView(context: Context) : View(context) {

        enum class State { Success, Error }

        private var state: State = State.Success
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#4CAF50")
        }
        private val path = Path()

        fun setState(newState: State, color: Int) {
            state = newState
            paint.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            paint.strokeWidth = (w * 0.16f).coerceAtLeast(2f)
            path.reset()
            when (state) {
                State.Success -> {
                    path.moveTo(w * 0.18f, h * 0.52f)
                    path.lineTo(w * 0.42f, h * 0.74f)
                    path.lineTo(w * 0.82f, h * 0.30f)
                }
                State.Error -> {
                    path.moveTo(w * 0.22f, h * 0.22f)
                    path.lineTo(w * 0.78f, h * 0.78f)
                    path.moveTo(w * 0.78f, h * 0.22f)
                    path.lineTo(w * 0.22f, h * 0.78f)
                }
            }
            canvas.drawPath(path, paint)
        }
    }
}

/** [T-sched-overlay-negative-clock] Pure rules for the capsule's clock. */
internal object OverlayClock {
    /** A stopped count-up clock is only meaningful from an anchor in the past. */
    fun completedShowsClock(base: Long, nowElapsedMs: Long): Boolean = base <= nowElapsedMs
}
