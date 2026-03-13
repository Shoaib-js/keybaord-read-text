//package helium314.keyboard.accessibility
//
//import android.graphics.Rect
//import android.util.Log
//import android.view.accessibility.AccessibilityNodeInfo
//import helium314.keyboard.accessibility.ChatUtils.TAG
//import helium314.keyboard.accessibility.ChatUtils.ISO_FMT
//import helium314.keyboard.accessibility.ChatUtils.cleanTimestampString
//import helium314.keyboard.accessibility.ChatUtils.formatIso
//import helium314.keyboard.accessibility.ChatUtils.isTimestampText
//import helium314.keyboard.accessibility.ChatUtils.parseTime
//import java.text.SimpleDateFormat
//import java.util.Calendar
//import java.util.Locale
//
///**
// * InstagramExtractor
// *
// * Extracts chat messages from an Instagram Direct accessibility tree snapshot.
// *
// * Called by ChatAccessibilityService when pkg == "com.instagram.android".
// *
// * Public API:
// *   InstagramExtractor.extract(root, screenWidth)   → List<ChatMessageData>
// *   InstagramExtractor.getContactName(root)         → String
// *
// * How it works:
// *   Instagram DM layout doesn't use a flat list with date dividers like WhatsApp.
// *   Instead, timestamps appear inline as sibling nodes. We traverse the full tree
// *   and track the last seen timestamp as we go (currentTime propagates down).
// *
// *   Sender is determined by X position:
// *     centerX > 55% of screen width → "me" (right-aligned = sent)
// *     centerX ≤ 55%                 → "other" (left-aligned = received)
// */
//object InstagramExtractor {
//
//    // ── View IDs that contain actual message text ──────────────────────────
//    private val MSG_IDS = setOf(
//        "com.instagram.android:id/direct_text_message_text_view"
//    )
//
//    // ── Text values that are UI chrome, not real messages ──────────────────
//    private val IGNORE_TEXT = setOf(
//        "message", "message…", "typing…", "typing",
//        "seen", "delivered", "active now", "active today",
//        "need to fix a typo?",
//        "you can edit a message for up to 15 minutes. tap and hold a message to start editing.",
//        "view transcription", "inquire", "view profile",
//        "send message", "send a message", "voice message",
//        "react to this message", "reply"
//    )
//
//    // ── Main entry point ───────────────────────────────────────────────────
//    fun extract(root: AccessibilityNodeInfo, screenWidth: Int): List<ChatMessageData> {
//        val result = mutableListOf<ChatMessageData>()
//        traverseTree(root, result, screenWidth, currentTime = "")
//        val filtered = result.filter { it.message.length >= 2 && it.message.lowercase() !in IGNORE_TEXT }
//        Log.d(TAG, "📊 [IG] Extracted: raw=${result.size} → filtered=${filtered.size}")
//        return filtered
//    }
//
//    // ── Extract contact name from header ──────────────────────────────────
//    fun getContactName(root: AccessibilityNodeInfo): String {
//        return root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/header_title")
//            ?.firstOrNull()?.text?.toString()?.trim() ?: "Unknown"
//    }
//
//    // ── Recursive tree traversal — timestamp propagates down through siblings
//    private fun traverseTree(
//        node: AccessibilityNodeInfo?,
//        result: MutableList<ChatMessageData>,
//        screenWidth: Int,
//        currentTime: String,
//        depth: Int = 0
//    ) {
//        if (node == null || depth > 20) return
//
//        val text    = node.text?.toString()?.trim() ?: ""
//        val viewId  = node.viewIdResourceName ?: ""
//
//        // Update currentTime if this node looks like a timestamp
//        var timeToUse = currentTime
//        val cleaned = cleanTimestampString(text)
//        if (text.isNotEmpty() && isTimestampText(cleaned)) {
//            val normalized = normalizeTimestamp(cleaned)
//            if (normalized.isNotEmpty()) {
//                timeToUse = normalized
//                Log.v(TAG, "⏱ [IG] Timestamp found: [$cleaned] → $timeToUse")
//            }
//        }
//
//        // If this node is a message view, capture it
//        if (viewId in MSG_IDS && text.isNotEmpty() && timeToUse.isNotEmpty()) {
//            val rect = Rect().also { node.getBoundsInScreen(it) }
//            val sender = if (rect.centerX() > screenWidth * 0.55) "me" else "other"
//            Log.d(TAG, "✅ [IG] ADD [$sender] [${text.take(40)}] @ $timeToUse")
//            result.add(ChatMessageData(
//                sender  = sender,
//                message = text,
//                time    = timeToUse,
//                y       = rect.centerY()
//            ))
//        }
//
//        // Recurse — pass current timeToUse down to children
//        for (i in 0 until node.childCount) {
//            traverseTree(node.getChild(i), result, screenWidth, timeToUse, depth + 1)
//        }
//    }
//
//    // ── Normalize Instagram timestamp strings → ISO 8601 ──────────────────
//    // Instagram shows: "Today 4:08 PM", "Yesterday 11:30 AM",
//    //                  "3:45 PM", "Mar 5 2:00 PM"
//    private fun normalizeTimestamp(raw: String): String {
//        val cleaned = cleanTimestampString(raw)
//        val lower   = cleaned.lowercase()
//        val today   = Calendar.getInstance()
//
//        return try {
//            when {
//                lower.startsWith("today") -> {
//                    val timePart = cleaned.substringAfter(" ").trim()
//                    formatIso(parseTime(timePart, today) ?: return "")
//                }
//                lower.startsWith("yesterday") -> {
//                    val yday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
//                    val timePart = cleaned.substringAfter(" ").trim()
//                    formatIso(parseTime(timePart, yday) ?: return "")
//                }
//                lower.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) -> {
//                    formatIso(parseTime(cleaned, today) ?: return "")
//                }
//                lower.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec).*", RegexOption.IGNORE_CASE)) -> {
//                    val p = try { SimpleDateFormat("MMM d h:mm a", Locale.ENGLISH).parse(cleaned) }
//                    catch (e: Exception) { null } ?: return ""
//                    val c = Calendar.getInstance().apply {
//                        time = p
//                        set(Calendar.YEAR, today.get(Calendar.YEAR))
//                    }
//                    formatIso(c)
//                }
//                else -> ""
//            }
//        } catch (e: Exception) { "" }
//    }
//}
