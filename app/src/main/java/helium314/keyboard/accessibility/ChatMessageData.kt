package helium314.keyboard.accessibility

/**
 * Shared data model for a single chat message.
 * Used by WhatsAppExtractorHelper, InstagramExtractor, OnDemandMessageExtractor.
 */
data class ChatMessageData(
    val sender: String,   // "me" or "other"
    val message: String,
    val time: String,     // ISO-8601: "2026-03-18T11:35:00"
    val y: Int = 0        // screen Y position (legacy compat)
)
