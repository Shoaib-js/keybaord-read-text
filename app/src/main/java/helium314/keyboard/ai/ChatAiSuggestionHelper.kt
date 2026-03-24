/*
 * ChatAiSuggestionHelper.kt — ON-DEMAND VERSION
 *
 * Three callbacks:
 *   onSuggestionsReady — API returned suggestions → show RESULT
 *   onEmpty            — no messages found → show EMPTY (no API call)
 *   onError            — API/network error → show error text in panel
 */
package helium314.keyboard.ai

import android.util.Log
import helium314.keyboard.accessibility.ChatAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ChatAiSuggestionHelper(
    private val backendUrl: String,
    private val onSuggestionsReady: (List<String>) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onEmpty: (() -> Unit)? = null   // called when no messages found
) {
    companion object {
        private const val TAG = "ChatAiHelper"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT    = 20_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isRequestInFlight = false

    fun requestSuggestions(tone: String) {
        if (isRequestInFlight) {
            Log.d(TAG, "Request in flight — ignoring duplicate")
            return
        }

        val service = ChatAccessibilityService.instance
        if (service == null) {
            onError("Accessibility service not running. Enable LeanType in Accessibility Settings.")
            return
        }

        val pkg = service.currentPackage
        if (pkg.isBlank()) {
            onEmpty?.invoke() ?: onError("Open a WhatsApp or Instagram chat first.")
            return
        }

        isRequestInFlight = true
        Log.d(TAG, "🔍 On-demand extraction | pkg=$pkg | tone=$tone")

        scope.launch {
            try {
                val result = OnDemandMessageExtractor.extractNow(service, pkg)

                // ── No result at all (accessibility error) ─────────────────
                if (result == null) {
                    onError("Could not read screen. Check Accessibility permission.")
                    return@launch
                }

                // ── Empty result: wrong screen or no messages visible ───────
                // Do NOT call API. Show EMPTY state — clean UX, no error color.
                if (result.messageCount == 0) {
                    Log.d(TAG, "📭 No messages — showing empty state (no API call)")
//                    onEmpty?.invoke() ?: onError("Open a chat conversation with messages, then tap AI.")
                    onEmpty?.invoke() ?: onError("Open a chat with messages")
                    return@launch
                }

                Log.d(TAG, "✅ Extracted ${result.messageCount} messages for '${result.chatWith}'")

                val payload = buildPayload(result.appName, result.chatWith, tone, result.messagesJson)
                Log.d(TAG, "📤 Payload: $payload")

                val response = postToBackend(payload)
                Log.d(TAG, "📥 Response: $response")

                val suggestions = parseResponse(response)
                onSuggestionsReady(suggestions)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error: ${e.message}")
                onError(e.message ?: "Unknown error")
            } finally {
                isRequestInFlight = false
            }
        }
    }

    private fun buildPayload(app: String, chatWith: String, tone: String, messages: String) =
        JSONObject().apply {
            put("app", app)
            put("chat_with", chatWith)
            put("tone", tone)
            put("messages", JSONArray(messages))
        }.toString()

    private fun postToBackend(payload: String): String {
        val conn = URL(backendUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true; conn.doInput = true
            conn.connectTimeout = CONNECT_TIMEOUT; conn.readTimeout = READ_TIMEOUT
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload); it.flush() }
            val code = conn.responseCode
            if (code !in 200..299) throw Exception("HTTP $code: ${conn.errorStream?.bufferedReader()?.readText()}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally { conn.disconnect() }
    }

    private fun parseResponse(json: String): List<String> {
        val obj = JSONObject(json)
        if (obj.optString("status") != "success") throw Exception(obj.optString("error", "Backend error"))
        val arr  = obj.getJSONArray("suggestions")
        val list = (0 until arr.length()).map { arr.getString(it).trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) throw Exception("Empty suggestions from backend")
        return list
    }

    fun register() {}
    fun unregister() {}
    fun hasContext(): Boolean = ChatAccessibilityService.instance != null
}
