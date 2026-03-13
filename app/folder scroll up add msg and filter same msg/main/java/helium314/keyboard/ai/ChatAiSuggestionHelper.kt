// ══════════════════════════════════════════════════════════════════
// FILE LOCATION:
//   app/src/main/java/helium314/keyboard/ai/ChatAiSuggestionHelper.kt
//
// ACTION: REPLACE the ENTIRE existing file with this content.
// ══════════════════════════════════════════════════════════════════

package helium314.keyboard.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

/**
 * ChatAiSuggestionHelper
 *
 * AccessibilityService se chat messages receive karta hai.
 * User AI button press kare + tone select kare toh backend ko request bhejta hai.
 * Backend se 3 suggestions receive karke keyboard ko deta hai.
 *
 * Architecture:
 *   AccessibilityService → Broadcast → This Helper → Backend API → Suggestions → Keyboard UI
 */
class ChatAiSuggestionHelper(
    private val context: Context,
    private val backendUrl: String,              // Aapka backend URL, e.g. "https://yourserver.com/generate-reply"
    private val onSuggestionsReady: (List<String>) -> Unit,
    private val onError: (String) -> Unit = {}   // Optional error callback
) {

    companion object {
        private const val TAG = "ChatAiHelper"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT    = 20_000
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Stored chat context ──
    private var lastMessagesJson: String = "[]"
    private var lastPackageName: String  = ""
    private var lastChatWith: String     = ""

    // ════════════════════════════════════════
    // BROADCAST RECEIVER — AccessibilityService se messages receive karo
    // ════════════════════════════════════════

    private val chatContextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ChatAccessibilityService.ACTION_CHAT_CONTEXT) return

            val messagesJson = intent.getStringExtra(ChatAccessibilityService.EXTRA_MESSAGES) ?: "[]"
            val pkg          = intent.getStringExtra(ChatAccessibilityService.EXTRA_PACKAGE) ?: ""
            val chatWith     = intent.getStringExtra(ChatAccessibilityService.EXTRA_CHAT_WITH) ?: ""

            lastMessagesJson = messagesJson
            lastPackageName  = pkg
            lastChatWith     = chatWith

            Log.d(TAG, "📩 Context received: app=$pkg | chat_with=$chatWith")
        }
    }

    // ════════════════════════════════════════
    // REGISTER / UNREGISTER
    // ════════════════════════════════════════

    fun register() {
        val filter = IntentFilter(ChatAccessibilityService.ACTION_CHAT_CONTEXT)
        context.registerReceiver(chatContextReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "ChatAiSuggestionHelper registered")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(chatContextReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver not registered: ${e.message}")
        }
    }

    // ════════════════════════════════════════
    // MAIN METHOD — User ne AI button + tone press kiya
    // Ye method keyboard se call hogi
    // ════════════════════════════════════════

    fun requestSuggestions(tone: String) {
        if (lastMessagesJson == "[]" || lastMessagesJson.isEmpty()) {
            Log.w(TAG, "No chat context available — chat screen pe jao pehle")
            onError("Koi chat context nahi mila. Pehle WhatsApp ya Instagram chat kholo.")
            return
        }

        // App name short karo
        val appName = when {
            lastPackageName.startsWith("com.whatsapp") -> "whatsapp"
            lastPackageName == "com.instagram.android"  -> "instagram"
            else -> lastPackageName
        }

        Log.d(TAG, "🚀 Requesting suggestions | app=$appName | tone=$tone | chat_with=$lastChatWith")

        scope.launch {
            try {
                // JSON payload build karo
                val payload = buildPayload(appName, lastChatWith, tone, lastMessagesJson)
                Log.d(TAG, "📤 Payload: $payload")

                // Backend ko bhejo
                val response = postToBackend(payload)
                Log.d(TAG, "📥 Response: $response")

                // Parse karo
                val suggestions = parseResponse(response)

                // Keyboard ko do
                onSuggestionsReady(suggestions)

            } catch (e: Exception) {
                Log.e(TAG, "❌ API error: ${e.message}")
                onError("Server error: ${e.message}")
            }
        }
    }

    // ════════════════════════════════════════
    // PAYLOAD BUILDER
    // ════════════════════════════════════════

    private fun buildPayload(
        app:          String,
        chatWith:     String,
        tone:         String,
        messagesJson: String
    ): String {
        val obj = JSONObject()
        obj.put("app",       app)
        obj.put("chat_with", chatWith)
        obj.put("tone",      tone)
        obj.put("messages",  JSONArray(messagesJson))   // Already valid JSON from AccessibilityService
        return obj.toString()
    }

    // ════════════════════════════════════════
    // HTTP POST TO BACKEND
    // ════════════════════════════════════════

    private fun postToBackend(payload: String): String {
        val url = URL(backendUrl)
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput     = true
            conn.doInput      = true
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT

            // Request body bhejo
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }

            // Response code check karo
            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: "No error body"
                throw Exception("HTTP $responseCode: $errorBody")
            }

            // Response read karo
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()

        } finally {
            conn.disconnect()
        }
    }

    // ════════════════════════════════════════
    // RESPONSE PARSER
    // ════════════════════════════════════════

    private fun parseResponse(responseJson: String): List<String> {
        return try {
            val json = JSONObject(responseJson)

            // Status check karo
            val status = json.optString("status", "")
            if (status != "success") {
                val errorMsg = json.optString("error", "Backend error")
                throw Exception(errorMsg)
            }

            // suggestions array nikalo
            val suggestionsArray = json.getJSONArray("suggestions")
            val list = mutableListOf<String>()
            for (i in 0 until suggestionsArray.length()) {
                val suggestion = suggestionsArray.getString(i).trim()
                if (suggestion.isNotEmpty()) {
                    list.add(suggestion)
                }
            }

            if (list.isEmpty()) {
                throw Exception("Backend ne empty suggestions diye")
            }

            list

        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message} | Response: $responseJson")
            throw Exception("Response parse nahi hua: ${e.message}")
        }
    }

    // ════════════════════════════════════════
    // UTILITY: Stored context clear karo
    // ════════════════════════════════════════

    fun clearContext() {
        lastMessagesJson = "[]"
        lastPackageName  = ""
        lastChatWith     = ""
        Log.d(TAG, "Context cleared")
    }

    // ════════════════════════════════════════
    // UTILITY: Check if context is available
    // ════════════════════════════════════════

    fun hasContext(): Boolean {
        return lastMessagesJson != "[]" && lastMessagesJson.isNotEmpty()
    }
}
