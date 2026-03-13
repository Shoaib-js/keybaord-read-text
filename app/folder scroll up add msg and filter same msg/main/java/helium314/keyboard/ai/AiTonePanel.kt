// ══════════════════════════════════════════════════════════════════
// FILE LOCATION:
//   app/src/main/java/helium314/keyboard/ai/AiTonePanel.kt
//
// ACTION: NEW FILE — Create this file (does not exist yet).
// ══════════════════════════════════════════════════════════════════

package helium314.keyboard.ai

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

/**
 * AiTonePanel
 *
 * Keyboard ke andar horizontal scrollable tone selection panel.
 * Jab user AI button press kare, ye panel show hota hai.
 * User tone select kare toh onToneSelected callback trigger hota hai.
 *
 * Tones:
 *   Smart | Funny | Flirty | Professional | Dating | Savage | Apology | Friendly
 *
 * USAGE in SuggestionStripView ya keyboard layout:
 *   val tonePanel = AiTonePanel(context)
 *   tonePanel.onToneSelected = { tone ->
 *       aiHelper.requestSuggestions(tone)
 *       tonePanel.visibility = View.GONE
 *   }
 */
class AiTonePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    companion object {
        private const val TAG = "AiTonePanel"

        val TONES = listOf(
            "Smart",
            "Funny",
            "Flirty",
            "Professional",
            "Dating",
            "Savage",
            "Apology",
            "Friendly"
        )
    }

    // ── Callback — keyboard ye implement karega ──
    var onToneSelected: ((String) -> Unit)? = null

    // ── Currently selected tone highlight ke liye ──
    private var selectedTone: String? = null
    private val toneButtons = mutableListOf<TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        setupPanel()
    }

    // ════════════════════════════════════════
    // SETUP
    // ════════════════════════════════════════

    private fun setupPanel() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
        }

        // Loading indicator (API call ke waqt dikh sake)
        val loadingText = TextView(context).apply {
            id = View.generateViewId()
            text = "⏳ Generating..."
            textSize = 13f
            setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
            visibility = View.GONE
            tag = "loading_indicator"
        }

        TONES.forEach { tone ->
            val btn = createToneButton(tone)
            toneButtons.add(btn)
            container.addView(btn)
        }

        container.addView(loadingText)
        addView(container)

        Log.d(TAG, "AiTonePanel created with ${TONES.size} tones")
    }

    private fun createToneButton(tone: String): TextView {
        return TextView(context).apply {
            text     = tone
            textSize = 13f
            gravity  = Gravity.CENTER

            val horizontalPad = dpToPx(14)
            val verticalPad   = dpToPx(7)
            setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)

            // Margin between buttons
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(8)
            }
            layoutParams = params

            // Default background — pill shape
            background = createPillBackground(isSelected = false)

            setOnClickListener {
                handleToneClick(tone, this)
            }
        }
    }

    // ════════════════════════════════════════
    // CLICK HANDLER
    // ════════════════════════════════════════

    private fun handleToneClick(tone: String, clickedButton: TextView) {
        // Previous selection clear karo
        toneButtons.forEach { btn ->
            btn.background = createPillBackground(isSelected = false)
            btn.setTextColor(Color.parseColor("#333333"))
        }

        // New selection highlight karo
        clickedButton.background = createPillBackground(isSelected = true)
        clickedButton.setTextColor(Color.WHITE)

        selectedTone = tone
        Log.d(TAG, "🎭 Tone selected: $tone")

        // Show loading
        showLoading(true)

        // Callback trigger karo
        onToneSelected?.invoke(tone.lowercase())
    }

    // ════════════════════════════════════════
    // LOADING STATE
    // ════════════════════════════════════════

    fun showLoading(loading: Boolean) {
        val loadingView = findViewWithTag<TextView>("loading_indicator")
        loadingView?.visibility = if (loading) View.VISIBLE else View.GONE

        // Buttons disable/enable karo
        toneButtons.forEach { btn ->
            btn.isEnabled = !loading
            btn.alpha     = if (loading) 0.5f else 1.0f
        }
    }

    // ════════════════════════════════════════
    // RESET
    // ════════════════════════════════════════

    fun reset() {
        selectedTone = null
        showLoading(false)
        toneButtons.forEach { btn ->
            btn.background = createPillBackground(isSelected = false)
            btn.setTextColor(Color.parseColor("#333333"))
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }

    // ════════════════════════════════════════
    // BACKGROUND HELPER
    // ════════════════════════════════════════

    private fun createPillBackground(isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(20).toFloat()
            if (isSelected) {
                setColor(Color.parseColor("#007AFF"))   // Blue when selected
                setStroke(0, Color.TRANSPARENT)
            } else {
                setColor(Color.parseColor("#F0F0F0"))   // Light grey default
                setStroke(1, Color.parseColor("#CCCCCC"))
            }
        }
    }

    // ════════════════════════════════════════
    // UTILITY
    // ════════════════════════════════════════

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
