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
 * #14 NEW: 5-stage grouped extraction replaces immediate-add walk
 *     Stage 1 — capture full tree snapshot into TreeRow list
 *     Stage 2 — detect all date dividers, pick orphan date
 *     Stage 3 — group PendingMsg under their correct DateGroup
 *     Stage 4 — sort messages by time inside each DateGroup
 *     Stage 5 — sort groups oldest→newest, flatten to final list
 * #15 FIX: orphan date = day BEFORE first divider (not last divider)
 *     Messages appearing before the first tree divider are the
 *     oldest messages on screen. They belong to the day prior to
 *     whatever the first divider says.
 *     e.g. first divider = "3 March" → orphan date = "2 March" ✓
 *
 * ═══════════════════════════════════════════════════════════════
 * WHY OLD IMMEDIATE-ADD WALK WAS BROKEN:
 * ═══════════════════════════════════════════════════════════════
 *
 *   Old flow: walk node → see message → add immediately
 *   Problem:  WhatsApp RecyclerView puts newest messages BEFORE
 *             their date divider in the accessibility tree.
 *             So messages inherited the FIRST divider date (oldest),
 *             not the LAST (most recent) where they actually belong.
 *
 *   New flow: collect ALL rows first → detect ALL dividers →
 *             group → sort within groups → merge chronologically.
 *             Every message now gets the correct date regardless
 *             of tree order.
 *
 * ═══════════════════════════════════════════════════════════════
 * WHAT WAS NOT CHANGED (per task requirement):
 * ═══════════════════════════════════════════════════════════════
 *   processMessages()    — unchanged
 *   initialLoad()        — unchanged
 *   seenKey()            — unchanged
 *   broadcastCache()     — unchanged
 *   resetCache()         — unchanged
 *   MessageCache logic   — unchanged
 *   Instagram extraction — unchanged
 *   All utility methods  — unchanged
 */
class ChatAccessibilityService : AccessibilityService() {

    // ══════════════════════════════════════════════════════════════════════
    // INTERNAL MODELS — used only by the 5-stage WhatsApp extraction
    // ══════════════════════════════════════════════════════════════════════

    /**
     * A single classified row captured from the accessibility list.
     * The entire list is converted to these rows in Stage 1 BEFORE
     * any date or timestamp assignment.
     */
    private sealed class TreeRow {
        /** A date-divider node ("Yesterday", "3 March 2026", …). */
        data class Divider(val cal: Calendar) : TreeRow()
        /**
         * A message node. Date is NOT assigned yet — only the raw time
         * string shown by WhatsApp ("10:43 pm") is stored.
         * The full timestamp is built in Stage 4 after the correct
         * DateGroup is known.
         */
        data class Msg(val pending: PendingMsg) : TreeRow()
    }

    /**
     * A message whose date has not been assigned yet.
     * [rawTimeStr] = exactly what WhatsApp shows, e.g. "10:43 pm".
     */
    private data class PendingMsg(
        val sender:     String,
        val text:       String,
        val rawTimeStr: String,
        val y:          Int
    )

    /** One date section: a resolved Calendar + all messages that belong to it. */
    private data class DateGroup(
        val dateCal: Calendar,
        val msgs:    MutableList<PendingMsg> = mutableListOf()
    )

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

        // Instagram-specific constants removed — now live in InstagramConstants.kt
        // (extractors/instagram/InstagramConstants.kt)

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

    // Last ISO timestamp successfully extracted from an Instagram DM tree.
    // Passed as fallback to InstagramExtractor when the keyboard opens and
    // Instagram's RecyclerView shrinks, scrolling the timestamp node away.
    // Reset whenever the chat contact changes (same reset as the rest of cache).
    private var lastKnownIgTimestamp: String = ""

    private val serviceScope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedTime = 0L

    // Fix B — content-hash skip: same messages pe extraction skip karo
    private var lastExtractedHash = 0

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

                // Fix A — debugFullTree only in DEBUG builds (prevents 50-300ms blocking in prod)
                if (android.os.Build.TYPE == "userdebug" || tryIsDebugBuild()) {
                    Log.d(TAG, "TREE START")
                    debugFullTree(rootNode)
                    Log.d(TAG, "TREE END")
                }

                // Fix C — skip non-DM Instagram screens (feed, reels, stories, gallery)
                if (pkg == "com.instagram.android" && !isInstagramDmScreen(rootNode)) {
                    rootNode.recycle()
                    return@launch
                }

