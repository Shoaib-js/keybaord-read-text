package helium314.keyboard.accessibility.extractors.instagram

// ══════════════════════════════════════════════════════════════════════════════
// IgRow — typed row captured from message_list in Stage 1.
//
// Each direct child of Instagram's message_list RecyclerView becomes one
// of these variants. InstagramNodeParser.classify() produces them;
// InstagramExtractor.extract() consumes them.
//
// Property naming matches InstagramExtractor.kt exactly:
//   Timestamp.normalizedIso  — already ISO-8601, no further parsing needed
//   Message.sender/text      — "me" or "other" + message content
//   XmaMessage.sender/text   — same, flagged as rich/bot content
//   Skip                     — discard silently
// ══════════════════════════════════════════════════════════════════════════════

internal sealed class IgRow {

    /**
     * Standalone timestamp TextView (null viewId, no children).
     * e.g. "Today 11:35 am" → already normalized to "2026-03-18T11:35:00"
     */
    data class Timestamp(val normalizedIso: String) : IgRow()

    /**
     * Plain text message — direct_text_message_text_view found inside
     * a message_content FrameLayout.
     * sender = "me"    when no sender_avatar child exists
     * sender = "other" when sender_avatar child is present
     */
    data class Message(val sender: String, val text: String) : IgRow()

    /**
     * Rich/XMA message — generic bot DM or shared profile/reel.
     * Text is pre-formatted: "[Bot] caption" or "[Profile] username".
     * sender detection follows the same avatar-presence rule as Message.
     */
    data class XmaMessage(val sender: String, val text: String) : IgRow()

    /**
     * Any row that should be silently discarded:
     *   — profile header block (LinearLayout with user_avatar)
     *   — seen-state row (LinearLayout with seen_state_text)
     *   — empty FrameLayout spacers
     *   — "See N updates" Button nodes
     *   — message_composer_bar
     */
    object Skip : IgRow()
}
