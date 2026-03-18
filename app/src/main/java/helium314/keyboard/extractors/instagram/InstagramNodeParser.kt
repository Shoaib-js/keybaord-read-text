package helium314.keyboard.accessibility.extractors.instagram

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

// ══════════════════════════════════════════════════════════════════════════════
// InstagramNodeParser
//
// Classifies a single direct child of message_list into a typed IgRow.
// Called once per child during InstagramExtractor's Stage 1 snapshot.
//
// Uses:
//   InstagramConstants   — all view-id strings and class-name constants
//   InstagramTimestampParser.normalize() — converts timestamp text to ISO-8601
//   IgRow                — output type
//
// ── CLASSIFICATION DECISION TREE ─────────────────────────────────────────────
//
//  viewId == ID_COMPOSER_BAR             → Skip  (keyboard bar, nothing below)
//  viewId == ID_MSG_CONTENT              → parseMessageContent()
//  viewId is empty + class == TextView
//    + InstagramTimestampParser.normalize() succeeds → Timestamp(iso)
//  viewId is empty + class == LinearLayout
//    + has ID_USER_AVATAR child           → Skip  (profile header)
//    + has ID_SEEN_STATE child            → Skip  (seen row)
//  viewId is empty + class == FrameLayout + childCount == 0 → Skip (spacer)
//  class == Button                        → Skip  ("See N updates")
//  anything else                          → Skip
//
// ── SENDER DETECTION ─────────────────────────────────────────────────────────
//
//  Instagram never shows sender_avatar for messages YOU sent.
//  It always shows sender_avatar for messages received from the other person.
//  Reliable across all screen sizes, orientations, and split-screen layouts.
//
//  ID_SENDER_AVATAR child present → "other"
//  ID_SENDER_AVATAR child absent  → "me"
//
// ── ISOLATION ─────────────────────────────────────────────────────────────────
//  Zero WhatsApp logic here. Everything Instagram-specific is in this package.
// ══════════════════════════════════════════════════════════════════════════════

internal object InstagramNodeParser {

    private const val TAG = "CHAT_DEBUG"

    // ── Main classification entry point ───────────────────────────────────────

    fun classify(node: AccessibilityNodeInfo): IgRow {
        val viewId  = node.viewIdResourceName ?: ""
        val rawText = node.text?.toString()?.trim() ?: ""
        val cls     = node.className?.toString() ?: ""

        // ── 1. Composer bar — always at the bottom, nothing useful past it ────
        if (viewId == InstagramConstants.ID_COMPOSER_BAR) {
            return IgRow.Skip
        }

        // ── 2. message_content FrameLayout — the main message unit ────────────
        if (viewId == InstagramConstants.ID_MSG_CONTENT) {
            return parseMessageContent(node)
        }

        // ── 3. Timestamp TextView ─────────────────────────────────────────────
        // Instagram renders timestamps as bare TextViews with NO view-id.
        // Identified by: empty id + TextView class + text that normalizes to ISO.
        if (viewId.isEmpty() && cls == InstagramConstants.CLS_TEXT_VIEW && rawText.isNotEmpty()) {
            val cleaned = cleanText(rawText)
            val iso     = InstagramTimestampParser.normalize(cleaned)
            if (iso.isNotEmpty()) {
                Log.v(TAG, "⏱ [IG] Timestamp: [$rawText] → $iso")
                return IgRow.Timestamp(iso)
            }
            // Has text but it's not a timestamp (e.g. "See 2 updates" Button
            // occasionally appears as a TextView too) — fall through to Skip.
        }

        // ── 4. LinearLayout rows — profile header or seen-state ───────────────
        if (viewId.isEmpty() && cls == InstagramConstants.CLS_LINEAR_LAYOUT) {
            if (hasChildWithId(node, InstagramConstants.ID_USER_AVATAR)) {
                Log.v(TAG, "⏭ [IG] SKIP profile header")
                return IgRow.Skip
            }
            if (hasChildWithId(node, InstagramConstants.ID_SEEN_STATE)) {
                Log.v(TAG, "⏭ [IG] SKIP seen state row")
                return IgRow.Skip
            }
        }

        // ── 5. Empty FrameLayout spacer ───────────────────────────────────────
        if (viewId.isEmpty() && cls == InstagramConstants.CLS_FRAME_LAYOUT && node.childCount == 0) {
            return IgRow.Skip
        }

        // ── 6. System Button ("See 2 updates", etc.) ──────────────────────────
        if (cls == InstagramConstants.CLS_BUTTON) {
            Log.v(TAG, "⏭ [IG] SKIP system button: [$rawText]")
            return IgRow.Skip
        }

        // Unknown row — skip silently
        return IgRow.Skip
    }

