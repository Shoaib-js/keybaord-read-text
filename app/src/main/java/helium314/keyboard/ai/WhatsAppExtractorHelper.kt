/*
 * WhatsAppExtractorHelper.kt
 *
 * Stateless 5-stage WhatsApp message extractor.
 * Called by OnDemandMessageExtractor with the correct WhatsApp window root.
 */
package helium314.keyboard.ai

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import helium314.keyboard.accessibility.ChatMessageData
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object WhatsAppExtractorHelper {

    private const val TAG = "WaExtractor"
    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    private sealed class TreeRow {
        data class Divider(val cal: Calendar) : TreeRow()
        data class Msg(val pending: PendingMsg) : TreeRow()
    }

    private data class PendingMsg(
        val sender: String,
        val text: String,
        val rawTimeStr: String,
        val y: Int
    )

    private data class DateGroup(
        val dateCal: Calendar,
        val msgs: MutableList<PendingMsg> = mutableListOf()
    )

    private val BANNER_IDS = setOf(
        "com.whatsapp:id/pinnedMessagesBanner",
        "com.whatsapp:id/pinnedMessagesBanner_content",
        "com.whatsapp:id/pinnedMessagesBanner_pinned_icon"
    )
    private val SYSTEM_IDS = setOf(
        "com.whatsapp:id/info",
        "com.whatsapp:id/conversation_contact_status"
    )
    private val IGNORE_TEXT = setOf(
        "type a message", "type a message…", "typing…", "typing",
        "online", "offline", "last seen", "last seen recently",
        "this message was deleted", "you deleted this message",
        "missed voice call", "missed video call", "tap to call back",
        "seen", "delivered", "sent", "yesterday", "today",
        "attach", "emoji", "sticker", "gif", "camera",
        "you pinned a message",
        "messages and calls are end-to-end encrypted."
    )
    private val DAY_OF_WEEK_MAP = mapOf(
        "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY
    )

    // ══════════════════════════════════════════════════════════════
    // PUBLIC ENTRY POINT
    // ══════════════════════════════════════════════════════════════

    fun extract(root: AccessibilityNodeInfo): List<ChatMessageData> {

        // DEBUG: confirm we have the right window
        Log.d(TAG, "🌳 Root pkg=${root.packageName} childCount=${root.childCount}")

        // Pre-scan — Pattern C coordinator header
        var coordinatorDateCal: Calendar? = null
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/date_divider_header")
            ?.firstOrNull()?.text?.toString()?.trim()?.let { txt ->
                coordinatorDateCal = parseDateDivider(txt)
                Log.d(TAG, "📅 Coordinator header: $txt")
            }

        // Find list — try multiple IDs (WhatsApp version differences)
        val listNode = findListNode(root) ?: run {
            Log.w(TAG, "❌ No list node found in root pkg=${root.packageName}")
            logTopLevelNodes(root)
            return emptyList()
        }
        Log.d(TAG, "✅ List node found: id=${listNode.viewIdResourceName} children=${listNode.childCount}")

        // ── Conversation screen guard ──────────────────────────────────────
        // WhatsApp HOME screen (chat list) also has android:id/list but with
        // conversation_item rows (no message_text). Detect and bail early.
        // A real chat conversation has at least some message_text nodes.
        if (listNode.childCount <= 4) {
            var hasMessageNode = false
            for (i in 0 until listNode.childCount) {
                val child = listNode.getChild(i) ?: continue
                val found = child.findAccessibilityNodeInfosByViewId(
                    "com.whatsapp:id/message_text")?.isNotEmpty() == true
                child.recycle()
                if (found) { hasMessageNode = true; break }
            }
            if (!hasMessageNode) {
                Log.w(TAG, "⚠️ Wrong screen — no message_text in ${listNode.childCount} children. Open a chat conversation!")
                return emptyList()
            }
        }
        // ──────────────────────────────────────────────────────────────────

        // ── STAGE 1 ────────────────────────────────────────────────
        val treeRows = mutableListOf<TreeRow>()
        for (i in 0 until listNode.childCount) {
            val item = listNode.getChild(i) ?: continue
            treeRows.addAll(collectTreeRows(item))
            item.recycle()
        }
        Log.d(TAG, "📋 [S1] ${treeRows.size} rows")

        // ── STAGE 2: Orphan date ───────────────────────────────────
        val allDividers = treeRows.filterIsInstance<TreeRow.Divider>()
        val orphanDate: Calendar = coordinatorDateCal
            ?: allDividers.firstOrNull()?.cal?.let { first ->
                (first.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            }
            ?: Calendar.getInstance()
        Log.d(TAG, "📅 [S2] ${allDividers.size} dividers | orphan=${ISO_FMT.format(orphanDate.time)}")

        // ── STAGE 3: Group ─────────────────────────────────────────
        val groups = mutableListOf<DateGroup>()
        var current = DateGroup(orphanDate.clone() as Calendar)
        groups.add(current)
        for (row in treeRows) {
            when (row) {
                is TreeRow.Divider -> {
                    current = DateGroup(row.cal.clone() as Calendar)
                    groups.add(current)
                }
                is TreeRow.Msg -> current.msgs.add(row.pending)
            }
        }

        // ── STAGES 4+5: Timestamps + flatten ──────────────────────
        val result = mutableListOf<ChatMessageData>()
        for (group in groups.sortedBy { it.dateCal.timeInMillis }) {
            if (group.msgs.isEmpty()) continue
            val withTs = group.msgs.mapNotNull { pending ->
                val iso = combineDateTime(group.dateCal, pending.rawTimeStr)
                if (iso.isEmpty()) return@mapNotNull null
                if (pending.text.lowercase().trim() in IGNORE_TEXT) return@mapNotNull null
                ChatMessageData(pending.sender, pending.text, iso, pending.y)
            }
            result.addAll(withTs.sortedBy { parseIsoToMs(it.time) })
        }

        val filtered = result.filter { parseIsoToMs(it.time) > 0L && it.message.isNotBlank() }
        Log.d(TAG, "📊 [S5] ${filtered.size} messages extracted")
        return filtered
    }

    // ── Find list node — try multiple IDs ─────────────────────────
    private fun findListNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Primary: standard ListView ID
        root.findAccessibilityNodeInfosByViewId("android:id/list")
            ?.firstOrNull()?.let { return it }
        // Fallback 1: WhatsApp-specific conversation list
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_list")
            ?.firstOrNull()?.let {
                Log.d(TAG, "Using fallback: conversation_list")
                return it
            }
        // Fallback 2: RecyclerView by class name scan
        return findByClassName(root, "androidx.recyclerview.widget.RecyclerView")
            .firstOrNull()?.also { Log.d(TAG, "Using fallback: RecyclerView by class") }
    }

    private fun findByClassName(node: AccessibilityNodeInfo, className: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        if (node.className?.toString() == className) results.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            results.addAll(findByClassName(child, className))
        }
        return results
    }

    private fun logTopLevelNodes(root: AccessibilityNodeInfo) {
        Log.d(TAG, "Top-level children of root:")
        for (i in 0 until minOf(root.childCount, 8)) {
            val child = root.getChild(i) ?: continue
            Log.d(TAG, "  [$i] id=${child.viewIdResourceName} class=${child.className} children=${child.childCount}")
            child.recycle()
        }
    }

    // ── Stage 1 row classifier ─────────────────────────────────────

    private fun collectTreeRows(item: AccessibilityNodeInfo): List<TreeRow> {
        val viewId = item.viewIdResourceName ?: ""
        if (viewId in BANNER_IDS || viewId in SYSTEM_IDS) return emptyList()

        if (viewId == "com.whatsapp:id/conversation_row_date_divider") {
            val txt = item.text?.toString()?.trim() ?: return emptyList()
            val cal = parseDateDivider(txt) ?: return emptyList()
            return listOf(TreeRow.Divider(cal))
        }

        val rows = mutableListOf<TreeRow>()
        var inlineDivCal: Calendar? = null
        var msgText: String? = null
        var rawTimeStr: String? = null
        var sender = "other"
        var msgY = 0
        var fileName: String? = null
        var fileType: String? = null

        for (j in 0 until item.childCount) {
            val child = item.getChild(j) ?: continue
            val childId  = child.viewIdResourceName ?: ""
            val childTxt = child.text?.toString()?.trim() ?: ""
            val cleaned  = cleanTs(childTxt)

            when (childId) {
                "com.whatsapp:id/conversation_row_date_divider" ->
                    if (childTxt.isNotEmpty()) inlineDivCal = parseDateDivider(childTxt)
                "com.whatsapp:id/message_text" -> {
                    msgText = childTxt
                    val r = Rect().also { child.getBoundsInScreen(it) }
                    msgY = r.centerY()
                }
                "com.whatsapp:id/date" ->
                    if (isTimeOnly(cleaned)) rawTimeStr = cleaned
                "com.whatsapp:id/status" -> sender = "me"
                "com.whatsapp:id/caption_text" -> {
                    if (childTxt.isNotEmpty()) {
                        msgText = "[Image] $childTxt"
                        val r = Rect().also { child.getBoundsInScreen(it) }
                        msgY = r.centerY()
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
                    val r = Rect().also { child.getBoundsInScreen(it) }
                    msgY = r.centerY()
                }
            }
            child.recycle()
        }

        if (msgText == null && !fileName.isNullOrEmpty())
            msgText = if (!fileType.isNullOrEmpty()) "[$fileType] $fileName" else "[File] $fileName"

        if (inlineDivCal != null) rows.add(TreeRow.Divider(inlineDivCal))
        if (!msgText.isNullOrEmpty() && !rawTimeStr.isNullOrEmpty())
            rows.add(TreeRow.Msg(PendingMsg(sender, msgText, rawTimeStr, msgY)))

        return rows
    }

    // ── Date / time utilities ──────────────────────────────────────

    private fun parseDateDivider(text: String): Calendar? {
        val trimmed = text.trim()
        val lower   = trimmed.lowercase()
        if (lower == "today")     return Calendar.getInstance()
        if (lower == "yesterday") return Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

        DAY_OF_WEEK_MAP[lower]?.let { targetDay ->
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
            repeat(7) {
                if (cal.get(Calendar.DAY_OF_WEEK) == targetDay) return cal
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
        }

        val formats = listOf(
            "d MMMM yyyy","MMMM d, yyyy","MMM d, yyyy","d MMM yyyy",
            "d/M/yy","dd/MM/yyyy","M/d/yyyy","yyyy-MM-dd",
            "d MMMM","MMMM d","MMM d"
        )
        val today   = Calendar.getInstance()
        val locales = listOf(Locale.ENGLISH, Locale.getDefault()).distinct()
        for (fmt in formats) for (locale in locales) {
            try {
                val sdf    = SimpleDateFormat(fmt, locale).apply { isLenient = false }
                val parsed = sdf.parse(trimmed) ?: continue
                val cal    = Calendar.getInstance().apply { time = parsed }
                if (!fmt.contains("y", ignoreCase = true))
                    cal.set(Calendar.YEAR, today.get(Calendar.YEAR))
                return cal
            } catch (_: Exception) {}
        }
        return null
    }

    private fun combineDateTime(dateCal: Calendar, timeStr: String): String {
        val cal = parseTime(cleanTs(timeStr), dateCal) ?: return ""
        return ISO_FMT.format(cal.time)
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
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseIsoToMs(iso: String): Long =
        try { ISO_FMT.parse(iso)?.time ?: 0L } catch (_: Exception) { 0L }

    private fun cleanTs(raw: String) =
        raw.replace('\u202F', ' ').replace('\u00A0', ' ').trim()

    private fun isTimeOnly(text: String) =
        text.lowercase().matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE))
}
