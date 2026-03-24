package helium314.keyboard.ai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * AiTonePanel — horizontal scrollable tone picker.
 * Uses the keyboard's Colors system — works on any theme (light/dark/custom).
 */
class AiTonePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {

    companion object {
        private const val TAG = "AiTonePanel"
        val TONES = listOf(
            "Smart", "Funny", "Flirty", "Professional",
            "Dating", "Savage", "Apology", "Friendly"
        )
    }

    var onToneSelected: ((String) -> Unit)? = null

    private var selectedTone: String? = null
    private val toneButtons = mutableListOf<TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        setupPanel()
    }

    private fun setupPanel() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        TONES.forEach { tone ->
            val btn = createToneButton(tone)
            toneButtons.add(btn)
            container.addView(btn)
        }
        addView(container)
    }

    private fun createToneButton(tone: String): TextView {
        val colors = Settings.getValues().mColors
        return TextView(context).apply {
            text = tone
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(colors.get(ColorType.KEY_TEXT))
            setPadding(dp(14), dp(7), dp(14), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            background = pillBg(selected = false)
            setOnClickListener { handleToneClick(tone, this) }
        }
    }

    private fun handleToneClick(tone: String, clicked: TextView) {
        val colors = Settings.getValues().mColors
        // Deselect all
        toneButtons.forEach { btn ->
            btn.background = pillBg(selected = false)
            btn.setTextColor(colors.get(ColorType.KEY_TEXT))
        }
        // Select clicked
        clicked.background = pillBg(selected = true)
        clicked.setTextColor(colors.get(ColorType.ACTION_KEY_ICON))  // white on accent
        selectedTone = tone
        Log.d(TAG, "🎭 Tone selected: $tone")
        showLoading(true)
        onToneSelected?.invoke(tone.lowercase())
    }

    /** Dim buttons during API call */
    fun showLoading(loading: Boolean) {
        toneButtons.forEach { btn ->
            btn.isEnabled = !loading
            btn.alpha = if (loading) 0.45f else 1.0f
        }
    }

    /** Reset to unselected, all enabled */
    fun reset() {
        selectedTone = null
        val colors = Settings.getValues().mColors
        toneButtons.forEach { btn ->
            btn.background = pillBg(selected = false)
            btn.setTextColor(colors.get(ColorType.KEY_TEXT))
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }

    private fun pillBg(selected: Boolean): GradientDrawable {
        val colors = Settings.getValues().mColors
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            if (selected) {
                setColor(colors.get(ColorType.ACTION_KEY_BACKGROUND))
                setStroke(0, 0)
            } else {
                setColor(colors.get(ColorType.KEY_BACKGROUND))
                setStroke(1, (colors.get(ColorType.KEY_TEXT) and 0x00FFFFFF) or 0x33000000)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
