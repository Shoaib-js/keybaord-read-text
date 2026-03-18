//package helium314.keyboard.accessibility.extractors.instagram
//
//import android.util.Log
//import android.view.accessibility.AccessibilityNodeInfo
//import helium314.keyboard.accessibility.ChatMessageData
//import java.text.SimpleDateFormat
//import java.util.Calendar
//import java.util.Locale
//
///**
// * InstagramExtractor
// *
// * Extracts chat messages from an Instagram Direct accessibility tree snapshot.
// * Called by ChatAccessibilityService when pkg == "com.instagram.android".
// *
// * Public API:
// *   InstagramExtractor.extract(root)        → List<ChatMessageData>
// *   InstagramExtractor.getContactName(root) → String
// *
// * ══════════════════════════════════════════════════════════════════════════
// * ARCHITECTURE: TWO-STAGE SEQUENTIAL FLAT WALK
// * ══════════════════════════════════════════════════════════════════════════
// *
// * WHY NOT RECURSION (old approach was broken):
// *   The old traverseInstagram() passed `timeToUse` DOWN to children.
// *   But Instagram's timestamp node ("Today 11:35 am") and message_content
// *   nodes are SIBLINGS at the same RecyclerView level — NOT parent/child.
// *   The timestamp was absorbed with no children to propagate to, and every
// *   message inherited currentTime="" from the RecyclerView parent → all
// *   messages were dropped silently.
// *
// * ─── STAGE 1: Snapshot ───────────────────────────────────────────────────
// *   Walk message_list direct children in order.
// *   Classify each child as: IgRow.Timestamp | IgRow.Message | (null = skip).
// *   No timestamps are assigned here — only raw rows are collected.
// *
// * ─── STAGE 2: Timestamp assignment ───────────────────────────────────────
// *   Key insight from tree log 3 (ورین شیخ):
// *     Instagram SOMETIMES places the timestamp AFTER the message group it
// *     describes (not always before). Example:
// *       message_content "Esi barat le jayege"   ← no timestamp yet
// *       TextView "25 Nov, 11:00 pm"              ← timestamp comes AFTER
// *       message_content "Itne sare option"       ← subsequent message
// *
// *   Strategy — "first-timestamp-as-orphan-anchor":
// *     ① Find the FIRST timestamp anywhere in the rows list.
// *     ② Messages appearing BEFORE that first timestamp (orphans) are
// *        assigned the first timestamp (they belong to the same time window).
// *     ③ Messages appearing AFTER a timestamp are assigned the most recent
// *        preceding timestamp (standard sequential behaviour).
// *     ④ If NO timestamp exists at all → all messages are dropped.
// *        (We cannot construct a valid ISO time without any anchor.)
// *
// * ══════════════════════════════════════════════════════════════════════════
// * SENDER DETECTION (avatar-based — NOT X-position)
// * ══════════════════════════════════════════════════════════════════════════
// *   sender_avatar present in message_content children → sender = "other"
// *   sender_avatar absent                              → sender = "me"
// *
// *   This is reliable across all screen sizes, orientations, split-screen,
// *   and foldables. The old X-position approach broke on tablets and any
// *   layout that isn't a standard portrait phone.
// *
// * ══════════════════════════════════════════════════════════════════════════
// * MESSAGE CONTENT TYPES (all 3 tree logs analysed)
// * ══════════════════════════════════════════════════════════════════════════
// *   direct_text_message_text_view          → plain text       → use as-is
// *   caption_title (in generic_xma)         → bot/promo text   → "[Bot] text"
// *   title_text    (in portrait_xma)        → shared @profile  → "[Profile] user"
// *   message_footer_label                   → "Tap and hold…"  → SKIP
// *   forwarding_shortcut_button             → forward icon      → SKIP
// *   cta_button                             → action button     → SKIP
// *
// * ══════════════════════════════════════════════════════════════════════════
// * SKIP RULES for message_list direct children
// * ══════════════════════════════════════════════════════════════════════════
// *   LinearLayout with user_avatar child         → profile header (top card)
// *   LinearLayout with seen_state_text child      → "Seen 1m ago" footer
// *   FrameLayout with childCount == 0             → spacer
// *   Button class (no message_content id)         → "See 2 updates" / system
// *   message_composer_bar                         → keyboard bar (stop walking)
// */
//object InstagramExtractor {
//
//    private const val TAG = "CHAT_DEBUG"
//
//    // ════════════════════════════════════════════════════════════════════
//    // INTERNAL ROW MODEL — used only within Stage 1/2
//    // ════════════════════════════════════════════════════════════════════
//
//    /** A single classified row from the message_list RecyclerView. */
//    private sealed class IgRow {
//        /**
//         * A timestamp TextView — date+time combined.
//         * e.g. "Today 11:35 am", "Yesterday 11:03 pm", "25 Nov, 11:00 pm"
//         * [isoTime] is already normalized to ISO-8601.
//         */
//        data class Timestamp(val isoTime: String) : IgRow()
//
//        /**
//         * A parsed message — timestamp NOT yet assigned.
//         * Assigned in Stage 2 after all rows are collected.
//         */
//        data class Message(val sender: String, val text: String) : IgRow()
//    }
//
//    // ════════════════════════════════════════════════════════════════════
//    // PUBLIC API
//    // ════════════════════════════════════════════════════════════════════
//
//    /**
//     * Main entry point.
//     * @param root Root AccessibilityNodeInfo of the Instagram window.
//     * @return Filtered, deduplicated list of [ChatMessageData], oldest first.
//     */
//    fun extract(root: AccessibilityNodeInfo): List<ChatMessageData> {
//
//        val messageList = root.findAccessibilityNodeInfosByViewId(
//            InstagramConstants.ID_MESSAGE_LIST
//        )?.firstOrNull() ?: run {
//            Log.w(TAG, "⚠️ [IG] message_list not found — tree may not be a DM thread")
//            return emptyList()
//        }
//
//        // ── STAGE 1: Collect rows ─────────────────────────────────────────
//        val rows = mutableListOf<IgRow>()
//        for (i in 0 until messageList.childCount) {
//            val child = messageList.getChild(i) ?: continue
//            val row   = classifyTopLevelChild(child)
//            child.recycle()
//            if (row != null) rows.add(row)
//        }
//
//        Log.d(TAG, "📋 [IG] Stage1: ${rows.size} rows | " +
//            "${rows.count { it is IgRow.Timestamp }} ts | " +
//            "${rows.count { it is IgRow.Message }} msgs")
//
//        // ── STAGE 2: Assign timestamps ────────────────────────────────────
//        val raw = assignTimestamps(rows)
//
//        // ── Final filter + dedup ──────────────────────────────────────────
//        val filtered = raw
//            .filter { parseIsoToMs(it.time) > 0L }
//            .filter { it.message.length >= 2 }
//            .filter { it.message.lowercase().trim() !in InstagramConstants.IGNORE_TEXT }
//            .distinctBy { "${it.sender}|${it.message.trim().lowercase()}|${it.time}" }
//
//        Log.d(TAG, "📊 [IG] Extracted: raw=${raw.size} → filtered=${filtered.size}")
//        return filtered
//    }
//
//    /**
//     * Reads the contact name from the thread toolbar.
//     * @return Display name or "Unknown".
//     */
//    fun getContactName(root: AccessibilityNodeInfo): String =
//        root.findAccessibilityNodeInfosByViewId(InstagramConstants.ID_HEADER_TITLE)
//            ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
//
//    // ════════════════════════════════════════════════════════════════════
//    // STAGE 1 — Classify top-level message_list children
//    // ════════════════════════════════════════════════════════════════════
//
//    /**
//     * Classifies a single direct child of message_list.
//     * Returns IgRow.Timestamp, IgRow.Message, or null (= skip).
//     *
//     * Called once per child with the child already obtained via getChild().
//     * The caller is responsible for recycling the node after this returns.
//     */
//    private fun classifyTopLevelChild(node: AccessibilityNodeInfo): IgRow? {
//        val nodeId    = node.viewIdResourceName ?: ""
//        val nodeCls   = node.className?.toString() ?: ""
//        val nodeText  = node.text?.toString()?.trim() ?: ""
//
//        // ── Composer bar → always last, nothing useful below ──────────────
//        if (nodeId == InstagramConstants.ID_COMPOSER_BAR) {
//            return null
//        }
//
//        // ── message_content FrameLayout → parse as a message ──────────────
//        if (nodeId == InstagramConstants.ID_MSG_CONTENT) {
//            return parseMessageContent(node)
//        }
//
//        // ── Timestamp TextView (null id + TextView + timestamp text) ───────
//        //
//        // Instagram renders timestamps as bare TextViews with no view-id.
//        // We identify them by: null id + TextView class + text that parses
//        // as a valid Instagram timestamp format.
//        if (nodeId.isEmpty() && nodeCls == InstagramConstants.CLS_TEXT_VIEW && nodeText.isNotEmpty()) {
//            val cleaned = cleanTimestamp(nodeText)
//            val iso     = InstagramTimestampParser.normalize(cleaned)
//            if (iso.isNotEmpty()) {
//                Log.v(TAG, "⏱ [IG] Timestamp: [$nodeText] → $iso")
//                return IgRow.Timestamp(iso)
//            }
//            // Text exists but isn't a timestamp → fall through to skip
//        }
//
//        // ── Profile-header LinearLayout (user_avatar child) → SKIP ─────────
//        // This is the large "you're chatting with X" card at the top.
//        if (nodeId.isEmpty() && nodeCls == InstagramConstants.CLS_LINEAR_LAYOUT) {
//            if (hasDirectChildWithId(node, InstagramConstants.ID_USER_AVATAR)) {
//                Log.v(TAG, "⏭ [IG] SKIP profile header")
//                return null
//            }
//            // Seen-status LinearLayout (seen_state_text child) → SKIP
//            if (hasDirectChildWithId(node, InstagramConstants.ID_SEEN_STATE)) {
//                Log.v(TAG, "⏭ [IG] SKIP seen state row")
//                return null
//            }
//        }
//
//        // ── Empty spacer FrameLayout (childCount == 0) → SKIP ─────────────
//        if (nodeId.isEmpty() &&
//            nodeCls == InstagramConstants.CLS_FRAME_LAYOUT &&
//            node.childCount == 0
//        ) {
//            return null
//        }
//
//        // ── System Buttons ("See 2 updates", etc.) → SKIP ─────────────────
//        if (nodeCls == InstagramConstants.CLS_BUTTON) {
//            Log.v(TAG, "⏭ [IG] SKIP system button: [$nodeText]")
//            return null
//        }
//
//        // Unknown node → skip silently
//        return null
//    }
//
//    // ════════════════════════════════════════════════════════════════════
//    // STAGE 1 — Parse one message_content FrameLayout
//    // ════════════════════════════════════════════════════════════════════
//
//    /**
//     * Walks the children of a message_content FrameLayout to extract:
//     *   - sender (via sender_avatar presence)
//     *   - message text (via direct_text, caption_title, or title_text)
//     *
//     * Returns IgRow.Message or null if no text is found.
//     */
//    private fun parseMessageContent(contentNode: AccessibilityNodeInfo): IgRow.Message? {
//        var sender  = "me"       // default: sent message (no avatar)
//        var msgText: String? = null
//
//        for (i in 0 until contentNode.childCount) {
//            val child    = contentNode.getChild(i) ?: continue
//            val childId  = child.viewIdResourceName ?: ""
//            val childTxt = child.text?.toString()?.trim() ?: ""
//
//            when (childId) {
//
//                // Sender avatar → this message was RECEIVED
//                InstagramConstants.ID_SENDER_AVATAR -> {
//                    sender = "other"
//                }
//
//                // Plain text message (most common)
//                InstagramConstants.ID_DIRECT_TEXT -> {
//                    if (childTxt.isNotEmpty() && msgText == null) {
//                        msgText = childTxt
//                    }
//                }
//
//                // Generic XMA container (bot/promo messages)
//                // Extract caption_title from its children
//                InstagramConstants.ID_XMA_GENERIC -> {
//                    if (msgText == null) {
//                        msgText = extractCaptionFromXma(child)
//                    }
//                }
//
//                // Portrait XMA container (shared Instagram profile)
//                // Extract title_text (@username) from its children
//                InstagramConstants.ID_XMA_PORTRAIT -> {
//                    if (msgText == null) {
//                        msgText = extractTitleFromXma(child)
//                    }
//                }
//
//                // Footer label → always skip
//                InstagramConstants.ID_FOOTER_LABEL -> { /* skip "Tap and hold to react" */ }
//
//                // Forwarding button → skip
//                InstagramConstants.ID_FORWARD_BTN -> { /* skip */ }
//
//                // CTA button inside xma containers → skip
//                InstagramConstants.ID_CTA_BUTTON -> { /* skip "Send me the access" */ }
//
//                // Unknown child → skip
//            }
//
//            child.recycle()
//        }
//
//        if (msgText.isNullOrBlank()) {
//            Log.v(TAG, "⏭ [IG] SKIP message_content: no text (sender=$sender)")
//            return null
//        }
//
//        val lowerText = msgText.lowercase().trim()
//        if (lowerText in InstagramConstants.IGNORE_TEXT) {
//            Log.v(TAG, "⏭ [IG] SKIP ignore list: [${msgText.take(30)}]")
//            return null
//        }
//
//        Log.d(TAG, "📝 [IG] Collected [$sender] [${msgText.take(40)}]")
//        return IgRow.Message(sender, msgText)
//    }
//
//    /**
//     * Extracts [caption_title] text from a generic_xma_container.
//     * Returns "[Bot] <text>" or null if not found.
//     */
//    private fun extractCaptionFromXma(xmaNode: AccessibilityNodeInfo): String? {
//        for (i in 0 until xmaNode.childCount) {
//            val child = xmaNode.getChild(i) ?: continue
//            val id    = child.viewIdResourceName ?: ""
//            val txt   = child.text?.toString()?.trim() ?: ""
//            child.recycle()
//            if (id == InstagramConstants.ID_CAPTION_TITLE && txt.isNotEmpty()) {
//                return "[Bot] $txt"
//            }
//        }
//        return null
//    }
//
//    /**
//     * Extracts [title_text] (@username) from a portrait_xma_container.
//     * Returns "[Profile] <username>" or null if not found.
//     */
//    private fun extractTitleFromXma(xmaNode: AccessibilityNodeInfo): String? {
//        for (i in 0 until xmaNode.childCount) {
//            val child = xmaNode.getChild(i) ?: continue
//            val id    = child.viewIdResourceName ?: ""
//            val txt   = child.text?.toString()?.trim() ?: ""
//            child.recycle()
//            if (id == InstagramConstants.ID_TITLE_TEXT && txt.isNotEmpty()) {
//                return "[Profile] $txt"
//            }
//        }
//        return null
//    }
//
//    // ════════════════════════════════════════════════════════════════════
//    // STAGE 2 — Assign timestamps to messages
//    // ════════════════════════════════════════════════════════════════════
//
//    /**
//     * Assigns ISO timestamps to all collected IgRow.Message entries.
//     *
//     * Strategy:
//     *   ① Pre-scan: find the FIRST timestamp in the list.
//     *   ② Start currentTimestamp = firstTimestamp (handles orphan messages
//     *      that appear before any timestamp node in the tree — tree log 3).
//     *   ③ Walk rows in order:
//     *      • IgRow.Timestamp → update currentTimestamp
//     *      • IgRow.Message   → create ChatMessageData with currentTimestamp
//     *   ④ If no timestamp exists anywhere → all messages are skipped
//     *      (cannot build a valid API body without time context).
//     */
//    private fun assignTimestamps(rows: List<IgRow>): List<ChatMessageData> {
//        val result = mutableListOf<ChatMessageData>()
//
//        // Pre-scan: first timestamp in the list (may be after some messages — tree log 3)
//        val firstTimestamp = rows
//            .filterIsInstance<IgRow.Timestamp>()
//            .firstOrNull()?.isoTime
//            ?: ""
//
//        if (firstTimestamp.isEmpty()) {
//            Log.w(TAG, "⚠️ [IG] No timestamp found in any row — dropping all messages")
//            return result
//        }
//
//        var currentTimestamp = firstTimestamp  // orphan messages get the first known timestamp
//
//        for (row in rows) {
//            when (row) {
//                is IgRow.Timestamp -> {
//                    currentTimestamp = row.isoTime
//                    Log.v(TAG, "⏱ [IG] Timestamp context → $currentTimestamp")
//                }
//                is IgRow.Message -> {
//                    Log.d(TAG, "✅ [IG] ADD [${row.sender}] [${row.text.take(40)}] @ $currentTimestamp")
//                    result.add(
//                        ChatMessageData(
//                            sender  = row.sender,
//                            message = row.text,
//                            time    = currentTimestamp
//                        )
//                    )
//                }
//            }
//        }
//
//        return result
//    }
//
//    // ════════════════════════════════════════════════════════════════════
//    // UTILITY — node helpers
//    // ════════════════════════════════════════════════════════════════════
//
//    /**
//     * Returns true if [node] has a direct child whose viewIdResourceName
//     * equals [targetId]. Recycles each child after checking.
//     */
//    private fun hasDirectChildWithId(
//        node: AccessibilityNodeInfo,
//        targetId: String
//    ): Boolean {
//        for (i in 0 until node.childCount) {
//            val child = node.getChild(i) ?: continue
//            val id    = child.viewIdResourceName ?: ""
//            child.recycle()
//            if (id == targetId) return true
//        }
//        return false
//    }
//
//    // ════════════════════════════════════════════════════════════════════
//    // UTILITY — time helpers (self-contained, no dependency on service)
//    // ════════════════════════════════════════════════════════════════════
//
//    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
//
//    /** Normalises unicode whitespace variants that Instagram injects. */
//    private fun cleanTimestamp(raw: String): String =
//        raw.replace('\u202F', ' ').replace('\u00A0', ' ').trim()
//
//    private fun parseIsoToMs(isoTime: String): Long =
//        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (_: Exception) { 0L }
//}




