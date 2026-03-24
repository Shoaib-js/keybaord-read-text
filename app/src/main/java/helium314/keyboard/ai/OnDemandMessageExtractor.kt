/*
 * OnDemandMessageExtractor.kt
 *
 * Reads screen ONLY when user taps tone button. Zero background reading.
 * Uses getRootForPackage() to get the correct chat app window,
 * NOT rootInActiveWindow (which returns keyboard window when keyboard is open).
 */
package helium314.keyboard.ai

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import helium314.keyboard.accessibility.ChatAccessibilityService
import helium314.keyboard.accessibility.ChatMessageData
import helium314.keyboard.accessibility.extractors.instagram.InstagramExtractor
import java.text.SimpleDateFormat
import java.util.Locale

object OnDemandMessageExtractor {

    private const val TAG = "OnDemandExtractor"
    const val MAX_MESSAGES = 10

    private const val CLICK_DEBOUNCE_MS = 1500L
    private var lastClickMs = 0L

    data class ExtractionResult(
        val messagesJson: String,
        val chatWith: String,
        val appName: String,
        val messageCount: Int
    )

    private val ISO_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)

    fun extractNow(service: AccessibilityService, pkg: String): ExtractionResult? {

        // ── Debounce ──────────────────────────────────────────────
        val now = System.currentTimeMillis()
        if (now - lastClickMs < CLICK_DEBOUNCE_MS) {
            Log.d(TAG, "⏱ Debounced")
            return null
        }
        lastClickMs = now

        // ── Validate package ──────────────────────────────────────
        val appName = when {
            pkg.startsWith("com.whatsapp") -> "whatsapp"
            pkg == "com.instagram.android" -> "instagram"
            else -> {
                Log.w(TAG, "Unsupported app: $pkg")
                return null
            }
        }

        Log.d(TAG, "🔍 On-demand extraction started | app=$appName")

        // ── Get correct window ────────────────────────────────────
        // CRITICAL: DO NOT use service.rootInActiveWindow
        // When keyboard is open, rootInActiveWindow = keyboard window, NOT chat app.
        // Use getRootForPackage() which searches ALL windows via getWindows().
        val chatService = service as? ChatAccessibilityService
        val root: AccessibilityNodeInfo = try {
            val node = chatService?.getRootForPackage(pkg) ?: service.rootInActiveWindow
            if (node == null) {
                Log.w(TAG, "❌ No root window for $pkg")
                return null
            }
            Log.d(TAG, "🌳 Got root: pkg=${node.packageName} children=${node.childCount}")
            node
        } catch (e: Exception) {
            Log.e(TAG, "Root window error: ${e.message}")
            return null
        }

        return try {
            // ── Instagram DM screen check ─────────────────────────
            if (appName == "instagram" && !isInstagramDmScreen(root)) {
                Log.d(TAG, "Not an Instagram DM screen")
                return null
            }

            val chatWith = extractContactName(root, appName)
            Log.d(TAG, "👤 chatWith=$chatWith")

            val messages: List<ChatMessageData> = when (appName) {
                "whatsapp"  -> WhatsAppExtractorHelper.extract(root)
                "instagram" -> InstagramExtractor.extract(root, "")
                else        -> emptyList()
            }

            if (messages.isEmpty()) {
                Log.w(TAG, "No messages extracted from $appName — probably wrong screen")
                // Return empty result (not null) so caller can show a helpful error
                return ExtractionResult("[]", chatWith, appName, 0)
            }

            val recent = messages
                .sortedBy { parseIsoToMs(it.time) }
                .takeLast(MAX_MESSAGES)

            val json = buildJsonArray(recent)
            Log.d(TAG, "✅ Extracted ${recent.size} messages for '$chatWith'")
            ExtractionResult(json, chatWith, appName, recent.size)

        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun isInstagramDmScreen(root: AccessibilityNodeInfo): Boolean {
        val hasList = root.findAccessibilityNodeInfosByViewId(
            "com.instagram.android:id/message_list")?.isNotEmpty() == true
        if (!hasList) return false
        return root.findAccessibilityNodeInfosByViewId(
            "com.instagram.android:id/row_thread_composer_edittext")?.isNotEmpty() == true
    }

    private fun extractContactName(root: AccessibilityNodeInfo, appName: String): String {
        return when (appName) {
            "instagram" -> InstagramExtractor.getContactName(root)
            "whatsapp"  -> {
                root.findAccessibilityNodeInfosByViewId(
                    "com.whatsapp:id/conversation_contact_name")
                    ?.firstOrNull()?.text?.toString()?.trim()
                    ?: root.findAccessibilityNodeInfosByViewId(
                        "com.whatsapp:id/toolbar_title_text_view")
                        ?.firstOrNull()?.text?.toString()?.trim()
                    ?: "Unknown"
            }
            else -> "Unknown"
        }
    }

    private fun parseIsoToMs(isoTime: String): Long =
        try { ISO_FMT.parse(isoTime)?.time ?: 0L } catch (_: Exception) { 0L }

    private fun buildJsonArray(messages: List<ChatMessageData>): String {
        val sb = StringBuilder("[")
        messages.forEachIndexed { i, msg ->
            sb.append("{\"sequence\":${i+1},\"sender\":\"${msg.sender}\",")
            sb.append("\"message\":\"${escapeJson(msg.message)}\",\"time\":\"${msg.time}\"}")
            if (i < messages.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escapeJson(t: String) = t
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
}
