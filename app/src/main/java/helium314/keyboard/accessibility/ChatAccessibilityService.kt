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
 * ChatAccessibilityService — Hybrid Cache System
 *
 * Strategy: Text-Dedup + Window-Range + AtBottom Detection
 *
 * RULE SET:
 *
 * A) Initial Load (cache empty):
 *    - Screen ke LAST 15 msgs lo (time sorted, newest = last)
 *    - seenKeys mein sabka normalizedText daalo
 *    - firstCacheTime = index[0].time, lastCacheTime = index[last].time
 *
 * B) After initial load — new msg check:
 *    - msg.normalizedText already in seenKeys? → BLOCK (text dedup)
 *    - msg.time < firstCacheTime? → BLOCK (scroll history, window range)
 *    - msg.time in range [firstCacheTime, lastCacheTime]? → BLOCK (middle zone)
 *    - msg.time > lastCacheTime? → ADD (genuinely new message)
 *
 * C) Multi-line nodes:
 *    - Text normalize: trim + collapse whitespace
 *
 * D) Chat switch:
 *    - pkg + chatWith change → full reset
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

        private const val DEBOUNCE_MS = 1500L

        const val ACTION_CHAT_CONTEXT = "helium314.keyboard.CHAT_CONTEXT"
        const val EXTRA_MESSAGES      = "chat_messages_json"
        const val EXTRA_PACKAGE       = "source_package"
        const val EXTRA_CHAT_WITH     = "chat_with"

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

        private val INSTAGRAM_IGNORE = setOf(
            "message", "message…", "typing…", "typing",
            "seen", "delivered", "active now", "active today",
            "need to fix a typo?",
            "you can edit a message for up to 15 minutes. tap and hold a message to start editing.",
            "view transcription", "inquire", "view profile",
            "send message", "send a message", "voice message",
            "react to this message", "reply"
        )

        private val WHATSAPP_IGNORE = setOf(
            "type a message", "type a message…",
            "typing…", "typing", "online", "offline",
            "last seen", "last seen recently",
            "voice message", "this message was deleted",
            "you deleted this message",
            "missed voice call", "missed video call",
            "tap to call back", "seen", "delivered", "sent",
            "yesterday", "today", "attach", "emoji", "sticker",
            "gif", "camera", "audio"
        )

        private val WHATSAPP_MSG_IDS = setOf(
            "com.whatsapp:id/message_text",
            "com.whatsapp:id/caption_text"
        )

        private val INSTAGRAM_MSG_IDS = setOf(
            "com.instagram.android:id/direct_text_message_text_view"
        )
    }

    // ══════════════════════════════════════════
    // CACHE STATE
    // ══════════════════════════════════════════

    // Main sliding window — index 0 = oldest, last = newest
    private val messageCache = ArrayDeque<ChatMessageData>()

    // Text-based dedup set — normalizedText only (sender excluded, see WHY below)
    // WHY no sender: WhatsApp sender = screen X-position, changes on scroll/resize
    // So "me::hello" and "other::hello" would be different keys = duplicate added
    private val seenKeys = LinkedHashSet<String>()

    // Window boundary timestamps (milliseconds)
    // firstCacheTime = time of oldest msg in cache
    // lastCacheTime  = time of newest msg in cache
    // Any message with time in (firstCacheTime, lastCacheTime) = middle zone = IGNORE
    // Any message with time < firstCacheTime = scroll history = IGNORE
    // Any message with time > lastCacheTime  = genuinely new = ADD
    private var firstCacheTimeMs = Long.MAX_VALUE
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
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
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
    // MAIN EVENT
    // ══════════════════════════════════════════
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) return
        lastProcessedTime = now

        serviceScope.launch {
            val rootNode = rootInActiveWindow ?: return@launch

            // 🔎 DEBUG UI TREE
            debugTree(rootNode)
            try {
                val rootNode = rootInActiveWindow ?: return@launch
                val chatWith = extractContactName(rootNode, pkg)

                // Chat switch → full reset
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

                if (screenMessages.isEmpty()) return@launch

                val changed = if (!cacheInitialized) {
                    initialLoad(screenMessages)
                } else {
                    processNewMessages(screenMessages)
                }

                if (changed) {
                    logCacheState()
                    broadcastCache(pkg, chatWith)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // INITIAL LOAD
    // Chat open hote hi ek baar, pehla scan
    //
    // Steps:
    // 1. Sort by time (oldest → newest)
    // 2. takeLast(15) — newest 15 lo
    // 3. seenKeys mein normalizedText daalo
    // 4. firstCacheTimeMs aur lastCacheTimeMs set karo
    // ══════════════════════════════════════════════════════════════
    private fun initialLoad(screenMessages: List<ChatMessageData>): Boolean {
        val sorted = screenMessages.sortedBy { parseIsoToMs(it.time) }
        val toLoad = sorted.takeLast(MAX_CACHE_SIZE)

        for (msg in toLoad) {
            messageCache.addLast(msg)
            seenKeys.add(msg.normalizedText())
        }

        if (messageCache.isNotEmpty()) {
            firstCacheTimeMs = parseIsoToMs(messageCache.first().time)
            lastCacheTimeMs  = parseIsoToMs(messageCache.last().time)
        }

        cacheInitialized = true
        Log.i(TAG, "📥 Initial load: ${messageCache.size} msgs | window [${messageCache.firstOrNull()?.time} → ${messageCache.lastOrNull()?.time}]")
        return messageCache.isNotEmpty()
    }

    // ══════════════════════════════════════════════════════════════
    // PROCESS NEW MESSAGES (after initial load)
    //
    // For each screen message, apply 3-layer filter:
    //
    // Layer 1 — Text Dedup:
    //   normalizedText in seenKeys? → BLOCK
    //   (handles: scroll-up duplicates, debounce re-scans, sender flip)
    //
    // Layer 2 — Window Range:
    //   msg.time <= lastCacheTimeMs? → BLOCK
    //   (handles: middle zone, scroll history, any old msg)
    //   Only msg.time > lastCacheTimeMs passes through
    //
    // Layer 3 — Add:
    //   Passed both checks → genuinely new → addLast
    //   Evict oldest if size > 15
    //   Update lastCacheTimeMs
    // ══════════════════════════════════════════════════════════════
    private fun processNewMessages(screenMessages: List<ChatMessageData>): Boolean {
        val sorted = screenMessages.sortedBy { parseIsoToMs(it.time) }
        var changed = false

        for (msg in sorted) {
            val msgTimeMs = parseIsoToMs(msg.time)
            val normText  = msg.normalizedText()

            // Layer 1: Text dedup
            if (normText in seenKeys) {
                Log.v(TAG, "⏭ Skip (text seen): ${normText.take(30)}")
                continue
            }

            // Layer 2: Window range — only strictly newer than lastCacheTimeMs
            if (msgTimeMs <= lastCacheTimeMs) {
                Log.v(TAG, "⏭ Skip (old/same time): ${msg.time} <= $lastCacheTimeMs")
                continue
            }

            // Layer 3: This is a genuinely new message
            messageCache.addLast(msg)
            seenKeys.add(normText)

            if (messageCache.size > MAX_CACHE_SIZE) {
                val evicted = messageCache.removeFirst()
                // Update firstCacheTimeMs after eviction
                firstCacheTimeMs = parseIsoToMs(messageCache.first().time)
                Log.d(TAG, "🗑️ Evicted: [${evicted.sender}] ${evicted.message.take(25)}")
            }

            lastCacheTimeMs = msgTimeMs
            Log.i(TAG, "➕ New: [${msg.sender}] ${msg.message.take(40)} @ ${msg.time}")
            changed = true
        }

        return changed
    }

    // ══════════════════════════════════════════
    // NORMALIZED TEXT
    // Multi-line, extra spaces sab normalize ho jate hain
    // "Hello  world\nnext line" → "hello world next line"
    // ══════════════════════════════════════════
    private fun ChatMessageData.normalizedText(): String =
        message.trim()
            .replace(Regex("\\s+"), " ")   // collapse whitespace/newlines
            .lowercase()

    // ══════════════════════════════════════════
    // RESET CACHE
    // ══════════════════════════════════════════
    private fun resetCache() {
        messageCache.clear()
        seenKeys.clear()
        firstCacheTimeMs = Long.MAX_VALUE
        lastCacheTimeMs  = 0L
        cacheInitialized = false
    }

    // ══════════════════════════════════════════
    // TIME PARSER
    // ══════════════════════════════════════════
    private fun parseIsoToMs(isoTime: String): Long =
        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (e: Exception) { 0L }

    // ══════════════════════════════════════════
    // CONTACT NAME
    // ══════════════════════════════════════════
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
    // INSTAGRAM EXTRACTION
    // ══════════════════════════════════════════
    private fun extractInstagramMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val result = mutableListOf<ChatMessageData>()
        traverseInstagram(root, result, resources.displayMetrics.widthPixels, "")
        return result.filter { isValidMessage(it.message, INSTAGRAM_IGNORE) }
    }

    private fun traverseInstagram(
        node: AccessibilityNodeInfo?, result: MutableList<ChatMessageData>,
        screenWidth: Int, currentTime: String, depth: Int = 0
    ) {
        if (node == null || depth > 20) return
        val text   = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        var timeToUse = currentTime
        if (isTimestampText(text)) timeToUse = normalizeTimestamp(text)
        if (viewId in INSTAGRAM_MSG_IDS && text.isNotEmpty()) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            result.add(ChatMessageData(
                sender  = if (rect.centerX() > screenWidth * 0.55) "me" else "other",
                message = text,
                time    = timeToUse.ifEmpty { getCurrentIsoTime() }
            ))
        }
        for (i in 0 until node.childCount)
            traverseInstagram(node.getChild(i), result, screenWidth, timeToUse, depth + 1)
    }

    // ══════════════════════════════════════════
    // WHATSAPP EXTRACTION
    // ══════════════════════════════════════════
    private fun extractWhatsAppMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {

        val messages = mutableListOf<ChatMessageData>()
        val screenWidth = resources.displayMetrics.widthPixels

        val nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")

        for(node in nodes){

            val text = node.text?.toString()?.trim() ?: continue
            if(text.isEmpty()) continue

            val parent = node.parent ?: continue

            var timestamp:String? = null

            for(i in 0 until parent.childCount){
                val child = parent.getChild(i) ?: continue
                val childText = child.text?.toString()?.trim() ?: continue

                if(isTimestampText(childText)){
                    timestamp = normalizeTimestamp(childText)
                    break
                }
            }

            if(timestamp == null) continue

            val rect = Rect()
            node.getBoundsInScreen(rect)

            val sender =
                if(rect.centerX() > screenWidth * 0.6) "me"
                else "other"

            messages.add(
                ChatMessageData(
                    sender,
                    text,
                    timestamp
                )
            )
        }

        return messages
    }

    private fun traverseWhatsApp(
        node: AccessibilityNodeInfo?, result: MutableList<ChatMessageData>,
        screenWidth: Int, currentTime: String = "", depth: Int = 0
    ) {
        if (node == null || depth > 20) return
        val text   = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        var timeToUse = currentTime
        if (isTimestampText(text)) timeToUse = normalizeTimestamp(text)
        if (viewId in WHATSAPP_MSG_IDS && text.isNotEmpty()) {
            val rect = Rect().also { node.getBoundsInScreen(it) }
            result.add(ChatMessageData(
                sender  = if (rect.centerX() > screenWidth * 0.55) "me" else "other",
                message = text,
                time    = timeToUse.ifEmpty { getCurrentIsoTime() }
            ))
        }
        for (i in 0 until node.childCount)
            traverseWhatsApp(node.getChild(i), result, screenWidth, timeToUse, depth + 1)
    }

    // ══════════════════════════════════════════
    // VALID MESSAGE CHECK
    // ══════════════════════════════════════════
    private fun isValidMessage(text: String, ignoreSet: Set<String>): Boolean {
        if (text.length < 2) return false
        if (text.lowercase().trim() in ignoreSet) return false
        if (isTimestampText(text)) return false
        if (text.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|AM|pm|PM))?"))) return false
        return true
    }

    // ══════════════════════════════════════════
    // TIMESTAMP
    // ══════════════════════════════════════════
    private fun isTimestampText(text: String): Boolean {
        val l = text.lowercase()
        return l.startsWith("today") || l.startsWith("yesterday") ||
            l.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ||
            l.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}.*", RegexOption.IGNORE_CASE))
    }

    private fun normalizeTimestamp(raw: String): String {
        val lower = raw.lowercase().trim()
        val today = Calendar.getInstance()
        return try {
            when {
                lower.startsWith("today") ->
                    formatIso(parseTime(raw.substringAfter(" ").trim(), today) ?: today)
                lower.startsWith("yesterday") -> {
                    val yday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    formatIso(parseTime(raw.substringAfter(" ").trim(), yday) ?: yday)
                }
                lower.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*", RegexOption.IGNORE_CASE)) -> {
                    val p = try { SimpleDateFormat("MMM d h:mm a", Locale.ENGLISH).parse(raw) } catch (e: Exception) { null }
                    if (p != null) {
                        val c = Calendar.getInstance().apply { time = p; set(Calendar.YEAR, today.get(Calendar.YEAR)) }
                        formatIso(c)
                    } else getCurrentIsoTime()
                }
                lower.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ->
                    formatIso(parseTime(raw, today) ?: today)
                else -> getCurrentIsoTime()
            }
        } catch (e: Exception) { getCurrentIsoTime() }
    }

    private fun parseTime(timeStr: String, base: Calendar): Calendar? {
        for (fmt in listOf("h:mm a", "h:mm", "HH:mm")) {
            try {
                val p = SimpleDateFormat(fmt, Locale.ENGLISH).parse(timeStr.trim()) ?: continue
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

    private fun formatIso(cal: Calendar) = ISO_FMT.format(cal.time)
    private fun getCurrentIsoTime()      = ISO_FMT.format(Date())


    private fun debugTree(node: AccessibilityNodeInfo?, depth:Int=0){

        if(node==null) return

        val indent = " ".repeat(depth*2)

        Log.d(TAG,
            "$indent node=" +
                " id=${node.viewIdResourceName}" +
                " text=${node.text}"
        )

        for(i in 0 until node.childCount){
            debugTree(node.getChild(i),depth+1)
        }
    }

    // ══════════════════════════════════════════
    // BROADCAST
    // ══════════════════════════════════════════
    private fun broadcastCache(pkg: String, chatWith: String) {
        val json = buildJsonArray(messageCache.toList())
        sendBroadcast(Intent(ACTION_CHAT_CONTEXT).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_PACKAGE,   pkg)
            putExtra(EXTRA_CHAT_WITH, chatWith)
            putExtra(EXTRA_MESSAGES,  json)
        })
        Log.d(TAG, "📡 Broadcast: ${messageCache.size} msgs | chat=$chatWith")
    }

    private fun buildJsonArray(messages: List<ChatMessageData>): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, msg ->
            sb.append("{")
            sb.append("\"sender\":\"${msg.sender}\",")
            sb.append("\"message\":\"${escapeJson(msg.message)}\",")
            sb.append("\"time\":\"${msg.time}\"")
            sb.append("}")
            if (i < messages.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(t: String) = t
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ══════════════════════════════════════════
    // DEBUG LOG
    // ══════════════════════════════════════════
    private fun logCacheState() {
        Log.i(TAG, "╔══════════════════════════════════════╗")
        Log.i(TAG, "  CACHE [${messageCache.size}/$MAX_CACHE_SIZE] — $cachedChatWith")
        Log.i(TAG, "  Window: [${messageCache.firstOrNull()?.time} → ${messageCache.lastOrNull()?.time}]")
        Log.i(TAG, "╚══════════════════════════════════════╝")
        messageCache.forEachIndexed { i, m ->
            val isLast = i == messageCache.size - 1
            Log.i(TAG, "  [$i] ${m.time} [${m.sender}] ${m.message.take(45)}${if (isLast) " ← LATEST" else ""}")
        }
    }
}

data class ChatMessageData(
    val sender:  String,
    val message: String,
    val time:    String
)

