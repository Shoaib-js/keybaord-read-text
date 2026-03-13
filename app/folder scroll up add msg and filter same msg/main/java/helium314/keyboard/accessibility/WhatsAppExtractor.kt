//package helium314.keyboard.accessibility
//
//import android.graphics.Rect
//import android.util.Log
//import android.view.accessibility.AccessibilityNodeInfo
//import helium314.keyboard.accessibility.ChatUtils.TAG
//import helium314.keyboard.accessibility.ChatUtils.ISO_FMT
//import helium314.keyboard.accessibility.ChatUtils.cleanTimestampString
//import helium314.keyboard.accessibility.ChatUtils.combineDateTime
//import helium314.keyboard.accessibility.ChatUtils.extractTimeFromText
//import helium314.keyboard.accessibility.ChatUtils.parseIsoToMs
//import helium314.keyboard.accessibility.ChatUtils.parseDateDivider
//import java.util.Calendar
//
///**
// * WhatsAppExtractor
// *
// * Extracts chat messages from a WhatsApp or WhatsApp Business
// * accessibility tree snapshot.
// *
// * Called by ChatAccessibilityService when pkg starts with "com.whatsapp".
// *
// * Public API:
// *   WhatsAppExtractor.extract(root)          → List<ChatMessageData>
// *   WhatsAppExtractor.getContactName(root)   → String
// *
// * THREE UI patterns handled:
// *   Pattern A — Date divider INSIDE a ViewGroup message row
// *   Pattern B — Date divider as STANDALONE list item
// *   Pattern C — date_divider_header at CoordinatorLayout level (pre-scan)
// */
//object WhatsAppExtractor {
//
//    // ── Nodes that indicate a pinned/system banner — skip these rows ───────
//    private val BANNER_IDS = setOf(
//        "com.whatsapp:id/pinnedMessagesBanner",
//        "com.whatsapp:id/pinnedMessagesBanner_content",
//        "com.whatsapp:id/pinnedMessagesBanner_pinned_icon"
//    )
//
//    private val SYSTEM_IDS = setOf(
//        "com.whatsapp:id/info",
//        "com.whatsapp:id/conversation_contact_status"
//    )
//
//    // ── Text values that are UI chrome, not real messages ──────────────────
//    private val IGNORE_TEXT = setOf(
//        "type a message", "type a message…",
//        "typing…", "typing", "online", "offline",
//        "last seen", "last seen recently",
//        "this message was deleted", "you deleted this message",
//        "missed voice call", "missed video call", "tap to call back",
//        "yesterday", "today", "attach", "emoji", "sticker", "gif", "camera",
//        "you pinned a message",
//        "messages and calls are end-to-end encrypted."
//    )
//
//    // ── Main entry point ───────────────────────────────────────────────────
//    fun extract(root: AccessibilityNodeInfo): List<ChatMessageData> {
//        val messages = mutableListOf<ChatMessageData>()
//
//        // Pattern C: pre-scan CoordinatorLayout for date_divider_header
//        var coordinatorDateCal: Calendar? = null
//        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/date_divider_header")
//            ?.firstOrNull()?.text?.toString()?.trim()?.let { divText ->
//                coordinatorDateCal = parseDateDivider(divText)
//                if (coordinatorDateCal != null)
//                    Log.d(TAG, "📅 [WA] Coordinator date: [$divText] → ${ISO_FMT.format(coordinatorDateCal!!.time)}")
//            }
//
//        val listNode = root.findAccessibilityNodeInfosByViewId("android:id/list")
//            ?.firstOrNull() ?: run {
//            Log.w(TAG, "⚠️ [WA] android:id/list not found")
//            return messages
//        }
//
//        // Walk list IN ORDER — date context must be tracked sequentially
//        var currentDateCal: Calendar = coordinatorDateCal ?: Calendar.getInstance()
//
//        for (i in 0 until listNode.childCount) {
//            val listItem = listNode.getChild(i) ?: continue
//            val newDate  = processRow(listItem, messages, currentDateCal)
//            if (newDate != null) {
//                currentDateCal = newDate
//                Log.d(TAG, "📅 [WA] Date context → ${ISO_FMT.format(currentDateCal.time)}")
//            }
//        }
//
//        val result = messages
//            .filter { parseIsoToMs(it.time) > 0L && it.message.length >= 2 }
//            .distinctBy { "${it.sender}|${it.message.trim().lowercase()}|${it.time}" }
//            .sortedBy { parseIsoToMs(it.time) }
//
//        Log.d(TAG, "📊 [WA] Extracted: raw=${messages.size} → filtered=${result.size}")
//        return result
//    }
//
//    // ── Extract contact name from toolbar ─────────────────────────────────
//    fun getContactName(root: AccessibilityNodeInfo): String {
//        val n = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
//        if (!n.isNullOrEmpty()) return n.firstOrNull()?.text?.toString()?.trim() ?: ""
//        return root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/toolbar_title_text_view")
//            ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
//    }
//
//    // ── Process one list row, return updated date context if a divider found
//    private fun processRow(
//        listItem: AccessibilityNodeInfo,
//        messages: MutableList<ChatMessageData>,
//        currentDateCal: Calendar
//    ): Calendar? {
//        if (isInsideBanner(listItem)) return null
//
//        val viewId = listItem.viewIdResourceName ?: ""
//
//        // Pattern B: standalone date divider row
//        if (viewId == "com.whatsapp:id/conversation_row_date_divider") {
//            val rawText = listItem.text?.toString()?.trim() ?: ""
//            return if (rawText.isNotEmpty()) parseDateDivider(rawText) else null
//        }
//
//        if (viewId == "com.whatsapp:id/info") return null
//
//        // Normal message row — scan children
//        var updatedDate : Calendar? = null
//        var msgText     : String?   = null
//        var timeStr     : String?   = null
//        var sender                  = "other"
//        var msgY                    = 0
//        var localDate   : Calendar? = null
//        var fileName    : String?   = null
//        var fileType    : String?   = null
//
//        for (j in 0 until listItem.childCount) {
//            val child    = listItem.getChild(j) ?: continue
//            val childId  = child.viewIdResourceName ?: ""
//            val childTxt = child.text?.toString()?.trim() ?: ""
//
//            when (childId) {
//
//                // Pattern A: date divider inside a message ViewGroup
//                "com.whatsapp:id/conversation_row_date_divider" -> {
//                    if (childTxt.isNotEmpty()) {
//                        parseDateDivider(childTxt)?.let { cal ->
//                            localDate = cal; updatedDate = cal
//                        }
//                    }
//                }
//
//                "com.whatsapp:id/message_text" -> {
//                    msgText = childTxt
//                    msgY    = Rect().also { child.getBoundsInScreen(it) }.centerY()
//                }
//
//                // Time node — extract time even if it has trailing ✓ or unicode
//                "com.whatsapp:id/date" -> {
//                    val extracted = extractTimeFromText(cleanTimestampString(childTxt))
//                    if (extracted != null) timeStr = extracted
//                    else Log.v(TAG, "⏱ [WA] No time in: [$childTxt]")
//                }
//
//                // Presence of status node = this message was sent by me
//                "com.whatsapp:id/status" -> { sender = "me" }
//
//                // Image with caption
//                "com.whatsapp:id/caption_text" -> {
//                    if (childTxt.isNotEmpty()) {
//                        msgText = "[Image] $childTxt"
//                        msgY    = Rect().also { child.getBoundsInScreen(it) }.centerY()
//                    }
//                }
//
//                // File/document attachment
//                "com.whatsapp:id/content" -> {
//                    for (k in 0 until child.childCount) {
//                        val gc = child.getChild(k) ?: continue
//                        when (gc.viewIdResourceName) {
//                            "com.whatsapp:id/title"     -> fileName = gc.text?.toString()?.trim()
//                            "com.whatsapp:id/file_type" -> fileType = gc.text?.toString()?.trim()
//                        }
//                    }
//                    msgY = Rect().also { child.getBoundsInScreen(it) }.centerY()
//                }
//            }
//        }
//
//        // Fallback: use filename if no message text
//        if (msgText == null && !fileName.isNullOrEmpty()) {
//            msgText = if (!fileType.isNullOrEmpty()) "[$fileType] $fileName" else "[File] $fileName"
//        }
//
//        // Decide whether to add or skip, and why
//        return when {
//            msgText.isNullOrEmpty() -> {
//                Log.v(TAG, "⏭ [WA] SKIP: no msgText (sender=$sender timeStr=$timeStr)")
//                updatedDate
//            }
//            timeStr.isNullOrEmpty() -> {
//                Log.d(TAG, "⚠️ [WA] SKIP: no timeStr | text=[${msgText?.take(30)}]")
//                updatedDate
//            }
//            else -> {
//                val effectiveDateCal = localDate ?: currentDateCal
//                val fullTimestamp    = combineDateTime(effectiveDateCal, timeStr)
//                val msgLower         = msgText.lowercase().trim()
//
//                when {
//                    fullTimestamp.isEmpty() ->
//                        Log.d(TAG, "⚠️ [WA] SKIP: time parse fail | timeStr=[$timeStr]")
//                    msgLower in IGNORE_TEXT ->
//                        Log.v(TAG, "⏭ [WA] SKIP: ignore list | text=[${msgText.take(30)}]")
//                    else -> {
//                        Log.d(TAG, "✅ [WA] ADD [$sender] [${msgText.take(40)}] @ $fullTimestamp")
//                        messages.add(ChatMessageData(sender, msgText, fullTimestamp, msgY))
//                    }
//                }
//                updatedDate
//            }
//        }
//    }
//
//    // ── Walk up the tree to check if a node is inside a pinned/system banner
//    private fun isInsideBanner(node: AccessibilityNodeInfo): Boolean {
//        var current: AccessibilityNodeInfo? = node.parent
//        var depth = 0
//        while (current != null && depth < 10) {
//            if ((current.viewIdResourceName ?: "") in (BANNER_IDS + SYSTEM_IDS)) return true
//            current = current.parent; depth++
//        }
//        return false
//    }
//}
