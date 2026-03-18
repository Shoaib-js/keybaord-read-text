package helium314.keyboard.accessibility.extractors.instagram

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * InstagramTimestampParser
 *
 * Converts Instagram's display timestamp strings → ISO-8601.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ALL FORMATS VERIFIED FROM REAL TREE LOGS:
 * ══════════════════════════════════════════════════════════════════════════
 *
 *   Format                  Example               Source log
 *   ─────────────────────   ──────────────────    ──────────
 *   "Today H:mm a"          "Today 11:35 am"      Log 1, Log 2
 *   "Yesterday H:mm a"      "Yesterday 11:03 pm"  Log 2
 *   "d MMM, H:mm a"         "25 Nov, 11:00 pm"    Log 3  ← NEW
 *   "MMM d H:mm a"          "Mar 5 2:00 PM"       general
 *   "H:mm a"                "3:45 PM"             general (time-only)
 *
 * ══════════════════════════════════════════════════════════════════════════
 * IMPORTANT NOTES:
 * ══════════════════════════════════════════════════════════════════════════
 *
 *   • Instagram uses lowercase am/pm. Java's SimpleDateFormat with
 *     Locale.ENGLISH is case-insensitive for the "a" marker when parsing.
 *   • Unicode narrow-space (U+202F) and non-breaking space (U+00A0) must
 *     be stripped before parsing — Instagram injects these in timestamps.
 *     The caller (InstagramExtractor) handles this via cleanTimestamp().
 *   • When the format has no year component, the current year is used.
 *   • "Today" / "Yesterday" are relative, so they resolve at parse time.
 */
internal object InstagramTimestampParser {

    private const val TAG = "CHAT_DEBUG"

    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    // Pre-compiled patterns for fast format identification
    private val RE_TIME_ONLY  = Regex("""^\d{1,2}:\d{2}(\s?(am|pm))?$""", RegexOption.IGNORE_CASE)
    private val RE_D_MMM      = Regex("""^\d{1,2}\s+[a-z]{3},.*""",        RegexOption.IGNORE_CASE)
    private val RE_MMM_D      = Regex("""^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\s+\d{1,2}.*""", RegexOption.IGNORE_CASE)

    /**
     * Normalises a cleaned Instagram timestamp string to ISO-8601.
     *
     * @param raw A cleaned timestamp string (unicode whitespace already removed).
     * @return ISO-8601 string, e.g. "2026-03-18T11:35:00", or "" on failure.
     */
    fun normalize(raw: String): String {
        val lower = raw.lowercase(Locale.ENGLISH)
        val today = Calendar.getInstance()

        return try {
            when {

                // ── "Today 11:35 am" ─────────────────────────────────────
                lower.startsWith("today ") -> {
                    val timePart = raw.substringAfter(" ").trim()
                    val cal = parseTime(timePart, today) ?: return ""
                    formatIso(cal)
                }

                // ── "Yesterday 11:03 pm" ─────────────────────────────────
                lower.startsWith("yesterday ") -> {
                    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                    val timePart  = raw.substringAfter(" ").trim()
                    val cal = parseTime(timePart, yesterday) ?: return ""
                    formatIso(cal)
                }

                // ── "25 Nov, 11:00 pm"  (d MMM, H:mm a) ─────────────────
                // Verified from Log 3 — "25 Nov, 11:00 pm"
                RE_D_MMM.matches(lower) -> {
                    // Remove comma so "25 Nov, 11:00 pm" → "25 Nov 11:00 pm"
                    val cleaned = raw.replace(",", " ").replace("  ", " ").trim()
                    parseAbsDate(cleaned, listOf("d MMM h:mm a", "d MMM hh:mm a"), today)
                }

                // ── "Mar 5 2:00 PM"  (MMM d H:mm a) ─────────────────────
                RE_MMM_D.matches(lower) -> {
                    val cleaned = raw.replace(",", " ").replace("  ", " ").trim()
                    parseAbsDate(cleaned, listOf("MMM d h:mm a", "MMM d hh:mm a"), today)
                }

                // ── "3:45 PM" or "11:35 am" (time-only) ─────────────────
                RE_TIME_ONLY.matches(lower) -> {
                    val cal = parseTime(raw, today) ?: return ""
                    formatIso(cal)
                }

                else -> {
                    Log.v(TAG, "⚠️ [IG] Timestamp format not recognised: [$raw]")
                    ""
                }
            }
        } catch (e: Exception) {
            Log.v(TAG, "⚠️ [IG] Timestamp parse exception: [$raw] — ${e.message}")
            ""
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Private helpers
    // ════════════════════════════════════════════════════════════════════

    /**
     * Tries a list of [formats] to parse [dateTimeStr] as an absolute date.
     * The year is set to the current year when the format has no year component.
     * Returns ISO string or "" on failure.
     */
    private fun parseAbsDate(
        dateTimeStr: String,
        formats: List<String>,
        today: Calendar
    ): String {
        for (fmt in formats) {
            try {
                val sdf    = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val parsed = sdf.parse(dateTimeStr) ?: continue
                val cal    = Calendar.getInstance().apply {
                    time = parsed
                    // If format has no year → use current year
                    if (!fmt.contains('y') && !fmt.contains('Y')) {
                        set(Calendar.YEAR, today.get(Calendar.YEAR))
                    }
                }
                return formatIso(cal)
            } catch (_: Exception) { /* try next format */ }
        }
        Log.v(TAG, "⚠️ [IG] parseAbsDate failed for [$dateTimeStr] formats=$formats")
        return ""
    }

    /**
     * Parses a time string ("11:35 am", "23:05", etc.) into a Calendar
     * whose date components are copied from [base].
     */
    private fun parseTime(timeStr: String, base: Calendar): Calendar? {
        for (fmt in listOf("h:mm a", "hh:mm a", "H:mm", "HH:mm")) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.ENGLISH)
                sdf.isLenient = false
                val parsed = sdf.parse(timeStr.trim()) ?: continue
                val result = base.clone() as Calendar
                val tmp    = Calendar.getInstance().apply { time = parsed }
                result.set(Calendar.HOUR_OF_DAY, tmp.get(Calendar.HOUR_OF_DAY))
                result.set(Calendar.MINUTE,      tmp.get(Calendar.MINUTE))
                result.set(Calendar.SECOND,      0)
                result.set(Calendar.MILLISECOND, 0)
                return result
            } catch (_: Exception) { /* try next */ }
        }
        Log.v(TAG, "⚠️ [IG] parseTime failed for [$timeStr]")
        return null
    }

    private fun formatIso(cal: Calendar): String = ISO_FMT.format(cal.time)
}
