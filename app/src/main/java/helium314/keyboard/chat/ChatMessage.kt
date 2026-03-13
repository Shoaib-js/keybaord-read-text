package helium314.keyboard.chat

/**
 * Immutable data model for a single chat message.
 *
 * [eventId] uses System.nanoTime() so it is always unique.
 * Two identical texts sent at different times get different IDs → both stored.
 * This satisfies constraint #2 (duplicates allowed).
 *
 * [isDebounceDuplicateOf] suppresses the exact same notification event being
 * delivered twice within 500ms (WhatsApp sometimes re-posts a notification
 * within milliseconds of posting). It does NOT act as a content filter.
 */
data class ChatMessage(
    val text:        String,
    val sender:      Sender,
    val timestampMs: Long   = System.currentTimeMillis(),
    // nanoTime = monotonically increasing, never repeats → eventId is always unique
    val eventId:     String = "${sender.name}:${System.nanoTime()}"
) {
    enum class Sender { ME, OTHER }

    /**
     * Returns true only when [other] appears to be the exact same notification
     * event as [this], re-delivered within a 500ms debounce window.
     */
    fun isDebounceDuplicateOf(other: ChatMessage): Boolean =
        sender      == other.sender &&
            text        == other.text   &&
            kotlin.math.abs(timestampMs - other.timestampMs) < 500L
}
