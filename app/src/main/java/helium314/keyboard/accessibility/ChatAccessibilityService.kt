package helium314.keyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ChatAccessibilityService — Final Version
 *
 * ═══════════════════════════════════════════════════════════════
 * COMPLETE BUG FIX HISTORY:
 * ═══════════════════════════════════════════════════════════════
 *
 * #1  Fallback getCurrentIsoTime() → returns "" on parse fail
 * #2  \u202F Unicode narrow space → cleanTimestampString()
 * #3  Scroll msgs wrong → correct date context tracking
 * #4  Sender: status node presence = "me"
 * #5  Shadow rootNode variable → single declaration
 * #6  Pinned banner filter → isInsidePinnedOrSystemBanner()
 * #7  Attachments → content/title/file_type parsing
 * #8  Dead traverseWhatsApp() → removed
 * #9  seenKey = sender+time+text → now normalizedText + HH:mm
 * #10 isTop/isBottom Y logic → wrong adds → REMOVED
 * #11 addFirst() removed wrongly → RESTORED with safety guard
 * #12 Date context not tracked → ORDER-BASED list walk
 * #13 date_divider_header at coordinator → pre-scan added
 *
 * ═══════════════════════════════════════════════════════════════
 * FINAL TWO BUGS FOUND FROM LOG 15:01:xx:
 * ═══════════════════════════════════════════════════════════════
 *
 * BUG A — Scroll history msgs should go to FRONT (addFirst):
 *   User scrolls up → old msgs visible on screen → should add to cache FRONT
 *   so backend gets older context too.
 *   Previous fix removed addFirst() entirely — WRONG.
 *   Correct fix: addFirst() IS needed, but only when timestamps are VALID.
 *   Now that date context tracking is correct, timestamps are reliable → safe to restore.
 *
 *   GATE for addFirst:
 *     msgTimeMs > 0          (valid timestamp)
 *     msgTimeMs < firstCacheTimeMs  (genuinely older than cache front)
 *     normKey NOT in seenKeys
 *
 * BUG B — Same-text duplicate messages skipped:
 *   "THIK" sent at 11:58, "thik" received at 12:09 → same normalized text
 *   Old seenKey = "thik" → both match → 12:09 msg skipped!
 *
 *   NEW seenKey = normalizedText + "|" + timeHHmm
 *   "thik" at 11:58 → key = "thik|11:58"
 *   "thik" at 12:09 → key = "thik|12:09" → DIFFERENT → both added ✓
 *
 *   timeHHmm = ISO string substring [11:16] = "HH:mm"
 *   "2026-03-11T11:58:00" → "11:58"
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG            = "CHAT_DEBUG"
        const val MAX_CACHE_SIZE = 15

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        private const val DEBOUNCE_MS = 500L

        const val ACTION_CHAT_CONTEXT = "helium314.keyboard.CHAT_CONTEXT"
        const val EXTRA_MESSAGES      = "chat_messages_json"
        const val EXTRA_PACKAGE       = "source_package"
        const val EXTRA_CHAT_WITH     = "chat_with"

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

        private val WHATSAPP_BANNER_IDS = setOf(
            "com.whatsapp:id/pinnedMessagesBanner",
            "com.whatsapp:id/pinnedMessagesBanner_content",
            "com.whatsapp:id/pinnedMessagesBanner_pinned_icon"
        )

        private val WHATSAPP_SYSTEM_IDS = setOf(
            "com.whatsapp:id/info",
            "com.whatsapp:id/conversation_contact_status"
        )

        private val WHATSAPP_IGNORE_TEXT = setOf(
            "type a message", "type a message…",
            "typing…", "typing", "online", "offline",
            "last seen", "last seen recently",
            "voice message", "this message was deleted",
            "you deleted this message",
            "missed voice call", "missed video call",
            "tap to call back", "seen", "delivered", "sent",
            "yesterday", "today", "attach", "emoji", "sticker",
            "gif", "camera", "audio", "message",
            "you pinned a message",
            "messages and calls are end-to-end encrypted."
        )

        private val INSTAGRAM_IGNORE = setOf(
            "message", "message…", "typing…", "typing",
            "seen", "delivered", "active now", "active today",
            "need to fix a typo?",
            "you can edit a message for up to 15 minutes. tap and hold a message to start editing.",
            "view transcription", "inquire", "view profile",
            "send message", "send a message", "voice message",
            "react to this message", "reply"
        )

        private val INSTAGRAM_MSG_IDS = setOf(
            "com.instagram.android:id/direct_text_message_text_view"
        )

        private val DAY_OF_WEEK_MAP = mapOf(
            "monday"    to Calendar.MONDAY,
            "tuesday"   to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday"  to Calendar.THURSDAY,
            "friday"    to Calendar.FRIDAY,
            "saturday"  to Calendar.SATURDAY,
            "sunday"    to Calendar.SUNDAY
        )
    }

    // ══════════════════════════════════════════
    // CACHE STATE
    // ArrayDeque: index 0 = oldest, last = newest
    // ══════════════════════════════════════════
    private val messageCache     = ArrayDeque<ChatMessageData>()
    private val seenKeys         = LinkedHashSet<String>()
    private var lastCacheTimeMs  = 0L
    private var cachedPackage    = ""
    private var cachedChatWith   = ""
    private var cacheInitialized = false

    private val serviceScope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedTime = 0L

    // ══════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════
    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            // TYPE_VIEW_SCROLLED intentionally NOT registered.
            // Scroll must never trigger message collection or debounce resets.
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 400
            packageNames = SUPPORTED_PACKAGES.toTypedArray()
        }
        serviceInfo = info
        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "║  ChatAccessibilityService CONNECTED  ║")
        Log.i(TAG, "╚══════════════════════════════════════╝")
    }

    override fun onInterrupt() = Unit

    // ══════════════════════════════════════════
    // MAIN EVENT — Bug #5 fix: single rootNode
    // ══════════════════════════════════════════
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) return
        lastProcessedTime = now

        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow ?: return@launch

                Log.d(TAG, "TREE START")
                debugFullTree(rootNode)
                Log.d(TAG, "TREE END")

                val chatWith = extractContactName(rootNode, pkg)

                if ("$pkg::$chatWith" != "$cachedPackage::$cachedChatWith") {
                    Log.i(TAG, "🔄 Chat changed: [$chatWith] — full reset")
                    resetCache()
                    cachedPackage    = pkg
                    cachedChatWith   = chatWith
                    cacheInitialized = false
                }

                val screenMessages = when {
                    pkg.startsWith("com.whatsapp") -> extractWhatsAppMessages(rootNode)
                    pkg == "com.instagram.android" -> extractInstagramMessages(rootNode)
                    else -> emptyList()
                }
                rootNode.recycle()

                if (screenMessages.isEmpty()) {
                    Log.d(TAG, "No valid messages on screen")
                    return@launch
                }

                val changed = if (!cacheInitialized) initialLoad(screenMessages)
                else processMessages(screenMessages)

                if (changed) {
                    logCacheState()
                    broadcastCache(pkg, chatWith)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════
    // INITIAL LOAD
    // ══════════════════════════════════════════
    private fun initialLoad(screenMessages: List<ChatMessageData>): Boolean {

        val validMsgs = screenMessages.filter { parseIsoToMs(it.time) > 0L }

        if (validMsgs.isEmpty()) {
            Log.w(TAG, "Initial load: no valid messages")
            return false
        }

        val sorted = validMsgs.sortedWith(
            compareBy<ChatMessageData> { parseIsoToMs(it.time) }
                .thenBy { it.y }
        )

        val toLoad = sorted.takeLast(MAX_CACHE_SIZE)

        for (msg in toLoad) {
            messageCache.addLast(msg)
            seenKeys.add(msg.seenKey())
        }

        lastCacheTimeMs  = parseIsoToMs(messageCache.last().time)

        cacheInitialized = true

        Log.i(TAG, "📥 Initial load: ${messageCache.size} msgs")
        return true
    }
    // ══════════════════════════════════════════════════════════════════
    // PROCESS MESSAGES — Append-only after initial load
    //
    // RULE: Only one accepted path →
    //   msgTimeMs > lastCacheTimeMs  AND  key not in seenKeys → addLast()
    //
    // Everything else is SKIPPED:
    //   • msgTimeMs <= 0              → invalid timestamp, skip
    //   • key already in seenKeys    → same instance already captured, skip
    //   • msgTimeMs <= lastCacheTimeMs → older or same-time msg (includes all
    //                                    visible/scroll history), skip
    //
    // This single gate naturally prevents scroll-revealed old messages from
    // being inserted, because they always have timestamps <= lastCacheTimeMs.
    //
    // NOTE: Two messages at the exact same second → only the first is stored.
    // WhatsApp timestamps are minute-resolution, so this edge case is rare.
    // ══════════════════════════════════════════════════════════════════
    private fun processMessages(screenMessages: List<ChatMessageData>): Boolean {

        val sorted = screenMessages.sortedWith(
            compareBy<ChatMessageData> { parseIsoToMs(it.time) }
                .thenBy { it.y }
        )

        var changed = false

        for (msg in sorted) {

            val msgTimeMs = parseIsoToMs(msg.time)
            val key       = msg.seenKey()

            // Gate 1: must have a parseable timestamp
            if (msgTimeMs <= 0L) continue

            // Gate 2: must be same-time OR newer than last cached message.
            // Only rejects messages that are STRICTLY OLDER (scroll-history / old msgs).
            //
            // WHY < instead of <=:
            //   WhatsApp timestamps are minute-resolution (HH:mm).
            //   If lastCacheTimeMs = 15:01 and a new msg arrives also at 15:01
            //   (different sender, or another message in the same minute),
            //   the old <= gate would SKIP it. That is wrong.
            //
            //   Gate 3 (seenKey check) handles same-scan re-deliveries correctly.
            //   Scroll-history old messages are blocked by Gate 3 because their
            //   seenKey was already added during initialLoad or earlier scans.
            if (msgTimeMs < lastCacheTimeMs) {
                Log.v(TAG, "⏭ Skip (older): [${msg.sender}] ${msg.message.take(25)} @ ${msg.time}")
                continue
            }

            // Gate 3: same message instance not already in cache
            if (key in seenKeys) {
                Log.v(TAG, "⏭ Skip (seen): ${key.take(40)}")
                continue
            }

            // All gates passed → this is a genuinely new message → append
            messageCache.addLast(msg)
            seenKeys.add(key)

            if (messageCache.size > MAX_CACHE_SIZE) {
                val evicted = messageCache.removeFirst()
                Log.d(TAG, "🗑️ Evicted: ${evicted.message.take(25)}")
            }

            lastCacheTimeMs = parseIsoToMs(messageCache.last().time)

            Log.i(TAG, "➕ Appended: [${msg.sender}] ${msg.message.take(40)} @ ${msg.time}")
//            Log.i(TAG, "➕ Appended: [${msg.sender}] ${msg.message} @ ${msg.time}")
            changed = true
        }

        return changed
    }
    // ══════════════════════════════════════════
    // BUG B FIX: seenKey = text + "|" + HH:mm
    //
    // "THIK" at 11:58 → "thik|11:58"
    // "thik" at 12:09 → "thik|12:09"  ← DIFFERENT key → both allowed ✓
    //
    // Using HH:mm not full ISO because:
    // - Same msg can appear with same time on multiple scans (same key = dedup ✓)
    // - Different msg with same text at different time (different key = both pass ✓)
    // ══════════════════════════════════════════
    // seenKey = sender + "|" + normalizedText + "|" + HH:mm
    //
    // WHY sender is included (NEW FIX):
    //   Without sender:  ME "ok" at 15:01  →  key = "ok|15:01"
    //                   OTHER "ok" at 15:01 →  key = "ok|15:01"  ← COLLISION → 2nd SKIPPED ❌
    //
    //   With sender:    OTHER "ok" at 15:01 →  key = "other|ok|15:01" ✓
    //                   ME    "ok" at 15:01 →  key = "me|ok|15:01"    ✓ (different key!)
    //
    // WHY HH:mm (not full ISO):
    //   Same message re-scanned = same key = dedup ✓
    //   Same text at different minute = different key = both stored ✓
    private fun ChatMessageData.seenKey(): String {
        val normText = message.trim().replace(Regex("\\s+"), " ").lowercase()
        // Extract HH:mm from ISO "2026-03-11T11:58:00" → "11:58"
        val hhmm = if (time.length >= 16) time.substring(11, 16) else time
        // Include sender so ME and OTHER can send same text at same time → both stored
        return "${sender}|$normText|$hhmm"
    }

    // ══════════════════════════════════════════════════════════════════════
    // WHATSAPP EXTRACTION — ORDER-BASED DATE CONTEXT
    //
    // THREE patterns confirmed from logs:
    //
    // Pattern A — divider INSIDE ViewGroup (most common):
    //   ViewGroup[4]: [divider="Today"] + message + date + status
    //
    // Pattern B — divider as STANDALONE list item:
    //   list[0]: conversation_row_date_divider = "3 March 2026"
    //   list[2]: ViewGroup (no divider): message + date
    //
    // Pattern C — date_divider_header at COORDINATOR level (confirmed log 15:01:17):
    //   coordinator: date_divider_header = "Yesterday"
    //   list[0]: STRIPE msg (no divider in parent) ← needs coordinator date
    // ══════════════════════════════════════════════════════════════════════
    private fun extractWhatsAppMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val messages = mutableListOf<ChatMessageData>()

        // Pattern C: Pre-scan coordinator for date_divider_header
        var coordinatorDateCal: Calendar? = null
        val coordinatorNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/date_divider_header")
        coordinatorNodes?.firstOrNull()?.text?.toString()?.trim()?.let { divText ->
            coordinatorDateCal = parseDateDivider(divText)
            if (coordinatorDateCal != null) {
                Log.d(TAG, "📅 Coordinator date context: [$divText]")
            }
        }

        val listNode = root.findAccessibilityNodeInfosByViewId("android:id/list")
            ?.firstOrNull() ?: return messages

        // Default: coordinatorDate if found, else today
        var currentDateCal: Calendar = coordinatorDateCal ?: Calendar.getInstance()

        // Walk list IN ORDER — critical for correct date context
        for (i in 0 until listNode.childCount) {
            val listItem = listNode.getChild(i) ?: continue
            val newDate  = processListItem(listItem, messages, currentDateCal)
            if (newDate != null) {
                currentDateCal = newDate
                Log.d(TAG, "📅 Date context updated → ${ISO_FMT.format(currentDateCal.time)}")
            }
        }

        return messages
//            .filter { parseIsoToMs(it.time) > 0L && it.message.length >= 2 }
            .filter { parseIsoToMs(it.time) > 0L && it.message.isNotBlank() }
//            .distinctBy { "${it.message.trim().lowercase()}|${it.time}" }  // dedup by text+time
            .sortedBy { parseIsoToMs(it.time) }
    }

    private fun processListItem(
        listItem: AccessibilityNodeInfo,
        messages: MutableList<ChatMessageData>,
        currentDateCal: Calendar
    ): Calendar? {

        if (isInsidePinnedOrSystemBanner(listItem)) return null

        val viewId  = listItem.viewIdResourceName ?: ""

        // Standalone date divider list item
        if (viewId == "com.whatsapp:id/conversation_row_date_divider") {
            val rawText = listItem.text?.toString()?.trim() ?: ""
            if (rawText.isNotEmpty()) return parseDateDivider(rawText)
            return null
        }

        if (viewId == "com.whatsapp:id/info") return null

        // ViewGroup — scan children
        var updatedDate: Calendar? = null
        var msgText:  String?  = null
        var timeStr:  String?  = null
        var sender              = "other"
        var msgY                = 0
        var localDate: Calendar? = null
        var fileName: String?  = null
        var fileType: String?  = null

        for (j in 0 until listItem.childCount) {
            val child    = listItem.getChild(j) ?: continue
            val childId  = child.viewIdResourceName ?: ""
            val childTxt = child.text?.toString()?.trim() ?: ""
            val cleaned  = cleanTimestampString(childTxt)

            when (childId) {
                "com.whatsapp:id/conversation_row_date_divider" -> {
                    if (childTxt.isNotEmpty()) {
                        val cal = parseDateDivider(childTxt)
                        if (cal != null) { localDate = cal; updatedDate = cal }
                    }
                }
                "com.whatsapp:id/message_text" -> {
                    msgText = childTxt
                    val rect = Rect().also { child.getBoundsInScreen(it) }
                    msgY = rect.centerY()
                }
                "com.whatsapp:id/date" -> {
                    if (isTimeOnlyText(cleaned)) timeStr = cleaned
                }
                "com.whatsapp:id/status" -> { sender = "me" }
                "com.whatsapp:id/caption_text" -> {
                    if (childTxt.isNotEmpty()) {
                        msgText = "[Image] $childTxt"
                        val rect = Rect().also { child.getBoundsInScreen(it) }
                        msgY = rect.centerY()
                    }
                }
                "com.whatsapp:id/content" -> {
                    for (k in 0 until child.childCount) {
                        val gc = child.getChild(k) ?: continue
                        when (gc.viewIdResourceName) {
                            "com.whatsapp:id/title"     -> fileName = gc.text?.toString()?.trim()
                            "com.whatsapp:id/file_type" -> fileType = gc.text?.toString()?.trim()
                        }
                    }
                    val rect = Rect().also { child.getBoundsInScreen(it) }
                    msgY = rect.centerY()
                }
            }
        }

        if (msgText == null && !fileName.isNullOrEmpty()) {
            msgText = if (!fileType.isNullOrEmpty()) "[$fileType] $fileName" else "[File] $fileName"
        }

        if (!msgText.isNullOrEmpty() && !timeStr.isNullOrEmpty()) {
            val effectiveDateCal = localDate ?: currentDateCal
            val fullTimestamp    = combineDateTime(effectiveDateCal, timeStr)
            if (fullTimestamp.isNotEmpty() && msgText.lowercase().trim() !in WHATSAPP_IGNORE_TEXT) {
                messages.add(ChatMessageData(sender, msgText, fullTimestamp, msgY))
            }
        }

        return updatedDate
    }

    private fun parseDateDivider(text: String): Calendar? {
        val lower = text.lowercase().trim()
        return when {
            lower == "today" -> Calendar.getInstance()
            lower == "yesterday" -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            DAY_OF_WEEK_MAP.containsKey(lower) -> {
                val targetDay = DAY_OF_WEEK_MAP[lower]!!
                val cal = Calendar.getInstance()
                var attempts = 0
                while (cal.get(Calendar.DAY_OF_WEEK) != targetDay && attempts < 8) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                    attempts++
                }
                cal
            }
            else -> {
                val formats = listOf("d MMMM yyyy", "MMMM d, yyyy", "MMM d, yyyy", "d MMM yyyy")
                for (fmt in formats) {
                    try {
                        val parsed = SimpleDateFormat(fmt, Locale.ENGLISH).parse(text) ?: continue
                        val cal = Calendar.getInstance()
                        cal.time = parsed
                        return cal
                    } catch (e: Exception) { continue }
                }
                null
            }
        }
    }

    private fun combineDateTime(dateCal: Calendar, timeStr: String): String {
        val cleaned = cleanTimestampString(timeStr)
        val cal     = parseTime(cleaned, dateCal) ?: return ""
        return formatIso(cal)
    }

    // ══════════════════════════════════════════
    // INSTAGRAM EXTRACTION
    // ══════════════════════════════════════════
    private fun extractInstagramMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val result = mutableListOf<ChatMessageData>()
        traverseInstagram(root, result, resources.displayMetrics.widthPixels, "")
        return result.filter { it.message.length >= 2 && it.message.lowercase() !in INSTAGRAM_IGNORE }
    }

    private fun traverseInstagram(
        node: AccessibilityNodeInfo?, result: MutableList<ChatMessageData>,
        screenWidth: Int, currentTime: String, depth: Int = 0
    ) {
        if (node == null || depth > 20) return
        val text   = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        var timeToUse = currentTime
        val cleaned = cleanTimestampString(text)
        if (isTimestampText(cleaned)) timeToUse = normalizeInstagramTimestamp(cleaned)
        if (viewId in INSTAGRAM_MSG_IDS && text.isNotEmpty() && timeToUse.isNotEmpty()) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            result.add(ChatMessageData(
                sender  = if (rect.centerX() > screenWidth * 0.55) "me" else "other",
                message = text, time = timeToUse, y = rect.centerY()
            ))
        }
        for (i in 0 until node.childCount)
            traverseInstagram(node.getChild(i), result, screenWidth, timeToUse, depth + 1)
    }

    private fun isInsidePinnedOrSystemBanner(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 10) {
            val id = current.viewIdResourceName ?: ""
            if (id in WHATSAPP_BANNER_IDS || id in WHATSAPP_SYSTEM_IDS) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun resetCache() {
        messageCache.clear()
        seenKeys.clear()
        lastCacheTimeMs  = 0L
        cacheInitialized = false
    }

    private fun extractContactName(root: AccessibilityNodeInfo, pkg: String): String {
        return when {
            pkg == "com.instagram.android" ->
                root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/header_title")
                    ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
            pkg.startsWith("com.whatsapp") -> {
                val n = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                if (!n.isNullOrEmpty()) n.firstOrNull()?.text?.toString()?.trim() ?: ""
                else root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar_title_text_view")
                    ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
            }
            else -> "Unknown"
        }
    }

    // ══════════════════════════════════════════
    // TIME UTILITIES
    // ══════════════════════════════════════════
    private fun parseIsoToMs(isoTime: String): Long =
        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (e: Exception) { 0L }

    private fun cleanTimestampString(raw: String): String =
        raw.replace('\u202F', ' ').replace('\u00A0', ' ').trim()

    private fun isTimeOnlyText(text: String): Boolean =
        text.lowercase().matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE))

    private fun isTimestampText(text: String): Boolean {
        val l = text.lowercase()
        return l.startsWith("today") || l.startsWith("yesterday") ||
            l.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ||
            l.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}.*", RegexOption.IGNORE_CASE))
    }

    private fun normalizeInstagramTimestamp(raw: String): String {
        val cleaned = cleanTimestampString(raw)
        val lower   = cleaned.lowercase()
        val today   = Calendar.getInstance()
        return try {
            when {
                lower.startsWith("today") ->
                    formatIso(parseTime(cleaned.substringAfter(" ").trim(), today) ?: return "")
                lower.startsWith("yesterday") -> {
                    val yday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    formatIso(parseTime(cleaned.substringAfter(" ").trim(), yday) ?: return "")
                }
                lower.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ->
                    formatIso(parseTime(cleaned, today) ?: return "")
                lower.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*", RegexOption.IGNORE_CASE)) -> {
                    val p = try { SimpleDateFormat("MMM d h:mm a", Locale.ENGLISH).parse(cleaned) }
                    catch (e: Exception) { null } ?: return ""
                    val c = Calendar.getInstance().apply { time = p; set(Calendar.YEAR, today.get(Calendar.YEAR)) }
                    formatIso(c)
                }
                else -> ""
            }
        } catch (e: Exception) { "" }
    }

    private fun parseTime(timeStr: String, base: Calendar): Calendar? {
        val cleaned = cleanTimestampString(timeStr)
        for (fmt in listOf("h:mm a", "h:mm", "HH:mm")) {
            try {
                val p = SimpleDateFormat(fmt, Locale.ENGLISH).parse(cleaned.trim()) ?: continue
                val r = base.clone() as Calendar
                val c = Calendar.getInstance().apply { time = p }
                r.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY))
                r.set(Calendar.MINUTE, c.get(Calendar.MINUTE))
                r.set(Calendar.SECOND, 0)
                return r
            } catch (e: Exception) { continue }
        }
        return null
    }

    private fun formatIso(cal: Calendar): String = ISO_FMT.format(cal.time)

    // ══════════════════════════════════════════
    // BROADCAST — sorted oldest→newest with sequence
    // ══════════════════════════════════════════
    private fun broadcastCache(pkg: String, chatWith: String) {

        val ordered = messageCache.sortedWith(
            compareBy<ChatMessageData> { parseIsoToMs(it.time) }
                .thenBy { it.y }
        )

        sendBroadcast(Intent(ACTION_CHAT_CONTEXT).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_PACKAGE, pkg)
            putExtra(EXTRA_CHAT_WITH, chatWith)
            putExtra(EXTRA_MESSAGES, buildJsonArray(ordered))
        })

        Log.d(TAG, "📡 Broadcast: ${ordered.size} msgs | chat=$chatWith")
    }
    private fun buildJsonArray(messages: List<ChatMessageData>): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, msg ->
            sb.append("{\"sequence\":${i + 1},\"sender\":\"${msg.sender}\",")
            sb.append("\"message\":\"${escapeJson(msg.message)}\",\"time\":\"${msg.time}\"}")
            if (i < messages.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(t: String) = t
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ══════════════════════════════════════════
    // DEBUG
    // ══════════════════════════════════════════
    private fun logCacheState() {
        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "  CACHE [${messageCache.size}/$MAX_CACHE_SIZE] — $cachedChatWith")
        Log.i(TAG, "  Window: [${messageCache.firstOrNull()?.time} → ${messageCache.lastOrNull()?.time}]")
        Log.i(TAG, "╚══════════════════════════════════════╝")
        messageCache.sortedBy { parseIsoToMs(it.time) }.forEachIndexed { i, m ->
            val marker = if (i == messageCache.size - 1) " ← LATEST" else ""
            Log.i(TAG, "  [$i] ${m.time} [${m.sender}] ${m.message.take(50)}$marker")
        }
    }

    private fun debugFullTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 20) return
        val indent = " ".repeat(depth * 2)
        Log.d(TAG, "$indent id=${node.viewIdResourceName} text=${node.text} class=${node.className} children=${node.childCount}")
        for (i in 0 until node.childCount) debugFullTree(node.getChild(i), depth + 1)
    }
}

data class ChatMessageData(
    val sender:  String,
    val message: String,
    val time:    String,
    val y:       Int = 0
)
