package helium314.keyboard.accessibility.extractors.instagram

/**
 * InstagramConstants
 *
 * Single source of truth for every Instagram view-id string and
 * ignore-text set used by InstagramExtractor.
 *
 * KEEP THIS FILE ISOLATED — nothing WhatsApp-related lives here.
 * When Instagram updates its app and view-ids change, only this
 * file needs to be patched.
 *
 * All IDs verified against 3 real accessibility tree snapshots:
 *   Log 1 — Sandy    : plain text messages, single timestamp
 *   Log 2 — Ayush    : XMA bot messages, two timestamps, cta_button
 *   Log 3 — ورین شیخ : portrait XMA shares, timestamp AFTER messages
 */
internal object InstagramConstants {

    const val PKG = "com.instagram.android"

    // ── Root / chrome containers ──────────────────────────────────────────
    const val ID_MESSAGE_LIST   = "$PKG:id/message_list"
    const val ID_COMPOSER_BAR   = "$PKG:id/message_composer_bar"

    // ── Contact name (thread toolbar) ────────────────────────────────────
    const val ID_HEADER_TITLE   = "$PKG:id/header_title"

    // ── Profile-header block identifiers (SKIP) ───────────────────────────
    // The first child of message_list is a LinearLayout containing all of
    // these — it is the contact info card, NOT a message.
    const val ID_USER_AVATAR    = "$PKG:id/user_avatar"

    // Seen-status row at the bottom of message_list (SKIP)
    const val ID_SEEN_STATE     = "$PKG:id/seen_state_text"

    // ── Message container ─────────────────────────────────────────────────
    const val ID_MSG_CONTENT    = "$PKG:id/message_content"

    // ── Sender detection ──────────────────────────────────────────────────
    // sender_avatar present  → received → sender = "other"
    // sender_avatar absent   → sent     → sender = "me"
    const val ID_SENDER_AVATAR  = "$PKG:id/sender_avatar"

    // ── Plain text message ────────────────────────────────────────────────
    const val ID_DIRECT_TEXT    = "$PKG:id/direct_text_message_text_view"

    // ── Generic XMA (bot / promo messages) ───────────────────────────────
    // Seen in Log 2: message_content_generic_xma_container
    //                   ├─ caption_title  ← the readable text
    //                   └─ cta_button     ← "Send me the access" — SKIP
    const val ID_XMA_GENERIC    = "$PKG:id/message_content_generic_xma_container"
    const val ID_CAPTION_TITLE  = "$PKG:id/caption_title"
    const val ID_CTA_BUTTON     = "$PKG:id/cta_button"

    // ── Portrait XMA (shared Instagram profile) ───────────────────────────
    // Seen in Log 3: message_content_portrait_xma_container
    //                   ├─ profile_attribution_picture ← SKIP (image)
    //                   └─ title_text  ← the @username
    const val ID_XMA_PORTRAIT   = "$PKG:id/message_content_portrait_xma_container"
    const val ID_TITLE_TEXT     = "$PKG:id/title_text"

    // ── Nodes to skip inside message_content ─────────────────────────────
    const val ID_FOOTER_LABEL   = "$PKG:id/message_footer_label"  // "Tap and hold to react"
    const val ID_FORWARD_BTN    = "$PKG:id/forwarding_shortcut_button"

    // ── Android class names (used for node classification) ────────────────
    const val CLS_TEXT_VIEW     = "android.widget.TextView"
    const val CLS_LINEAR_LAYOUT = "android.widget.LinearLayout"
    const val CLS_FRAME_LAYOUT  = "android.widget.FrameLayout"
    const val CLS_BUTTON        = "android.widget.Button"

    // ── Timestamp formats seen across all 3 tree logs ─────────────────────
    //   "Today 11:35 am"       Log 1, Log 2
    //   "Yesterday 11:03 pm"   Log 2
    //   "25 Nov, 11:00 pm"     Log 3  ← "d MMM, h:mm a" — NEW format
    //   "Mar 5 2:00 PM"        general Instagram
    //   "3:45 PM"              general Instagram
    // All handled in InstagramTimestampParser.

    // ── Text values that are UI chrome, not real messages ─────────────────
    val IGNORE_TEXT: Set<String> = setOf(
        "message", "message…",
        "typing…", "typing",
        "seen", "delivered",
        "active now", "active today",
        "send message", "send a message",
        "voice message",
        "react to this message", "reply",
        "tap and hold to react",
        "need to fix a typo?",
        "you can edit a message for up to 15 minutes. " +
            "tap and hold a message to start editing.",
        "view transcription", "inquire", "view profile"
    )
}
