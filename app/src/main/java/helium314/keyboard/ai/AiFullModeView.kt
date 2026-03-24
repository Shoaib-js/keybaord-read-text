/*
 * AiFullModeView.kt — Minimal AI Suggestion Panel
 *
 * Layout (always visible when panel is open):
 *   [ Privacy text row (always) ]
 *   [ Tone picker   ] ← TONE state
 *   [ Loading row   ] ← LOADING state
 *   [ Suggestion rows ] ← RESULT state
 *   [ Empty message ] ← EMPTY state
 *
 * Privacy text is ALWAYS shown (TONE/LOADING/RESULT/EMPTY).
 * No separate error UI for "wrong screen" — use EMPTY state instead.
 */
package helium314.keyboard.ai

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Layout
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

class AiFullModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : LinearLayout(context, attrs, defStyle) {

    // ── Java-callable callbacks ────────────────────────────────────────────
    var onToneSelected: ((String) -> Unit)? = null
    var onSuggestionTapped: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    fun setOnToneSelected(cb: java.util.function.Consumer<String>) { onToneSelected = { cb.accept(it) } }
    fun setOnSuggestionTapped(cb: java.util.function.Consumer<String>) { onSuggestionTapped = { cb.accept(it) } }
    fun setOnClose(cb: Runnable) { onClose = { cb.run() } }
    // Legacy no-op kept so LatinIME doesn't crash
    fun setOnRetry(cb: Runnable) {}

    enum class State { IDLE, TONE, LOADING, RESULT, EMPTY }
    private var currentState = State.IDLE

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var toneRow: LinearLayout
    private lateinit var tonePanel: AiTonePanel
    private lateinit var loadingRow: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var contentRows: LinearLayout   // holds suggestion rows OR empty message
    private lateinit var emptyText: TextView

    private var pulseAnimator: ValueAnimator? = null

    companion object { private const val TAG = "AiFullModeView" }

