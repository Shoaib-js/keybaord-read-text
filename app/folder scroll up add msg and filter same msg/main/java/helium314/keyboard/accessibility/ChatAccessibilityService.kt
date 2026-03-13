package helium314.keyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * ChatAccessibilityService — v6 HYBRID ARCHITECTURE
 *
 * ═══════════════════════════════════════════════════════════════
 * NEW IN v6: NotificationListenerService Integration
 * ═══════════════════════════════════════════════════════════════
 *
 * v5 problem: New message while keyboard is open and chat is visible
 *   → No scroll / window event fires
 *   → AI context NOT updated until user scrolls or switches tab
 *
 * v6 fix: Hybrid trigger model with 3 message sources:
 *
 *   SOURCE 1 — Screen snapshot (TYPE_WINDOW_CONTENT_CHANGED)
 *     When: Chat opened, message sent, UI update
 *     Action: Full accessibility extraction → merge into timeline
 *
 *   SOURCE 2 — Scroll history (TYPE_VIEW_SCROLLED)
 *     When: User scrolls up through old messages
 *     Action: Old messages inserted at top of timeline
 *
 *   SOURCE 3 — Notification trigger (WhatsAppNotificationService)
 *     When: New WhatsApp/Instagram message arrives (even in foreground)
 *     Action: forceRescan() → extract → append to bottom of timeline
 *             AI context updated immediately ← THIS IS THE NEW PART
 *
 * ═══════════════════════════════════════════════════════════════
 * TWO-LIST ARCHITECTURE (unchanged from v5):
 * ═══════════════════════════════════════════════════════════════
 *
 *   List 1 — messageCache (TreeMap, MAX_HISTORY_SIZE = 200)
 *     Full buffer of all captured messages for current chat.
 *
 *   List 2 — sendMessages (derived on demand)
 *     messageCache.values.takeLast(MAX_CONTEXT_SIZE = 15)
 *     Newest 15 msgs. Newest is always LAST. Sent to AI on broadcast.
 *
 * ═══════════════════════════════════════════════════════════════
 * TIMELINE ENGINE:
 * ═══════════════════════════════════════════════════════════════
 *   Key = timestampMs * 1000 + insertionIndex
 *   TreeMap auto-sorts → always chronological
 *   Same-time messages → insertionIndex preserves tree order
 *   Y-coordinate NEVER used for ordering
 *   seenKey = "sender|normalizedText|fullISO" → no duplicates
 *
 * ═══════════════════════════════════════════════════════════════
 * ALL BUGS FROM v1–v5 STILL FIXED:
 * ═══════════════════════════════════════════════════════════════
 *   BUG #1  Timestamp window impossible       FIX: TreeMap firstKey/lastKey
 *   BUG #2  addFirst/addLast order corrupt    FIX: TreeMap.put()
 *   BUG #3  Window [day N → day N-1]          FIX: TreeMap
 *   BUG #4  seenKey same-minute collision     FIX: full ISO in key
 *   BUG #5  Overflow removes wrong msg        FIX: pollFirstEntry()
 *   BUG #6  JSON sequence wrong               FIX: TreeMap already sorted
 *   BUG #7  "Tuesday" off by 1 day            FIX: after(today) → -7 days
 *   BUG #8  addFirst for non-oldest msgs      FIX: TreeMap
 *   BUG #9  Y-coordinate sort unreliable      FIX: removed entirely
 *   BUG #10 DEBOUNCE_MS 1500 too slow         FIX: 500ms
 *   FIX F   isTimeOnlyText too strict         FIX: Regex.find()
 *   FIX G   Silent message drops              FIX: skip reason logged
 *   FIX H   Invisible Unicode in timestamps   FIX: cleanTimestampString()
 *   FIX I   IGNORE_TEXT too broad             FIX: removed broad terms
 * ═══════════════════════════════════════════════════════════════
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "CHAT_DEBUG"

        // ── Two-List constants ──────────────────────────────────────────
        const val MAX_HISTORY_SIZE = 200   // List 1: full history buffer
        const val MAX_CONTEXT_SIZE = 15    // List 2: AI context window
        // ───────────────────────────────────────────────────────────────

        // Set false before release
        private const val DEBUG_TREE_ENABLED = true

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android"
        )

        private const val DEBOUNCE_MS       = 500L   // Accessibility event debounce
        private const val NOTIF_DEBOUNCE_MS = 300L   // Notification scan debounce (faster)

        const val ACTION_CHAT_CONTEXT = "helium314.keyboard.CHAT_CONTEXT"
        const val EXTRA_MESSAGES      = "chat_messages_json"
        const val EXTRA_PACKAGE       = "source_package"
        const val EXTRA_CHAT_WITH     = "chat_with"

        private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

        private val TIME_EXTRACT_REGEX = Regex(
            """(\d{1,2}:\d{2})\s*(am|pm)?""",
            RegexOption.IGNORE_CASE
        )

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
            "this message was deleted", "you deleted this message",
            "missed voice call", "missed video call", "tap to call back",
            "yesterday", "today", "attach", "emoji", "sticker", "gif", "camera",
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

    // ══════════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ══════════════════════════════════════════════════════════════════════
    private val messageCache   = TreeMap<Long, ChatMessageData>()
    private val seenKeys       = LinkedHashSet<String>()
    private var insertionIndex = 0L

    private var cachedPackage    = ""
    private var cachedChatWith   = ""
    private var cacheInitialized = false

    private val serviceScope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedTime = 0L
    private var lastNotifScanTime = 0L

    private val firstCacheTimeMs: Long
        get() = if (messageCache.isEmpty()) Long.MAX_VALUE else messageCache.firstKey() / 1000
    private val lastCacheTimeMs: Long
        get() = if (messageCache.isEmpty()) 0L else messageCache.lastKey() / 1000

    // ══════════════════════════════════════════════════════════════════════
    // SOURCE 3 — NOTIFICATION BROADCAST RECEIVER
    //
    // Listens for broadcasts from WhatsAppNotificationService.
    // Every new incoming message → forceRescan() immediately.
    //
    // This solves the foreground keyboard problem:
    //   Chat open + user typing → new message arrives
    //   No scroll/window event fires → accessibility normally misses it
    //   This receiver catches it via notification → force scan → AI updated
    //
    // Two paths inside forceRescan():
    //   PATH A: rootInActiveWindow != null → full accessibility extraction
    //   PATH B: rootInActiveWindow == null → inject notification msg directly
    // ══════════════════════════════════════════════════════════════════════
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WhatsAppNotificationService.ACTION_NEW_CHAT_MESSAGE) return

            val pkg       = intent.getStringExtra(WhatsAppNotificationService.EXTRA_NOTIF_PACKAGE)   ?: return
            val sender    = intent.getStringExtra(WhatsAppNotificationService.EXTRA_NOTIF_SENDER)    ?: return
            val msgText   = intent.getStringExtra(WhatsAppNotificationService.EXTRA_NOTIF_MESSAGE)   ?: return
            val timestamp = intent.getLongExtra(WhatsAppNotificationService.EXTRA_NOTIF_TIMESTAMP, 0L)

            Log.i(TAG, "🔔 Notification trigger → [$sender]: ${msgText.take(50)}")

            val now = System.currentTimeMillis()
            if (now - lastNotifScanTime < NOTIF_DEBOUNCE_MS) {
                Log.v(TAG, "⏳ Notification debounce — skip")
                return
            }
            lastNotifScanTime = now

            serviceScope.launch {
                try {
                    forceRescan(pkg, sender, msgText, timestamp)
                } catch (e: Exception) {
                    Log.e(TAG, "💥 forceRescan error: ${e.message}", e)
                }
            }
        }
    }

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
            notificationTimeout = 300
            packageNames = SUPPORTED_PACKAGES.toTypedArray()
        }
        serviceInfo = info

        // Register notification receiver — RECEIVER_NOT_EXPORTED required Android 14+
        registerReceiver(
            notificationReceiver,
            IntentFilter(WhatsAppNotificationService.ACTION_NEW_CHAT_MESSAGE),
            Context.RECEIVER_NOT_EXPORTED
        )

        Log.i(TAG, "╔════════════════════════════════════════════════╗")
        Log.i(TAG, "║  ChatAccessibilityService CONNECTED            ║")
        Log.i(TAG, "║  v6 Hybrid: Accessibility + Notification      ║")
        Log.i(TAG, "║  History=$MAX_HISTORY_SIZE | Context=$MAX_CONTEXT_SIZE               ║")
        Log.i(TAG, "╚════════════════════════════════════════════════╝")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(notificationReceiver)
            Log.d(TAG, "Notification receiver unregistered")
        } catch (e: Exception) {
            Log.w(TAG, "Receiver unregister: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit

    // ══════════════════════════════════════════════════════════════════════
    // MAIN ACCESSIBILITY EVENT — SOURCE 1 + SOURCE 2
    // ══════════════════════════════════════════════════════════════════════
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

                if (DEBUG_TREE_ENABLED) {
                    Log.d(TAG, "════════ TREE pkg=$pkg ════════")
                    debugFullTree(rootNode)
                    Log.d(TAG, "════════ TREE END ════════")
                }

                val chatWith       = extractContactName(rootNode, pkg)
                val screenMessages = extractMessages(rootNode, pkg)

                // BUG D FIX: recycle AFTER all node work
                rootNode.recycle()

                handleNewMessages(pkg, chatWith, screenMessages, source = "ACCESSIBILITY")

            } catch (e: Exception) {
                Log.e(TAG, "💥 Accessibility event error: ${e.message}", e)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // FORCE RESCAN — SOURCE 3 entry point
    //
    // PATH A: App in foreground → rootInActiveWindow available
    //         → full extraction (most accurate)
    //
    // PATH B: App in background → rootInActiveWindow = null
    //         → inject notification message directly as "other"
    //         → still updates AI context immediately
    // ══════════════════════════════════════════════════════════════════════
    private fun forceRescan(
        pkg: String,
        notifSender: String,
        notifMsg: String,
        notifTimestamp: Long
    ) {
        Log.d(TAG, "🔍 forceRescan() — pkg=$pkg")

        val rootNode = rootInActiveWindow

        if (rootNode != null) {
            // PATH A: Full accessibility extraction
            Log.d(TAG, "✅ forceRescan PATH A — root available, full extraction")
            val chatWith       = extractContactName(rootNode, pkg)
            val screenMessages = extractMessages(rootNode, pkg)
            rootNode.recycle()
            handleNewMessages(pkg, chatWith, screenMessages, source = "NOTIF_RESCAN")

        } else {
            // PATH B: No root — inject notification message directly
            // Notifications only come from "other" (never from "me")
            Log.d(TAG, "⚠️ forceRescan PATH B — no root, injecting notification msg")

            val isoTime = ISO_FMT.format(Date(notifTimestamp))
            val injected = ChatMessageData(
                sender  = "other",
                message = notifMsg,
                time    = isoTime,
                y       = 0
            )

            val seenKey = injected.seenKey()
            if (seenKey !in seenKeys) {
                val mapKey = uniqueKey(notifTimestamp)
                messageCache[mapKey] = injected
                seenKeys.add(seenKey)

                // Evict oldest if over limit
                while (messageCache.size > MAX_HISTORY_SIZE) {
                    messageCache.pollFirstEntry()
                }

                Log.i(TAG, "💉 Injected: [${notifMsg.take(40)}] @ $isoTime")
                logCacheState()
                broadcastCache(pkg, notifSender)
            } else {
                Log.v(TAG, "⏭ Notification msg already in cache — skip inject")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HANDLE NEW MESSAGES — shared by all 3 sources after extraction
    // ══════════════════════════════════════════════════════════════════════
    private fun handleNewMessages(
        pkg: String,
        chatWith: String,
        screenMessages: List<ChatMessageData>,
        source: String
    ) {
        if ("$pkg::$chatWith" != "$cachedPackage::$cachedChatWith") {
            Log.i(TAG, "🔄 Chat changed [$source] → [$chatWith]")
            resetCache()
            cachedPackage    = pkg
            cachedChatWith   = chatWith
            cacheInitialized = false
        }

        if (screenMessages.isEmpty()) {
            Log.d(TAG, "⚠️ [$source] No messages from screen")
            return
        }

        val changed = if (!cacheInitialized) initialLoad(screenMessages)
        else processMessages(screenMessages)

        if (changed) {
            logCacheState()
            broadcastCache(pkg, chatWith)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // INITIAL LOAD
    // ══════════════════════════════════════════════════════════════════════
    private fun initialLoad(screenMessages: List<ChatMessageData>): Boolean {
        val valid = screenMessages.filter { parseIsoToMs(it.time) > 0L }
        if (valid.isEmpty()) {
            Log.w(TAG, "Initial load: no valid timestamps")
            return false
        }

        val sorted = valid.sortedBy { parseIsoToMs(it.time) }   // BUG #9 FIX: no Y
        val toLoad = sorted.takeLast(MAX_HISTORY_SIZE)

        for (msg in toLoad) {
            val mapKey = uniqueKey(parseIsoToMs(msg.time))
            messageCache[mapKey] = msg
            seenKeys.add(msg.seenKey())
        }

        cacheInitialized = true
        Log.i(TAG, "📥 Initial: ${messageCache.size} msgs | " +
            "oldest=${messageCache.firstEntry().value.time} " +
            "newest=${messageCache.lastEntry().value.time}")
        return true
    }

    // ══════════════════════════════════════════════════════════════════════
    // PROCESS MESSAGES — merge snapshot into history buffer
    //
    // Handles all sources after initial load.
    // New msgs append bottom, scroll history inserts top.
    // Same-time msgs: insertionIndex preserves tree order.
    // ══════════════════════════════════════════════════════════════════════
    private fun processMessages(screenMessages: List<ChatMessageData>): Boolean {
        val sorted  = screenMessages.sortedBy { parseIsoToMs(it.time) }
        var changed = false

        for (msg in sorted) {
            val msgTimeMs = parseIsoToMs(msg.time)
            if (msgTimeMs <= 0L) continue

            val key = msg.seenKey()       // BUG #4 FIX: full ISO timestamp in key
            if (key in seenKeys) {
                Log.v(TAG, "⏭ Skip (seen): ${key.take(50)}")
                continue
            }

            val mapKey = uniqueKey(msgTimeMs)  // BUG #2 #8 FIX: TreeMap handles position
            messageCache[mapKey] = msg
            seenKeys.add(key)

            while (messageCache.size > MAX_HISTORY_SIZE) {   // BUG #5 FIX: evict oldest
                val evicted = messageCache.pollFirstEntry()
                Log.d(TAG, "🗑️ Evicted: ${evicted.value.message.take(25)}")
            }

            val position = when {
                msgTimeMs < firstCacheTimeMs -> "⬆️ History"
                msgTimeMs > lastCacheTimeMs  -> "➕ New    "
                else                         -> "⚠️ Mid    "
            }
            Log.i(TAG, "$position [${msg.sender}] ${msg.message.take(40)} @ ${msg.time}")
            changed = true
        }

        return changed
    }

    // ══════════════════════════════════════════
    // EXTRACTION DISPATCHER
    // ══════════════════════════════════════════
    private fun extractMessages(root: AccessibilityNodeInfo, pkg: String): List<ChatMessageData> =
        when {
            pkg.startsWith("com.whatsapp") -> extractWhatsAppMessages(root)
            pkg == "com.instagram.android" -> extractInstagramMessages(root)
            else                           -> emptyList()
        }

    // ══════════════════════════════════════════════════════════════════════
    // seenKey — BUG #4 FIX: full ISO prevents same-minute collision
    // ══════════════════════════════════════════════════════════════════════
    private fun ChatMessageData.seenKey(): String {
        val normText = message.trim().replace(Regex("\\s+"), " ").lowercase()
        return "$sender|$normText|$time"
    }

    private fun uniqueKey(timestampMs: Long): Long = timestampMs * 1000 + (insertionIndex++)

    // ══════════════════════════════════════════════════════════════════════
    // WHATSAPP EXTRACTION
    // ══════════════════════════════════════════════════════════════════════
    private fun extractWhatsAppMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val messages = mutableListOf<ChatMessageData>()

        // Pattern C pre-scan: coordinator-level date header
        var coordinatorDateCal: Calendar? = null
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/date_divider_header")
            ?.firstOrNull()?.text?.toString()?.trim()?.let { divText ->
                coordinatorDateCal = parseDateDivider(divText)
                if (coordinatorDateCal != null)
                    Log.d(TAG, "📅 Coordinator date: [$divText]")
            }

        val listNode = root.findAccessibilityNodeInfosByViewId("android:id/list")
            ?.firstOrNull() ?: run {
            Log.w(TAG, "⚠️ android:id/list not found")
            return messages
        }

        var currentDateCal: Calendar = coordinatorDateCal ?: Calendar.getInstance()

        for (i in 0 until listNode.childCount) {
            val listItem = listNode.getChild(i) ?: continue
            val newDate  = processListItem(listItem, messages, currentDateCal)
            if (newDate != null) {
                currentDateCal = newDate
                Log.d(TAG, "📅 Date → ${ISO_FMT.format(currentDateCal.time)}")
            }
        }

        val result = messages
            .filter { parseIsoToMs(it.time) > 0L && it.message.length >= 2 }
            .distinctBy { "${it.sender}|${it.message.trim().lowercase()}|${it.time}" }
            .sortedBy { parseIsoToMs(it.time) }

        Log.d(TAG, "📊 WhatsApp: raw=${messages.size} → filtered=${result.size}")
        return result
    }

    private fun processListItem(
        listItem: AccessibilityNodeInfo,
        messages: MutableList<ChatMessageData>,
        currentDateCal: Calendar
    ): Calendar? {
        if (isInsidePinnedOrSystemBanner(listItem)) return null

        val viewId = listItem.viewIdResourceName ?: ""

        if (viewId == "com.whatsapp:id/conversation_row_date_divider") {
            val rawText = listItem.text?.toString()?.trim() ?: ""
            return if (rawText.isNotEmpty()) parseDateDivider(rawText) else null
        }

        if (viewId == "com.whatsapp:id/info") return null

        var updatedDate : Calendar? = null
        var msgText     : String?   = null
        var timeStr     : String?   = null
        var sender                  = "other"
        var msgY                    = 0
        var localDate   : Calendar? = null
        var fileName    : String?   = null
        var fileType    : String?   = null

        for (j in 0 until listItem.childCount) {
            val child    = listItem.getChild(j) ?: continue
            val childId  = child.viewIdResourceName ?: ""
            val childTxt = child.text?.toString()?.trim() ?: ""

            when (childId) {
                "com.whatsapp:id/conversation_row_date_divider" -> {
                    if (childTxt.isNotEmpty()) {
                        parseDateDivider(childTxt)?.let { cal ->
                            localDate = cal; updatedDate = cal
                        }
                    }
                }
                "com.whatsapp:id/message_text" -> {
                    msgText = childTxt
                    msgY = Rect().also { child.getBoundsInScreen(it) }.centerY()
                }
                "com.whatsapp:id/date" -> {
                    val extracted = extractTimeFromText(cleanTimestampString(childTxt))
                    if (extracted != null) timeStr = extracted
                    else Log.v(TAG, "⏱ No time in: [$childTxt]")
                }
                "com.whatsapp:id/status" -> { sender = "me" }
                "com.whatsapp:id/caption_text" -> {
                    if (childTxt.isNotEmpty()) {
                        msgText = "[Image] $childTxt"
                        msgY = Rect().also { child.getBoundsInScreen(it) }.centerY()
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
                    msgY = Rect().also { child.getBoundsInScreen(it) }.centerY()
                }
            }
        }

        if (msgText == null && !fileName.isNullOrEmpty()) {
            msgText = if (!fileType.isNullOrEmpty()) "[$fileType] $fileName" else "[File] $fileName"
        }

        return when {
            msgText.isNullOrEmpty() -> {
                Log.v(TAG, "⏭ SKIP: no msgText (sender=$sender)")
                updatedDate
            }
            timeStr.isNullOrEmpty() -> {
                Log.d(TAG, "⚠️ SKIP: no timeStr | text=[${msgText?.take(30)}]")
                updatedDate
            }
            else -> {
                val effectiveDateCal = localDate ?: currentDateCal
                val fullTimestamp    = combineDateTime(effectiveDateCal, timeStr)
                val msgLower         = msgText.lowercase().trim()

                when {
                    fullTimestamp.isEmpty() ->
                        Log.d(TAG, "⚠️ SKIP: time parse failed | timeStr=[$timeStr]")
                    msgLower in WHATSAPP_IGNORE_TEXT ->
                        Log.v(TAG, "⏭ SKIP: ignore | text=[${msgText.take(30)}]")
                    else -> {
                        Log.d(TAG, "✅ ADD [$sender] [${msgText.take(40)}] @ $fullTimestamp")
                        messages.add(ChatMessageData(sender, msgText, fullTimestamp, msgY))
                    }
                }
                updatedDate
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // BUG #7 FIX: parseDateDivider — future-date check for day names
    // ══════════════════════════════════════════════════════════════════════
    private fun parseDateDivider(text: String): Calendar? {
        val lower = text.lowercase().trim()
        return when {
            lower == "today"     -> Calendar.getInstance()
            lower == "yesterday" -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

            DAY_OF_WEEK_MAP.containsKey(lower) -> {
                val targetDay = DAY_OF_WEEK_MAP[lower]!!
                val today = Calendar.getInstance()
                val cal   = Calendar.getInstance()
                var attempts = 0
                while (cal.get(Calendar.DAY_OF_WEEK) != targetDay && attempts < 8) {
                    cal.add(Calendar.DAY_OF_YEAR, -1)
                    attempts++
                }
                if (cal.after(today)) cal.add(Calendar.DAY_OF_YEAR, -7)
                cal
            }

            else -> {
                for (fmt in listOf("d MMMM yyyy", "MMMM d, yyyy", "MMM d, yyyy", "d MMM yyyy")) {
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
        val cal = parseTime(cleanTimestampString(timeStr), dateCal) ?: return ""
        return formatIso(cal)
    }

    // FIX F: Regex.find() — extracts time from within any string
    private fun extractTimeFromText(text: String): String? {
        val match = TIME_EXTRACT_REGEX.find(text) ?: return null
        val hhmm  = match.groupValues[1]
        val ampm  = match.groupValues[2]
        return if (ampm.isNotEmpty()) "$hhmm $ampm" else hhmm
    }

    // ══════════════════════════════════════════
    // INSTAGRAM EXTRACTION
    // ══════════════════════════════════════════
    private fun extractInstagramMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val result = mutableListOf<ChatMessageData>()
        traverseInstagram(root, result, resources.displayMetrics.widthPixels, "")
        return result.filter {
            it.message.length >= 2 && it.message.lowercase() !in INSTAGRAM_IGNORE
        }
    }

    private fun traverseInstagram(
        node: AccessibilityNodeInfo?,
        result: MutableList<ChatMessageData>,
        screenWidth: Int,
        currentTime: String,
        depth: Int = 0
    ) {
        if (node == null || depth > 20) return
        val text   = node.text?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        var timeToUse = currentTime

        if (isTimestampText(cleanTimestampString(text))) {
            timeToUse = normalizeInstagramTimestamp(text)
        }

        if (viewId in INSTAGRAM_MSG_IDS && text.isNotEmpty() && timeToUse.isNotEmpty()) {
            val rect   = Rect().also { node.getBoundsInScreen(it) }
            val sender = if (rect.centerX() > screenWidth * 0.55) "me" else "other"
            result.add(ChatMessageData(sender, text, timeToUse, rect.centerY()))
        }

        for (i in 0 until node.childCount) {
            traverseInstagram(node.getChild(i), result, screenWidth, timeToUse, depth + 1)
        }
    }

    // ══════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════

    private fun isInsidePinnedOrSystemBanner(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 10) {
            val id = current.viewIdResourceName ?: ""
            if (id in WHATSAPP_BANNER_IDS || id in WHATSAPP_SYSTEM_IDS) return true
            current = current.parent; depth++
        }
        return false
    }

    private fun resetCache() {
        messageCache.clear()
        seenKeys.clear()
        insertionIndex   = 0L
        cacheInitialized = false
    }

    private fun extractContactName(root: AccessibilityNodeInfo, pkg: String): String = when {
        pkg == "com.instagram.android" ->
            root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/header_title")
                ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
        pkg.startsWith("com.whatsapp") -> {
            val n = root.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/conversation_contact_name")
            if (!n.isNullOrEmpty()) n.firstOrNull()?.text?.toString()?.trim() ?: ""
            else root.findAccessibilityNodeInfosByViewId(
                "com.whatsapp:id/toolbar_title_text_view")
                ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
        }
        else -> "Unknown"
    }

    // FIX H: Strip invisible Unicode chars that break regex matching
    private fun cleanTimestampString(raw: String): String =
        raw.replace('\u202F', ' ').replace('\u00A0', ' ')
            .replace('\u200E', ' ').replace('\u200F', ' ').replace('\u200B', ' ')
            .trim()

    private fun parseIsoToMs(isoTime: String): Long =
        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (e: Exception) { 0L }

    private fun isTimestampText(text: String): Boolean {
        val l = text.lowercase()
        return l.startsWith("today") || l.startsWith("yesterday") ||
            l.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ||
            l.matches(Regex(
                "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}.*",
                RegexOption.IGNORE_CASE
            ))
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
                else -> {
                    val p = try {
                        SimpleDateFormat("MMM d h:mm a", Locale.ENGLISH).parse(cleaned)
                    } catch (e: Exception) { null } ?: return ""
                    val c = Calendar.getInstance().apply {
                        time = p; set(Calendar.YEAR, today.get(Calendar.YEAR))
                    }
                    formatIso(c)
                }
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

    // ══════════════════════════════════════════════════════════════════════
    // BROADCAST — Two-List context window extraction
    // BUG #6 FIX: TreeMap already sorted, takeLast = newest 15
    // ══════════════════════════════════════════════════════════════════════
    private fun broadcastCache(pkg: String, chatWith: String) {
        val sendMessages = messageCache.values.toList().takeLast(MAX_CONTEXT_SIZE)

        sendBroadcast(Intent(ACTION_CHAT_CONTEXT).apply {
            setPackage(applicationContext.packageName)
            putExtra(EXTRA_PACKAGE,   pkg)
            putExtra(EXTRA_CHAT_WITH, chatWith)
            putExtra(EXTRA_MESSAGES,  buildJsonArray(sendMessages))
        })

        Log.i(TAG, "📡 Broadcast: history=${messageCache.size}/$MAX_HISTORY_SIZE " +
            "context=${sendMessages.size}/$MAX_CONTEXT_SIZE chat=$chatWith")
    }

    private fun buildJsonArray(messages: List<ChatMessageData>): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, msg ->
            sb.append("{\"sequence\":${i + 1},")
            sb.append("\"sender\":\"${escapeJson(msg.sender)}\",")
            sb.append("\"message\":\"${escapeJson(msg.message)}\",")
            sb.append("\"time\":\"${msg.time}\"}")
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
        val oldest       = messageCache.firstEntry()?.value
        val newest       = messageCache.lastEntry()?.value
        val sendMessages = messageCache.values.toList().takeLast(MAX_CONTEXT_SIZE)


        Log.i(TAG, "╔══════════════════════════════════════════════╗")
        Log.i(TAG, "  HISTORY  [${messageCache.size}/$MAX_HISTORY_SIZE] — $cachedChatWith")
        Log.i(TAG, "  CONTEXT  [${sendMessages.size}/$MAX_CONTEXT_SIZE] → AI")
        Log.i(TAG, "  Window:  [${oldest?.time}  →  ${newest?.time}]")
        Log.i(TAG, "╚══════════════════════════════════════════════╝")
        sendMessages.forEachIndexed { i, m ->
            val marker = if (i == sendMessages.size - 1) " ← LATEST" else ""
            Log.i(TAG, "  [${i + 1}] ${m.time} [${m.sender}] ${m.message.take(50)}$marker")
        }
    }

    private fun debugFullTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 20) return
        val indent = "  ".repeat(depth)
        Log.d(TAG, "${indent}id=${node.viewIdResourceName} " +
            "text=${node.text?.toString()?.take(60)} " +
            "class=${node.className?.toString()?.substringAfterLast(".")} " +
            "children=${node.childCount}")
        for (i in 0 until node.childCount) debugFullTree(node.getChild(i), depth + 1)
    }
}

// ══════════════════════════════════════════════════════════════════════
// DATA CLASS
// ══════════════════════════════════════════════════════════════════════
data class ChatMessageData(
    val sender:  String,
    val message: String,
    val time:    String,
    val y:       Int = 0
)