                val chatWith = extractContactName(rootNode, pkg)

                if ("$pkg::$chatWith" != "$cachedPackage::$cachedChatWith") {
                    Log.i(TAG, "🔄 Chat changed: [$chatWith] — full reset")
                    resetCache()
                    cachedPackage    = pkg
                    cachedChatWith   = chatWith
                    cacheInitialized = false
                    lastExtractedHash = 0  // reset hash on chat change
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

                // Fix B — skip if same messages as last extraction (no new content)
                val newHash = screenMessages.fold(0) { acc, msg ->
                    acc * 31 + (msg.sender + msg.message + msg.time).hashCode()
                }
                if (cacheInitialized && newHash == lastExtractedHash) {
                    Log.v(TAG, "⏭ Skip extraction — content unchanged (hash=$newHash)")
                    return@launch
                }
                lastExtractedHash = newHash

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

    // Fix C helper — Instagram DM screen detection
    // Must have BOTH message_list (chat RecyclerView) AND composer bar (text input)
    private fun isInstagramDmScreen(rootNode: AccessibilityNodeInfo): Boolean {
        val hasMessageList = rootNode
            .findAccessibilityNodeInfosByViewId("com.instagram.android:id/message_list")
            ?.isNotEmpty() == true
        if (!hasMessageList) return false
        val hasComposer = rootNode
            .findAccessibilityNodeInfosByViewId("com.instagram.android:id/row_thread_composer_edittext")
            ?.isNotEmpty() == true
        return hasComposer
    }

    // Fix A helper — detect debug build without BuildConfig dependency
    private fun tryIsDebugBuild(): Boolean = try {
        val appInfo = applicationContext.packageManager
            .getApplicationInfo(applicationContext.packageName, 0)
        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    } catch (e: Exception) { false }

    // ══════════════════════════════════════════
    // INITIAL LOAD — unchanged
    // ══════════════════════════════════════════
    private fun initialLoad(screenMessages: List<ChatMessageData>): Boolean {

        val validMsgs = screenMessages.filter { parseIsoToMs(it.time) > 0L }

        if (validMsgs.isEmpty()) {
            Log.w(TAG, "Initial load: no valid messages")
            return false
        }

//        val sorted = validMsgs.sortedWith(
//            compareBy<ChatMessageData> { parseIsoToMs(it.time) }
//                .thenBy { it.y }
//        )

        val sorted = validMsgs.sortedBy { parseIsoToMs(it.time) }


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

    // ══════════════════════════════════════════
    // PROCESS MESSAGES — unchanged
    // ══════════════════════════════════════════
    private fun processMessages(screenMessages: List<ChatMessageData>): Boolean {

        val sorted = screenMessages.sortedWith(
            compareBy<ChatMessageData> { parseIsoToMs(it.time) }
                .thenBy { it.y }
        )

        var changed = false

        for (msg in sorted) {

            val msgTimeMs = parseIsoToMs(msg.time)
            val key       = msg.seenKey()

            if (msgTimeMs <= 0L) continue

            if (msgTimeMs < lastCacheTimeMs) {
                Log.v(TAG, "⏭ Skip (older): [${msg.sender}] ${msg.message.take(25)} @ ${msg.time}")
                continue
            }

            if (key in seenKeys) {
                Log.v(TAG, "⏭ Skip (seen): ${key.take(40)}")
                continue
            }

            messageCache.addLast(msg)
            seenKeys.add(key)

            if (messageCache.size > MAX_CACHE_SIZE) {
                val evicted = messageCache.removeFirst()
                Log.d(TAG, "🗑️ Evicted: ${evicted.message.take(25)}")
            }

            lastCacheTimeMs = parseIsoToMs(messageCache.last().time)

            Log.i(TAG, "➕ Appended: [${msg.sender}] ${msg.message.take(40)} @ ${msg.time}")
            changed = true
        }

        return changed
    }

    // ══════════════════════════════════════════
    // ══════════════════════════════════════════
    // seenKey
    //
    // FIX: was using only HH:mm which caused collisions on Instagram.
    // All Instagram messages in the same group share one timestamp
    // (e.g. "Today 11:35 am"). Three "Hlo" from "me" all produced
    // key "me|hlo|11:35" → only the first survived, the rest were dropped.
    //
    // Now uses the full ISO string ("2026-03-18T11:35:01").
    // InstagramExtractor assigns unique per-message second offsets
    // so every message has a distinct `time` field → distinct key.
    // WhatsApp is unaffected — each message already has its own HH:mm.
    // ══════════════════════════════════════════
    private fun ChatMessageData.seenKey(): String {
        val normText = message.trim().replace(Regex("\\s+"), " ").lowercase()
        return "${sender}|$normText|$time"
    }

    // ══════════════════════════════════════════════════════════════════════
    // WHATSAPP EXTRACTION — 5-STAGE GROUPED APPROACH         ← CHANGED
    //
    // Replaces the old single-pass immediate-add walk.
    //
    // ─── STAGE 1: Capture tree snapshot ────────────────────────────────
    //   Walk android:id/list. Classify every child as Divider or Msg.
    //   Messages are stored as PendingMsg (rawTimeStr only, no date yet).
    //   Nothing is written to the output list at this stage.
    //
    // ─── STAGE 2: Detect all dividers, pick orphan date ────────────────
    //   "Orphan" messages = those appearing before any divider in the tree.
    //   They are the OLDEST visible messages (WhatsApp reversed layout).
    //   Orphan date = ONE DAY BEFORE the FIRST divider found.
    //   e.g. first divider = "3 March" → orphan date = "2 March" ✓
    //   Priority: coordinator header → (firstDivider − 1 day) → today.
    //
    // ─── STAGE 3: Group messages under their divider ────────────────────
    //   Walk treeRows in order. On Divider → open new DateGroup.
    //   On Msg → append to current DateGroup.
    //   Orphan messages fall into the pre-created orphanGroup.
    //
    // ─── STAGE 4: Assign timestamps + sort within each group ───────────
    //   Combine group.dateCal + pending.rawTimeStr → ISO timestamp.
    //   Sort messages by that timestamp inside the group.
    //
    // ─── STAGE 5: Sort groups oldest→newest, flatten ───────────────────
    //   Groups sorted by dateCal.timeInMillis.
    //   Flattened into a single list → returned to initialLoad/processMessages.
    // ══════════════════════════════════════════════════════════════════════
    private fun extractWhatsAppMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {

        // ── Pre-check: Pattern C — coordinator-level date_divider_header ─────────────
        var coordinatorDateCal: Calendar? = null
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/date_divider_header")
            ?.firstOrNull()?.text?.toString()?.trim()?.let { txt ->
                coordinatorDateCal = parseDateDivider(txt)
                if (coordinatorDateCal != null)
                    Log.d(TAG, "📅 [STAGE1] coordinator header: [$txt] → ${ISO_FMT.format(coordinatorDateCal!!.time)}")
                else
                    Log.w(TAG, "📅 [STAGE1] coordinator header unparseable: [$txt]")
            }

        val listNode = root.findAccessibilityNodeInfosByViewId("android:id/list")
            ?.firstOrNull() ?: return emptyList()

        // ── STAGE 1: Capture full tree snapshot ──────────────────────────────────────
        // Do NOT add messages here. Only collect classified TreeRow objects.
        val treeRows = mutableListOf<TreeRow>()
        for (i in 0 until listNode.childCount) {
            val item = listNode.getChild(i) ?: continue
            treeRows.addAll(collectTreeRows(item))
            item.recycle()
        }
        Log.d(TAG, "📋 [STAGE1] ${treeRows.size} rows from tree")

        // ── STAGE 2: Detect all dividers, determine orphan date ──────────────────────
        //
        // ╔══════════════════════════════════════════════════════════════════╗
        // ║  BUG FIX #15 — orphan date = day BEFORE first divider           ║
        // ║                                                                  ║
        // ║  Accessibility tree order (WhatsApp reversed RecyclerView):      ║
        // ║                                                                  ║
        // ║    msg  "try to kr liys hsi"  11:19  ← orphan (no divider yet)  ║
        // ║    msg  "ab iska or dekhta"   11:20  ← orphan                   ║
        // ║    [3 March 2026 divider]                                        ║
        // ║    msg  "Ha unse connect"      9:26                              ║
        // ║    [6 March 2026 divider]                                        ║
        // ║    msg  "Kb tk aaoge"         10:27                              ║
        // ║    [Thursday divider]                                            ║
        // ║    msg  "hi"                   5:52                              ║
        // ║                                                                  ║
        // ║  firstDivider = 3 March 2026                                     ║
        // ║  orphanDate   = 3 March − 1 day = 2 March 2026  ✓               ║
        // ║                                                                  ║
        // ║  OLD (wrong): lastOrNull() → Thursday → orphan @ 2026-03-12     ║
        // ║  NEW (fixed): firstOrNull() − 1 day  → 2 March → orphan @ 2026-03-02 ✓ ║
        // ╚══════════════════════════════════════════════════════════════════╝
        val allDividers = treeRows.filterIsInstance<TreeRow.Divider>()

        // ▼▼▼ ONLY LINE CHANGED FROM ORIGINAL ▼▼▼
        val orphanDate: Calendar = coordinatorDateCal
            ?: allDividers.firstOrNull()?.cal?.let { firstCal ->
                (firstCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
            ?: Calendar.getInstance()
        // ▲▲▲ ONLY LINE CHANGED FROM ORIGINAL ▲▲▲

        Log.d(TAG, "📅 [STAGE2] ${allDividers.size} dividers | orphanDate = ${ISO_FMT.format(orphanDate.time)}")

        // ── STAGE 3: Group messages under their date divider ─────────────────────────
        //
        // Start with the orphan group (catches messages before the first divider).
        // Each Divider row opens a new DateGroup. Each Msg row joins the current group.
        val groups       = mutableListOf<DateGroup>()
        var currentGroup = DateGroup(orphanDate.clone() as Calendar)
        groups.add(currentGroup)

        for (row in treeRows) {
            when (row) {
                is TreeRow.Divider -> {
                    currentGroup = DateGroup(row.cal.clone() as Calendar)
                    groups.add(currentGroup)
                    Log.d(TAG, "📅 [STAGE3] new group → ${ISO_FMT.format(row.cal.time)}")
                }
                is TreeRow.Msg -> currentGroup.msgs.add(row.pending)
            }
        }
        Log.d(TAG, "📋 [STAGE3] ${groups.size} date groups built")

        // ── STAGES 4 & 5: Assign timestamps, sort within groups, merge ───────────────
        val result = mutableListOf<ChatMessageData>()

        // Stage 5: groups sorted oldest → newest
        val sortedGroups = groups.sortedBy { it.dateCal.timeInMillis }

        for (group in sortedGroups) {
            if (group.msgs.isEmpty()) continue

            // Stage 4: build ChatMessageData with correct timestamp for each pending msg
            val withTimestamps = group.msgs.mapNotNull { pending ->
                val iso = combineDateTime(group.dateCal, pending.rawTimeStr)
                if (iso.isEmpty()) return@mapNotNull null
                if (pending.text.lowercase().trim() in WHATSAPP_IGNORE_TEXT) return@mapNotNull null
                ChatMessageData(pending.sender, pending.text, iso, pending.y).also {
                    Log.v(TAG, "📌 [STAGE4] [${pending.sender}] \"${pending.text.take(30)}\" @ ${pending.rawTimeStr} → $iso")
                }
            }

            // Sort messages inside this date group by their full timestamp
            val sortedMsgs = withTimestamps.sortedBy { parseIsoToMs(it.time) }
            Log.d(TAG, "📅 [STAGE5] group ${ISO_FMT.format(group.dateCal.time)}: ${sortedMsgs.size} msgs")
            result.addAll(sortedMsgs)
        }

        return result.filter { parseIsoToMs(it.time) > 0L && it.message.isNotBlank() }
    }

    // ══════════════════════════════════════════════════════════════════════
    // collectTreeRows — Stage 1 helper                       ← CHANGED
    //
    // Converts ONE list item into zero or more TreeRows.
    // Replaces processListItem(). Key difference: NO timestamp is built
    // here. Messages become PendingMsg (rawTimeStr only). The date is
    // applied in Stage 4 after the correct DateGroup is determined.
    //
    // For a ViewGroup containing BOTH an inline divider AND a message
    // (Pattern A), the Divider row is emitted FIRST so Stage 3's walk
    // creates the new DateGroup before adding the message to it.
    //
    // Pattern A — divider child inside ViewGroup → [Divider, Msg]
    // Pattern B — standalone divider list item  → [Divider]
    // Message-only ViewGroup                    → [Msg]
    // System/banner row                         → []
    // ══════════════════════════════════════════════════════════════════════
    private fun collectTreeRows(listItem: AccessibilityNodeInfo): List<TreeRow> {
        val viewId = listItem.viewIdResourceName ?: ""

        // Skip system and banner rows — they contain no chat messages
        if (viewId in WHATSAPP_BANNER_IDS || viewId in WHATSAPP_SYSTEM_IDS) return emptyList()

        // Pattern B — standalone date divider list item
        if (viewId == "com.whatsapp:id/conversation_row_date_divider") {
            val txt = listItem.text?.toString()?.trim() ?: ""
            if (txt.isEmpty()) return emptyList()
            val cal = parseDateDivider(txt) ?: return emptyList()
            Log.d(TAG, "📅 [STAGE1] standalone divider: [$txt] → ${ISO_FMT.format(cal.time)}")
            return listOf(TreeRow.Divider(cal))
        }

        // ViewGroup — scan children for divider + message content
        val rows         = mutableListOf<TreeRow>()
        var inlineDivCal: Calendar? = null
        var msgText:     String?    = null
        var rawTimeStr:  String?    = null
        var sender                  = "other"
        var msgY                    = 0
        var fileName:    String?    = null
        var fileType:    String?    = null

        for (j in 0 until listItem.childCount) {
            val child    = listItem.getChild(j) ?: continue
            val childId  = child.viewIdResourceName ?: ""
            val childTxt = child.text?.toString()?.trim() ?: ""
            val cleaned  = cleanTimestampString(childTxt)

            when (childId) {
                // Pattern A — inline date divider inside the ViewGroup
                "com.whatsapp:id/conversation_row_date_divider" -> {
                    if (childTxt.isNotEmpty()) {
                        val cal = parseDateDivider(childTxt)
                        if (cal != null) {
                            inlineDivCal = cal
                            Log.d(TAG, "📅 [STAGE1] inline divider: [$childTxt] → ${ISO_FMT.format(cal.time)}")
                        }
                    }
                }
                "com.whatsapp:id/message_text" -> {
                    msgText = childTxt
                    val rect = Rect().also { child.getBoundsInScreen(it) }
                    msgY = rect.centerY()
                }
                "com.whatsapp:id/date" -> {
                    // Store raw time string only — the date part comes from the DateGroup
                    if (isTimeOnlyText(cleaned)) rawTimeStr = cleaned
                }
                "com.whatsapp:id/status" -> sender = "me"
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
                        gc.recycle()
                    }
                    val rect = Rect().also { child.getBoundsInScreen(it) }
                    msgY = rect.centerY()
                }
            }
            child.recycle()
        }

        // Compose file attachment label when no plain-text message was found
        if (msgText == null && !fileName.isNullOrEmpty()) {
            msgText = if (!fileType.isNullOrEmpty()) "[$fileType] $fileName" else "[File] $fileName"
        }

        // CRITICAL ORDER: emit Divider BEFORE the message so Stage 3 opens the
        // new DateGroup before this message is added to it.
        if (inlineDivCal != null) {
            rows.add(TreeRow.Divider(inlineDivCal))
        }

        if (!msgText.isNullOrEmpty() && !rawTimeStr.isNullOrEmpty()) {
            rows.add(TreeRow.Msg(PendingMsg(sender, msgText, rawTimeStr, msgY)))
        }

        return rows
    }

    // ══════════════════════════════════════════════════════════════════════
    // parseDateDivider — unchanged
    // ══════════════════════════════════════════════════════════════════════
    private fun parseDateDivider(text: String): Calendar? {
        val trimmed = text.trim()
        val lower   = trimmed.lowercase()

        Log.d(TAG, "📅 parseDateDivider: [$trimmed]")

        if (lower == "today") {
            Log.d(TAG, "📅   → TODAY")
            return Calendar.getInstance()
        }

        if (lower == "yesterday") {
            Log.d(TAG, "📅   → YESTERDAY")
            return Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        }

        if (DAY_OF_WEEK_MAP.containsKey(lower)) {
            val targetDay = DAY_OF_WEEK_MAP[lower]!!
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            var attempts = 0
            while (cal.get(Calendar.DAY_OF_WEEK) != targetDay && attempts < 7) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
                attempts++
            }
            if (cal.get(Calendar.DAY_OF_WEEK) == targetDay) {
                Log.d(TAG, "📅   → DAY-OF-WEEK [$trimmed] = ${ISO_FMT.format(cal.time)}")
                return cal
            } else {
                Log.w(TAG, "📅   → DAY-OF-WEEK [$trimmed] not found in 7-day window!")
                return null
            }
        }

        val formats = listOf(
            "d MMMM yyyy",
            "MMMM d, yyyy",
            "MMM d, yyyy",
            "d MMM yyyy",
            "d/M/yy",
            "dd/MM/yyyy",
            "M/d/yyyy",
            "yyyy-MM-dd",
            "d MMMM",
            "MMMM d",
            "MMM d"
        )

        val today   = Calendar.getInstance()
        val locales = linkedSetOf(Locale.ENGLISH, Locale.getDefault())
            .filter { it.language.isNotEmpty() }

        for (fmt in formats) {
            for (locale in locales) {
                try {
                    val sdf    = SimpleDateFormat(fmt, locale)
                    sdf.isLenient = false
                    val parsed = sdf.parse(trimmed) ?: continue
                    val cal    = Calendar.getInstance()
                    cal.time   = parsed
                    if (!fmt.contains("y") && !fmt.contains("Y")) {
                        cal.set(Calendar.YEAR, today.get(Calendar.YEAR))
                    }
                    Log.d(TAG, "📅   → ABS-DATE [$trimmed] fmt=[$fmt] locale=[${locale.language}] = ${ISO_FMT.format(cal.time)}")
                    return cal
                } catch (e: Exception) { /* try next */ }
            }
        }

        Log.w(TAG, "📅   → UNPARSEABLE date divider: [$trimmed] — falling back to null")
        return null
    }

    private fun combineDateTime(dateCal: Calendar, timeStr: String): String {
        val cleaned = cleanTimestampString(timeStr)
        val cal     = parseTime(cleaned, dateCal) ?: return ""
        return formatIso(cal)
    }

    // ══════════════════════════════════════════════════════════════════════
    // INSTAGRAM EXTRACTION — delegated to InstagramExtractor
    //
    // All Instagram-specific logic now lives in:
    //   extractors/instagram/InstagramExtractor.kt    (main entry point)
    //   extractors/instagram/InstagramNodeParser.kt   (node classification)
    //   extractors/instagram/InstagramConstants.kt    (IDs + ignore lists)
    //   extractors/instagram/IgRow.kt                 (typed row model)
    //
    // DO NOT add Instagram logic back here.
    //
    // lastKnownIgTimestamp is passed as fallback so messages are not dropped
    // when the keyboard opens and the RecyclerView timestamp node scrolls away.
    // It is updated from the first message of every successful extraction.
    // ══════════════════════════════════════════════════════════════════════
    private fun extractInstagramMessages(root: AccessibilityNodeInfo): List<ChatMessageData> {
        val result = helium314.keyboard.accessibility.extractors.instagram.InstagramExtractor
            .extract(root, lastKnownIgTimestamp)

        // Update the last known timestamp so the next extraction (possibly
        // without a timestamp node) has a valid fallback to work from.
        val firstWithTime = result.firstOrNull()
        if (firstWithTime != null && firstWithTime.time.isNotEmpty()) {
            lastKnownIgTimestamp = firstWithTime.time
            Log.v(TAG, "📅 [IG] lastKnownIgTimestamp updated → $lastKnownIgTimestamp")
        }

        return result
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
        lastCacheTimeMs       = 0L
        cacheInitialized      = false
        lastKnownIgTimestamp  = ""   // reset per-chat fallback timestamp
        lastExtractedHash     = 0    // Fix B — reset hash so next extraction is not skipped
    }

    private fun extractContactName(root: AccessibilityNodeInfo, pkg: String): String {
        return when {
            pkg == "com.instagram.android" ->
                helium314.keyboard.accessibility.extractors.instagram.InstagramExtractor.getContactName(root)
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
    // TIME UTILITIES — unchanged
    // ══════════════════════════════════════════
    private fun parseIsoToMs(isoTime: String): Long =
        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (e: Exception) { 0L }

    private fun cleanTimestampString(raw: String): String =
        raw.replace('\u202F', ' ').replace('\u00A0', ' ').trim()

    private fun isTimeOnlyText(text: String): Boolean =
        text.lowercase().matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE))

    // isTimestampText removed — now lives in InstagramTimestampParser.kt
    // normalizeInstagramTimestamp removed — now lives in InstagramTimestampParser.kt

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
    // BROADCAST — unchanged
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
    // DEBUG — unchanged
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