    // ── Nav bar inset ──────────────────────────────────────────────────────
    private var navBarHeight = 0
    fun getNavBarHeight() = navBarHeight

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        if (bottom != navBarHeight) {
            navBarHeight = bottom
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
        }
        return insets
    }

    init {
        orientation = VERTICAL
        visibility = GONE
        buildUI()
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUILD UI
    // ══════════════════════════════════════════════════════════════════════
    private fun buildUI() {
        val colors  = Settings.getValues().mColors
        val bg      = colors.get(ColorType.STRIP_BACKGROUND)
        val keyText = colors.get(ColorType.KEY_TEXT)
        val accent  = colors.get(ColorType.ACTION_KEY_BACKGROUND)

        setBackgroundColor(bg)

        // ── Top divider ────────────────────────────────────────────────────
        addView(android.view.View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor((keyText and 0x00FFFFFF) or 0x22000000)
        })

        // ── PRIVACY ROW (always visible when panel is open) ────────────────
        // Privacy text LEFT, close button RIGHT
        val privacyRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(14), dp(5), dp(4), dp(3))
        }
        val privacyText = TextView(context).apply {
            text = "We analyze only visible messages to generate replies"
            textSize = 10f
            alpha = 0.5f
            setTextColor(keyText)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_BALANCED
            }
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = xBtn(keyText) { onClose?.invoke() }
        privacyRow.addView(privacyText)
        privacyRow.addView(closeBtn)
        addView(privacyRow)

        // ── TONE ROW ───────────────────────────────────────────────────────
        toneRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        tonePanel = AiTonePanel(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            onToneSelected = { tone -> this@AiFullModeView.onToneSelected?.invoke(tone) }
        }
        toneRow.addView(tonePanel)
        addView(toneRow)

        // ── LOADING ROW ────────────────────────────────────────────────────
        loadingRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(38))
            visibility = GONE
        }
        loadingRow.addView(ProgressBar(context).apply {
            layoutParams = LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(8) }
            isIndeterminate = true
        })
        loadingText = TextView(context).apply {
            text = "Generating…"
            textSize = 12f
            setTextColor(keyText)
            alpha = 0.65f
        }
        loadingRow.addView(loadingText)
        addView(loadingRow)

        // ── CONTENT ROWS (suggestions OR empty message) ────────────────────
        contentRows = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            setPadding(dp(8), dp(2), dp(8), dp(6))
            visibility = GONE
        }
        // Empty state text (shown when no messages found)
        emptyText = TextView(context).apply {
            text = "Open a chat with messages to get AI replies"
            textSize = 13f
            setTextColor(keyText)
            alpha = 0.55f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            visibility = GONE
        }
        contentRows.addView(emptyText)
        addView(contentRows)
    }

    // ══════════════════════════════════════════════════════════════════════
    // STATE TRANSITIONS
    // ══════════════════════════════════════════════════════════════════════

    /** TONE: show tone picker, privacy text always visible */
    fun showTone() {
        visibility = VISIBLE
        currentState = State.TONE
        toneRow.isVisible = true
        tonePanel.reset()
        loadingRow.isVisible = false
        contentRows.isVisible = false
        stopPulse()
        Log.d(TAG, "→ TONE")
    }

    /** LOADING: hide tone, show spinner, privacy text stays */
    fun showLoading() {
        currentState = State.LOADING
        toneRow.isVisible = false
        loadingRow.isVisible = true
        contentRows.isVisible = false
        startPulse()
        Log.d(TAG, "→ LOADING")
    }

    /** RESULT: hide tone+spinner, show suggestion rows, privacy text stays */
    fun showResult(suggestions: List<String>) {
        currentState = State.RESULT
        stopPulse()
        toneRow.isVisible = false
        loadingRow.isVisible = false
        emptyText.isVisible = false
        buildSuggestionRows(suggestions)
        contentRows.isVisible = true
        tonePanel.reset()
        Log.d(TAG, "→ RESULT (${suggestions.size})")
    }

    /**
     * EMPTY: no messages found — do NOT call API, show helpful message.
     * Privacy text stays visible. No error color, no retry button.
     */
    fun showEmpty() {
        currentState = State.EMPTY
        stopPulse()
        toneRow.isVisible = false
        loadingRow.isVisible = false
        // Remove any old suggestion rows, show only empty text
        removeOldSuggestionRows()
        emptyText.isVisible = true
        contentRows.isVisible = true
        tonePanel.reset()
        tonePanel.showLoading(false)
        Log.d(TAG, "→ EMPTY")
    }

    /** Legacy error — route to empty or show in loading area */
    fun showError(message: String) {
        // If it's a "wrong screen" error, show empty state (cleaner UX)
        if (message.contains("CONVERSATION", ignoreCase = true) ||
            message.contains("chat", ignoreCase = true) ||
            message.contains("Open a", ignoreCase = true)) {
            showEmpty()
        } else {
            // Generic API error — show in loading area briefly
            currentState = State.EMPTY
            stopPulse()
            toneRow.isVisible = false
            loadingRow.isVisible = false
            emptyText.text = message.substringBefore(".").take(55)
            emptyText.isVisible = true
            contentRows.isVisible = true
            tonePanel.reset()
            tonePanel.showLoading(false)
            Log.d(TAG, "→ ERROR→EMPTY: $message")
        }
    }

    fun hide() {
        stopPulse()
        currentState = State.IDLE
        visibility = GONE
        tonePanel.reset()
        removeOldSuggestionRows()
        Log.d(TAG, "→ IDLE (GONE)")
    }

    fun isShowing() = visibility == VISIBLE
    fun getState()  = currentState

    // ══════════════════════════════════════════════════════════════════════
    // SUGGESTION ROWS
    // ══════════════════════════════════════════════════════════════════════

    private fun removeOldSuggestionRows() {
        // Remove all children except emptyText (first child)
        while (contentRows.childCount > 1) {
            contentRows.removeViewAt(contentRows.childCount - 1)
        }
    }

    private fun buildSuggestionRows(suggestions: List<String>) {
        removeOldSuggestionRows()
        val colors  = Settings.getValues().mColors
        val bgColor = colors.get(ColorType.KEY_BACKGROUND)
        val txtClr  = colors.get(ColorType.KEY_TEXT)
        val accent  = colors.get(ColorType.ACTION_KEY_BACKGROUND)

        suggestions.forEach { text ->
            val rowBg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(bgColor)
                setStroke(1, (accent and 0x00FFFFFF) or 0x28000000)
            }
            contentRows.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(txtClr)
                background = rowBg
                setPadding(dp(14), dp(9), dp(14), dp(9))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = dp(5) }
                isClickable = true
                isFocusable  = true
                setOnClickListener {
                    alpha = 0.4f
                    postDelayed({ alpha = 1f }, 80)
                    onSuggestionTapped?.invoke(text)
                }
            })
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // PULSE ANIMATION
    // ══════════════════════════════════════════════════════════════════════

    private fun startPulse() {
        stopPulse()
        pulseAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 700
            repeatMode  = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (::loadingText.isInitialized) loadingText.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (::loadingText.isInitialized) loadingText.alpha = 0.65f
    }

    private fun xBtn(textColor: Int, onClick: () -> Unit) = TextView(context).apply {
        text = "✕"
        textSize = 15f
        setTextColor(textColor)
        alpha = 0.55f
        gravity = Gravity.CENTER
        layoutParams = LayoutParams(dp(36), dp(36))
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