    // ── Parse one message_content FrameLayout ─────────────────────────────────
    //
    // Children observed across all tree logs:
    //   1-child:  [direct_text]                        → me,   plain text
    //   2-child:  [sender_avatar, direct_text]         → other, plain text
    //   3-child:  [sender_avatar, direct_text, footer] → other, plain text + footer
    //   1-child:  [xma_generic]                        → me,   bot/promo
    //   3-child:  [sender_avatar, xma_generic, footer] → other, bot/promo
    //   2-child:  [xma_portrait, forward_btn]          → me,   shared profile
    //   2-child:  [sender_avatar, xma_portrait]        → other, shared profile
    //
    private fun parseMessageContent(node: AccessibilityNodeInfo): IgRow {
        var sender  = "me"          // default: no avatar = sent by me
        var msgText: String? = null
        var isXma   = false

        for (i in 0 until node.childCount) {
            val child    = node.getChild(i) ?: continue
            val childId  = child.viewIdResourceName ?: ""
            val childTxt = child.text?.toString()?.trim() ?: ""

            when (childId) {

                // Sender avatar → received message
                InstagramConstants.ID_SENDER_AVATAR -> {
                    sender = "other"
                }

                // Plain text message
                InstagramConstants.ID_DIRECT_TEXT -> {
                    if (childTxt.isNotEmpty() && msgText == null) {
                        msgText = childTxt
                    }
                }

                // Generic XMA: bot/promo DM with caption + CTA button
                InstagramConstants.ID_XMA_GENERIC -> {
                    isXma = true
                    if (msgText == null) {
                        msgText = extractXmaGenericText(child)
                    }
                }

                // Portrait XMA: shared Instagram profile or reel
                InstagramConstants.ID_XMA_PORTRAIT -> {
                    isXma = true
                    if (msgText == null) {
                        val title = extractXmaPortraitText(child)
                        if (!title.isNullOrEmpty()) msgText = "[Profile] $title"
                    }
                }

                // Footer label — "Tap and hold to react" → skip
                InstagramConstants.ID_FOOTER_LABEL -> { /* intentionally empty */ }

                // Forwarding shortcut button → skip
                InstagramConstants.ID_FORWARD_BTN -> { /* intentionally empty */ }

                // CTA button inside XMA containers → skip
                InstagramConstants.ID_CTA_BUTTON -> { /* intentionally empty */ }

                // Unknown children → skip
            }
            child.recycle()
        }

        if (msgText.isNullOrBlank()) {
            Log.v(TAG, "⏭ [IG] SKIP message_content: no text (sender=$sender)")
            return IgRow.Skip
        }

        Log.d(TAG, "📝 [IG] Collected [$sender] [${msgText.take(40)}]")
        return if (isXma) IgRow.XmaMessage(sender = sender, text = msgText)
        else       IgRow.Message   (sender = sender, text = msgText)
    }

    // ── Extract caption text from generic XMA container ───────────────────────
    //
    // message_content_generic_xma_container
    //   ├── caption_title ("Hey there! Glad you're here 😊")  ← we want this
    //   └── cta_button
    //         └── null TextView ("Send me the access")
    //
    private fun extractXmaGenericText(xmaNode: AccessibilityNodeInfo): String? {
        for (i in 0 until xmaNode.childCount) {
            val child   = xmaNode.getChild(i) ?: continue
            val childId = child.viewIdResourceName ?: ""
            val childTxt= child.text?.toString()?.trim() ?: ""
            child.recycle()
            if (childId == InstagramConstants.ID_CAPTION_TITLE && childTxt.isNotEmpty()) {
                return "[Bot] $childTxt"
            }
        }
        return null
    }

    // ── Extract username from portrait XMA container ──────────────────────────
    //
    // message_content_portrait_xma_container
    //   ├── profile_attribution_picture (ImageView, no text)
    //   └── title_text ("marinaeventcomplex.farmhouse")  ← we want this
    //
    private fun extractXmaPortraitText(xmaNode: AccessibilityNodeInfo): String? {
        for (i in 0 until xmaNode.childCount) {
            val child   = xmaNode.getChild(i) ?: continue
            val childId = child.viewIdResourceName ?: ""
            val childTxt= child.text?.toString()?.trim() ?: ""
            child.recycle()
            if (childId == InstagramConstants.ID_TITLE_TEXT && childTxt.isNotEmpty()) {
                return childTxt
            }
        }
        return null
    }

    // ── Check if any direct child has the given viewId ────────────────────────

    private fun hasChildWithId(node: AccessibilityNodeInfo, targetId: String): Boolean {
        for (i in 0 until node.childCount) {
            val child   = node.getChild(i) ?: continue
            val childId = child.viewIdResourceName ?: ""
            child.recycle()
            if (childId == targetId) return true
        }
        return false
    }

    // ── Strip invisible Unicode characters that appear in Instagram text ───────

    fun cleanText(raw: String): String =
        raw.replace('\u202F', ' ')   // narrow no-break space
            .replace('\u00A0', ' ')   // non-breaking space
            .replace('\u200E', ' ')   // LTR mark
            .replace('\u200F', ' ')   // RTL mark
            .replace('\u200B', ' ')   // zero-width space
            .trim()
}
