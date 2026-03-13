//package helium314.keyboard.accessibility
//
//import android.util.Log
//import java.text.SimpleDateFormat
//import java.util.*
//
//// ══════════════════════════════════════════════════════════════
//// SHARED DATA MODEL
//// ══════════════════════════════════════════════════════════════
//
//data class ChatMessageData(
//    val sender:  String,   // "me" or "other"
//    val message: String,
//    val time:    String,   // ISO 8601: "2026-03-12T16:08:00"
//    val y:       Int = 0   // screen Y — kept for compat, not used in sorting
//)
//
//// ══════════════════════════════════════════════════════════════
//// SHARED UTILITIES
//// Used by both WhatsAppExtractor and InstagramExtractor
//// ══════════════════════════════════════════════════════════════
//
//object ChatUtils {
//
//    const val TAG = "CHAT_DEBUG"
//
//    val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
//
//    // Regex to EXTRACT time from any string — finds "1:07 pm", "13:07" etc.
//    // Regex.find() not full-match — handles "1:07 pm ✓✓", "13:07\u200E" correctly
//    private val TIME_EXTRACT_REGEX = Regex(
//        """(\d{1,2}:\d{2})\s*(am|pm)?""",
//        RegexOption.IGNORE_CASE
//    )
//
//    val DAY_OF_WEEK_MAP = mapOf(
//        "monday"    to Calendar.MONDAY,
//        "tuesday"   to Calendar.TUESDAY,
//        "wednesday" to Calendar.WEDNESDAY,
//        "thursday"  to Calendar.THURSDAY,
//        "friday"    to Calendar.FRIDAY,
//        "saturday"  to Calendar.SATURDAY,
//        "sunday"    to Calendar.SUNDAY
//    )
//
//    // ── seenKey: unique identity per message ──────────────────────────────
//    // Uses full ISO time (not just HH:mm) to avoid same-minute collisions
//    fun ChatMessageData.seenKey(): String {
//        val normText = message.trim().replace(Regex("\\s+"), " ").lowercase()
//        return "$sender|$normText|$time"
//    }
//
//    // ── Timestamp parsing ─────────────────────────────────────────────────
//    fun parseIsoToMs(isoTime: String): Long =
//        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (e: Exception) { 0L }
//
//    fun formatIso(cal: Calendar): String = ISO_FMT.format(cal.time)
//
//    // ── Clean invisible unicode chars that appear in WhatsApp/Instagram times
//    fun cleanTimestampString(raw: String): String =
//        raw.replace('\u202F', ' ')   // narrow no-break space
//            .replace('\u00A0', ' ')   // non-breaking space
//            .replace('\u200E', ' ')   // LTR mark
//            .replace('\u200F', ' ')   // RTL mark
//            .replace('\u200B', ' ')   // zero-width space
//            .trim()
//
//    // ── Extract time from a string — returns "H:mm am/pm" or "HH:mm" or null
//    fun extractTimeFromText(text: String): String? {
//        val match = TIME_EXTRACT_REGEX.find(text) ?: return null
//        val hhmm  = match.groupValues[1]
//        val ampm  = match.groupValues[2]
//        return if (ampm.isNotEmpty()) "$hhmm $ampm" else hhmm
//    }
//
//    // ── Parse "1:07 pm", "13:07" etc. into a Calendar at the given base date
//    fun parseTime(timeStr: String, base: Calendar): Calendar? {
//        val cleaned = cleanTimestampString(timeStr)
//        for (fmt in listOf("h:mm a", "h:mm", "HH:mm")) {
//            try {
//                val p = SimpleDateFormat(fmt, Locale.ENGLISH).parse(cleaned.trim()) ?: continue
//                val r = base.clone() as Calendar
//                val c = Calendar.getInstance().apply { time = p }
//                r.set(Calendar.HOUR_OF_DAY, c.get(Calendar.HOUR_OF_DAY))
//                r.set(Calendar.MINUTE,      c.get(Calendar.MINUTE))
//                r.set(Calendar.SECOND,      0)
//                return r
//            } catch (e: Exception) { continue }
//        }
//        return null
//    }
//
//    // ── Combine a date Calendar + time string → ISO timestamp string
//    fun combineDateTime(dateCal: Calendar, timeStr: String): String {
//        val cal = parseTime(cleanTimestampString(timeStr), dateCal) ?: return ""
//        return formatIso(cal)
//    }
//
//    // ── Parse "Today", "Yesterday", "Monday", "3 March 2026" → Calendar
//    fun parseDateDivider(text: String): Calendar? {
//        val lower = text.lowercase().trim()
//        return when {
//            lower == "today"     -> Calendar.getInstance()
//            lower == "yesterday" -> Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
//
//            DAY_OF_WEEK_MAP.containsKey(lower) -> {
//                val targetDay = DAY_OF_WEEK_MAP[lower]!!
//                val today = Calendar.getInstance()
//                val cal   = Calendar.getInstance()
//                var attempts = 0
//                while (cal.get(Calendar.DAY_OF_WEEK) != targetDay && attempts < 8) {
//                    cal.add(Calendar.DAY_OF_YEAR, -1); attempts++
//                }
//                // If result ended up in future (shouldn't happen but safety check)
//                if (cal.after(today)) cal.add(Calendar.DAY_OF_YEAR, -7)
//                cal
//            }
//
//            else -> {
//                for (fmt in listOf("d MMMM yyyy", "MMMM d, yyyy", "MMM d, yyyy", "d MMM yyyy")) {
//                    try {
//                        val parsed = SimpleDateFormat(fmt, Locale.ENGLISH).parse(text) ?: continue
//                        val cal = Calendar.getInstance()
//                        cal.time = parsed
//                        return cal
//                    } catch (e: Exception) { continue }
//                }
//                null
//            }
//        }
//    }
//
//    // ── isTimestampText: quick check if a string looks like a timestamp
//    fun isTimestampText(text: String): Boolean {
//        val l = text.lowercase()
//        return l.startsWith("today") || l.startsWith("yesterday") ||
//            l.matches(Regex("\\d{1,2}:\\d{2}(\\s?(am|pm))?", RegexOption.IGNORE_CASE)) ||
//            l.matches(Regex("(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\s+\\d{1,2}.*", RegexOption.IGNORE_CASE))
//    }
//}