package helium314.keyboard.accessibility.extractors.instagram

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import helium314.keyboard.accessibility.ChatMessageData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ══════════════════════════════════════════════════════════════════════════════
// InstagramExtractor
//
// Production-ready Instagram DM message extractor.
// Called by ChatAccessibilityService when pkg == "com.instagram.android".
//
// PUBLIC API:
//   InstagramExtractor.extract(root)        → List<ChatMessageData>
//   InstagramExtractor.getContactName(root) → String
//
// ── ROOT CAUSE OF ORIGINAL BUG ───────────────────────────────────────────────
//
// The original code used deep recursive traversal, passing `timeToUse`
// DOWNWARD to children. This was broken because:
//
//   In Instagram's tree, the timestamp node ("Today 11:35 am") and the
//   message_content nodes are SIBLINGS at the same level inside message_list.
//
//   When message_list was visited, currentTime="" was passed to ALL its children
//   equally. The timestamp was found in child[N] but has no children of its own,
//   so the updated timeToUse was never propagated sideways to the message siblings.
//
//   Result: every message was skipped (timeToUse was always empty).
//
// Fix: sequential flat walk — same principle as WhatsApp's date-divider list walk.
//
// ── SECOND BUG: IDENTICAL seenKey FOR MESSAGES IN SAME GROUP ─────────────────
//
// Instagram's accessibility tree gives every message in the same timestamp group
// an identical ISO time string (e.g. ALL messages at "Today 11:35 am" become
// "2026-03-18T11:35:00"). This means three messages like:
//
//   sender=me   text="Hlo"  time="2026-03-18T11:35:00"
//   sender=me   text="Hlo"  time="2026-03-18T11:35:00"   ← same seenKey → dropped
//   sender=me   text="Hlo"  time="2026-03-18T11:35:00"   ← same seenKey → dropped
//
// seenKey = "me|hlo|2026-03-18T11:35:00" is identical for all three.
// The AccessibilityService's deduplication drops the 2nd and 3rd as "already seen".
//
// Fix — Stage 4: Per-group second offset
//   Messages that share the same base timestamp are assigned sequential seconds:
//   msg[0] → 2026-03-18T11:35:00
//   msg[1] → 2026-03-18T11:35:01
//   msg[2] → 2026-03-18T11:35:02
//
//   This makes every seenKey unique while preserving chronological order within
//   the group. The second-level precision does not affect display — the API
//   receives the correct conversation sequence.
//
// ── EDGE CASE: TIMESTAMP AFTER MESSAGE (tree log 3 — ورین شیخ) ───────────────
//
// In some conversations, a timestamp appears AFTER the first message of a group:
//
//   message_content  "Esi barat le jayege"   ← appears BEFORE timestamp in tree
//   TextView         "25 Nov, 11:00 pm"       ← timestamp comes AFTER
//   message_content  "Itne sare option"
//
// A naïve forward-only walk would discard "Esi barat le jayege".
//
// Fix — Stage 2: Orphan timestamp
//   Before Stage 3, find the FIRST timestamp in the snapshot list.
//   Seed currentTimestamp with it. Messages before it inherit this value.
//
// ── STAGE OVERVIEW ────────────────────────────────────────────────────────────
//
//   Stage 1 — Snapshot
//     Walk every direct child of message_list.
//     Classify each as IgRow via InstagramNodeParser.
//     Nothing written to result yet.
//
//   Stage 2 — Orphan timestamp
//     Find first IgRow.Timestamp. Seed currentTimestamp.
//     No timestamp found at all → return empty (tree not ready).
//
//   Stage 3 — Propagate + collect
//     Walk rows in order.
//     Timestamp → update currentTimestamp.
//     Message/XmaMessage → collect into pendingMsgs list tagged with currentTimestamp.
//     Skip → discard.
//
//   Stage 4 — Unique second offsets + final filter
//     Group messages by their base timestamp.
//     Within each group, add 0, 1, 2, … seconds to make every time unique.
//     Apply text filter. Return sorted list.
//
// ══════════════════════════════════════════════════════════════════════════════

object InstagramExtractor {

    private const val TAG = "CHAT_DEBUG"
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // ── Main entry point ──────────────────────────────────────────────────────
    //
    // [fallbackTimestamp] — ISO-8601 timestamp from the last successful extraction.
    // Passed by ChatAccessibilityService when the RecyclerView renders without its
    // timestamp node (happens when keyboard opens and shrinks the visible area,
    // scrolling the timestamp TextView out of the accessibility tree).
    //
    // With fallback:  messages are extracted using the last known time   ← correct
    // Without fallback + no timestamp in tree → emptyList() is returned  ← was losing msgs
    //
    fun extract(root: AccessibilityNodeInfo, fallbackTimestamp: String = ""): List<ChatMessageData> {

        // ── Locate message_list RecyclerView ─────────────────────────────────
        val messageList = root
            .findAccessibilityNodeInfosByViewId(InstagramConstants.ID_MESSAGE_LIST)
            ?.firstOrNull()
            ?: run {
                Log.w(TAG, "⚠️ [IG] message_list not found")
                return emptyList()
            }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 1 — Snapshot
        // Classify every direct child of message_list into a typed IgRow.
        // ════════════════════════════════════════════════════════════════════
        val rows = mutableListOf<IgRow>()
        var tsCount  = 0
        var msgCount = 0

        for (i in 0 until messageList.childCount) {
            val child = messageList.getChild(i) ?: continue
            val row   = InstagramNodeParser.classify(child)
            rows.add(row)
            child.recycle()
            when (row) {
                is IgRow.Timestamp  -> tsCount++
                is IgRow.Message,
                is IgRow.XmaMessage -> msgCount++
                else                -> Unit
            }
        }
        Log.d(TAG, "📋 [IG][STAGE1] ${rows.size} rows | $tsCount ts | $msgCount msgs")

        // ════════════════════════════════════════════════════════════════════
        // STAGE 2 — Orphan timestamp
        //
        // Try sources in priority order:
        //   1. First IgRow.Timestamp found in the snapshot (ideal path)
        //   2. fallbackTimestamp from previous successful extraction
        //      ← This covers the keyboard-open case where Instagram's
        //        RecyclerView shrinks and scrolls the timestamp node away.
        //   3. Neither → return empty (no safe anchor for timestamps)
        // ════════════════════════════════════════════════════════════════════
        val orphan: String = rows
            .filterIsInstance<IgRow.Timestamp>()
            .firstOrNull()?.normalizedIso
            ?: fallbackTimestamp.takeIf { it.isNotEmpty() }
                ?.also { Log.d(TAG, "📅 [IG][STAGE2] No timestamp in tree — using fallback: $it") }
            ?: run {
                Log.d(TAG, "⚠️ [IG][STAGE2] No timestamp node and no fallback — tree not ready")
                return emptyList()
            }

        Log.v(TAG, "⏱ [IG] Timestamp context → $orphan")

        // ════════════════════════════════════════════════════════════════════
        // STAGE 3 — Propagate + collect
        // Walk rows in order. On Timestamp, update state.
        // On Message/XmaMessage, collect with current timestamp.
        // ════════════════════════════════════════════════════════════════════

        // PendingMsg: message text + sender + the base ISO timestamp it belongs to
        data class PendingMsg(val sender: String, val text: String, val baseTime: String)

        val pending          = mutableListOf<PendingMsg>()
        var currentTimestamp = orphan

        for (row in rows) {
            when (row) {
                is IgRow.Timestamp -> {
                    currentTimestamp = row.normalizedIso
                }
                is IgRow.Message -> {
                    if (row.text.isBlank()) continue
                    if (row.text.lowercase().trim() in InstagramConstants.IGNORE_TEXT) continue
                    pending.add(PendingMsg(row.sender, row.text, currentTimestamp))
                    Log.d(TAG, "📝 [IG] Collected [${row.sender}] [${row.text.take(40)}]")
                }
                is IgRow.XmaMessage -> {
                    if (row.text.isBlank()) continue
                    if (row.text.lowercase().trim() in InstagramConstants.IGNORE_TEXT) continue
                    pending.add(PendingMsg(row.sender, row.text, currentTimestamp))
                    Log.d(TAG, "📝 [IG] Collected XMA [${row.sender}] [${row.text.take(40)}]")
                }
                is IgRow.Skip -> Unit
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 4 — Unique second offsets
        //
        // Problem: every message in a timestamp group has the same base ISO
        // time. seenKey = sender|text|time would collide for identical texts
        // sent at the same time (e.g. three "Hlo" messages all at 11:35:00).
        //
        // Solution: within each base-timestamp group, assign seconds 0, 1, 2 …
        // so each message gets a unique `time` field and thus a unique seenKey.
        //
        // msg[0] @ 11:35:00 → stays 11:35:00
        // msg[1] @ 11:35:00 → becomes 11:35:01
        // msg[2] @ 11:35:00 → becomes 11:35:02
        //
        // This does NOT change order — messages within a group preserve their
        // original relative order. The seconds are a tiebreaker only.
        // ════════════════════════════════════════════════════════════════════
        val groupCounters = mutableMapOf<String, Int>()
        val result        = mutableListOf<ChatMessageData>()

        for (msg in pending) {
            val offset    = groupCounters.getOrDefault(msg.baseTime, 0)
            groupCounters[msg.baseTime] = offset + 1

            val uniqueTime = if (offset == 0) msg.baseTime
            else addSeconds(msg.baseTime, offset)

            result.add(ChatMessageData(
                sender  = msg.sender,
                message = msg.text,
                time    = uniqueTime
            ))
            Log.d(TAG, "✅ [IG] ADD [${msg.sender}] [${msg.text.take(40)}] @ $uniqueTime")
        }

        val filtered = result.filter { it.message.length >= 2 }
        Log.d(TAG, "📊 [IG] Extracted: raw=${result.size} → filtered=${filtered.size}")
        return filtered
    }

    // ── Contact name from DM header ───────────────────────────────────────────

    fun getContactName(root: AccessibilityNodeInfo): String {
        return root
            .findAccessibilityNodeInfosByViewId(InstagramConstants.ID_HEADER_TITLE)
            ?.firstOrNull()
            ?.text?.toString()?.trim()
            ?: "Unknown"
    }

    // ── Add N seconds to an ISO timestamp string ──────────────────────────────
    //
    // Used in Stage 4 to give each message a unique `time` field within a group.
    // If parsing fails (should never happen — ISO_FMT produced the string),
    // returns the original string unchanged so no message is lost.
    //
    private fun addSeconds(iso: String, seconds: Int): String {
        return try {
            val cal = Calendar.getInstance().apply {
                time = ISO_FMT.parse(iso)!!
            }
            cal.add(Calendar.SECOND, seconds)
            ISO_FMT.format(cal.time)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ [IG] addSeconds failed for [$iso]: ${e.message}")
            iso   // fallback: return unchanged — message is still kept
        }
    }
}
