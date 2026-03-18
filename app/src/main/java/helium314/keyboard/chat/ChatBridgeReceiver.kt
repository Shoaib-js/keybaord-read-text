//package helium314.keyboard.chat
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.util.Log
//import helium314.keyboard.accessibility.ChatAccessibilityService
//import org.json.JSONArray
//import org.json.JSONException
//
///**
// * ChatBridgeReceiver
// *
// * ─────────────────────────────────────────────────────────────────
// * PURPOSE
// * ─────────────────────────────────────────────────────────────────
// * The existing system (ChatAccessibilityService + WhatsAppNotificationService)
// * already extracts, deduplicates, and timestamps every chat message correctly.
// * It broadcasts the result as ACTION_CHAT_CONTEXT with a JSON payload.
// *
// * This receiver's only job is to:
// *   1. Receive that broadcast
// *   2. Parse the JSON → List<ChatMessage>
// *   3. Write into MessageCache.shared
// *
// * That makes MessageCache.shared always up-to-date without running any
// * duplicate accessibility or notification service.
// *
// * ─────────────────────────────────────────────────────────────────
// * DATA FLOW
// * ─────────────────────────────────────────────────────────────────
// *
// *  WhatsApp sends msg
// *       ↓
// *  WhatsAppNotificationService → ACTION_NEW_CHAT_MESSAGE broadcast
// *       ↓
// *  ChatAccessibilityService   → scans tree, deduplicates, timestamps
// *       ↓
// *  ACTION_CHAT_CONTEXT broadcast  (JSON array, sorted oldest→newest)
// *       ↓
// *  ChatBridgeReceiver         → parseMessages() → MessageCache.shared
// *       ↓
// *  AiSuggestionEngine         → MessageCache.shared.getAll() → prompt → API
// *
// * ─────────────────────────────────────────────────────────────────
// * LIFECYCLE — register in LatinIME.java
// * ─────────────────────────────────────────────────────────────────
// *   // Field:
// *   private ChatBridgeReceiver mChatBridgeReceiver;
// *
// *   // In onCreate(), after mChatAiHelper.register():
// *   mChatBridgeReceiver = new ChatBridgeReceiver();
// *   mChatBridgeReceiver.register(this);
// *
// *   // In onDestroy():
// *   if (mChatBridgeReceiver != null) {
// *       mChatBridgeReceiver.unregister(this);
// *       mChatBridgeReceiver = null;
// *   }
// */
//class ChatBridgeReceiver : BroadcastReceiver() {
//
//    companion object {
//        private const val TAG = "ChatBridge"
//    }
//
//    /** Last seen package name — "com.whatsapp" or "com.instagram.android" */
//    @Volatile var lastPackage:  String = ""
//
//    /** Last seen contact/group name */
//    @Volatile var lastChatWith: String = ""
//
//    // ─────────────────────────────────────────
//    // REGISTER / UNREGISTER
//    // ─────────────────────────────────────────
//
//    fun register(context: Context) {
//        val filter = IntentFilter(ChatAccessibilityService.ACTION_CHAT_CONTEXT)
//        context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
//        Log.d(TAG, "ChatBridgeReceiver registered")
//    }
//
//    fun unregister(context: Context) {
//        try {
//            context.unregisterReceiver(this)
//            Log.d(TAG, "ChatBridgeReceiver unregistered")
//        } catch (e: IllegalArgumentException) {
//            Log.w(TAG, "Already unregistered: ${e.message}")
//        }
//    }
//
//    // ─────────────────────────────────────────
//    // onReceive — main entry point
//    // ─────────────────────────────────────────
//
//    override fun onReceive(context: Context?, intent: Intent?) {
//        if (intent?.action != ChatAccessibilityService.ACTION_CHAT_CONTEXT) return
//
//        val messagesJson = intent.getStringExtra(ChatAccessibilityService.EXTRA_MESSAGES)
//            ?: run { Log.w(TAG, "EXTRA_MESSAGES is null"); return }
//        val pkg      = intent.getStringExtra(ChatAccessibilityService.EXTRA_PACKAGE)   ?: ""
//        val chatWith = intent.getStringExtra(ChatAccessibilityService.EXTRA_CHAT_WITH) ?: ""
//
//        lastPackage  = pkg
//        lastChatWith = chatWith
//
//        val chatKey  = "$pkg:$chatWith"
//        val messages = parseMessages(messagesJson)
//
//        if (messages.isEmpty()) {
//            Log.w(TAG, "Parsed 0 valid messages from broadcast — nothing to cache")
//            return
//        }
//
//        Log.d(TAG, "Bridge received ${messages.size} msgs | chat=$chatKey")
//
//        if (chatKey != MessageCache.shared.currentChatKey) {
//            // ── New / switched conversation → replace entire cache ──────────
//            // replaceAll() loads up to 15 messages as initial context snapshot.
//            MessageCache.shared.replaceAll(messages, chatKey)
//            Log.i(TAG, "Cache replaced for new chat: $chatKey (${messages.size} msgs loaded)")
//        } else {
//            // ── Same conversation → append only the newest message ──────────
//            // ChatAccessibilityService already deduplicates internally.
//            // The last item in the sorted JSON array is always the newest message.
//            val newest = messages.last()
//            val added  = MessageCache.shared.add(newest)
//            if (added) {
//                Log.i(TAG, "Appended to cache: [${newest.sender}] ${newest.text.take(50)}")
//            } else {
//                Log.v(TAG, "Debounce skipped: ${newest.text.take(30)}")
//            }
//        }
//
//        Log.d(TAG, "MessageCache.shared size=${MessageCache.shared.size()} | key=${MessageCache.shared.currentChatKey}")
//    }
//
//    // ─────────────────────────────────────────────────────────────
//    // JSON PARSING
//    //
//    // Input (from ChatAccessibilityService.buildJsonArray):
//    // [
//    //   {"sequence":1,"sender":"other","message":"Kal miloge?","time":"2026-03-13T15:01:00"},
//    //   {"sequence":2,"sender":"me",   "message":"Haan bhai",  "time":"2026-03-13T15:02:00"}
//    // ]
//    //
//    // JSON is already sorted oldest→newest by the accessibility service.
//    // ─────────────────────────────────────────────────────────────
//
//    private fun parseMessages(json: String): List<ChatMessage> {
//        val result = mutableListOf<ChatMessage>()
//        try {
//            val array = JSONArray(json)
//            for (i in 0 until array.length()) {
//                val obj = array.optJSONObject(i) ?: continue
//
//                val text      = obj.optString("message", "").trim()
//                val senderStr = obj.optString("sender",  "other")
//                val timeStr   = obj.optString("time",    "")
//
//                if (text.isBlank()) continue
//
//                val sender = if (senderStr.equals("me", ignoreCase = true))
//                    ChatMessage.Sender.ME
//                else
//                    ChatMessage.Sender.OTHER
//
//                val timestampMs = parseIsoToMs(timeStr)
//
//                result.add(
//                    ChatMessage(
//                        text        = text,
//                        sender      = sender,
//                        timestampMs = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
//                    )
//                )
//            }
//        } catch (e: JSONException) {
//            Log.e(TAG, "JSON parse error: ${e.message} | json preview: ${json.take(200)}")
//        }
//        return result
//    }
//
//    // ── Parse "2026-03-13T15:01:00" → epoch milliseconds ────────────────────
//    private fun parseIsoToMs(iso: String): Long {
//        if (iso.isBlank()) return 0L
//        return try {
//            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
//                .parse(iso)?.time ?: 0L
//        } catch (e: Exception) {
//            0L
//        }
//    }
//}




package helium314.keyboard.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import helium314.keyboard.accessibility.ChatAccessibilityService
import org.json.JSONArray
import org.json.JSONException

/**
 * ChatBridgeReceiver
 *
 * ─────────────────────────────────────────────────────────────────
 * PURPOSE
 * ─────────────────────────────────────────────────────────────────
 * The existing system (ChatAccessibilityService + WhatsAppNotificationService)
 * already extracts, deduplicates, and timestamps every chat message correctly.
 * It broadcasts the result as ACTION_CHAT_CONTEXT with a JSON payload.
 *
 * This receiver's only job is to:
 *   1. Receive that broadcast
 *   2. Parse the JSON → List<ChatMessage>
 *   3. Write into MessageCache.shared
 *
 * That makes MessageCache.shared always up-to-date without running any
 * duplicate accessibility or notification service.
 *
 * ─────────────────────────────────────────────────────────────────
 * DATA FLOW
 * ─────────────────────────────────────────────────────────────────
 *
 *  WhatsApp sends msg
 *       ↓
 *  WhatsAppNotificationService → ACTION_NEW_CHAT_MESSAGE broadcast
 *       ↓
 *  ChatAccessibilityService   → scans tree, deduplicates, timestamps
 *       ↓
 *  ACTION_CHAT_CONTEXT broadcast  (JSON array, sorted oldest→newest)
 *       ↓
 *  ChatBridgeReceiver         → parseMessages() → MessageCache.shared
 *       ↓
 *  AiSuggestionEngine         → MessageCache.shared.getAll() → prompt → API
 *
 * ─────────────────────────────────────────────────────────────────
 * LIFECYCLE — register in LatinIME.java
 * ─────────────────────────────────────────────────────────────────
 *   // Field:
 *   private ChatBridgeReceiver mChatBridgeReceiver;
 *
 *   // In onCreate(), after mChatAiHelper.register():
 *   mChatBridgeReceiver = new ChatBridgeReceiver();
 *   mChatBridgeReceiver.register(this);
 *
 *   // In onDestroy():
 *   if (mChatBridgeReceiver != null) {
 *       mChatBridgeReceiver.unregister(this);
 *       mChatBridgeReceiver = null;
 *   }
 */
class ChatBridgeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ChatBridge"
    }

    /** Last seen package name — "com.whatsapp" or "com.instagram.android" */
    @Volatile var lastPackage:  String = ""

    /** Last seen contact/group name */
    @Volatile var lastChatWith: String = ""

    // ─────────────────────────────────────────
    // REGISTER / UNREGISTER
    // ─────────────────────────────────────────

    fun register(context: Context) {
        val filter = IntentFilter(ChatAccessibilityService.ACTION_CHAT_CONTEXT)
        context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d(TAG, "ChatBridgeReceiver registered")
    }

    fun unregister(context: Context) {
        try {
            context.unregisterReceiver(this)
            Log.d(TAG, "ChatBridgeReceiver unregistered")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Already unregistered: ${e.message}")
        }
    }

    // ─────────────────────────────────────────
    // onReceive — main entry point
    // ─────────────────────────────────────────

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != ChatAccessibilityService.ACTION_CHAT_CONTEXT) return

        val messagesJson = intent.getStringExtra(ChatAccessibilityService.EXTRA_MESSAGES)
            ?: run { Log.w(TAG, "EXTRA_MESSAGES is null"); return }
        val pkg      = intent.getStringExtra(ChatAccessibilityService.EXTRA_PACKAGE)   ?: ""
        val chatWith = intent.getStringExtra(ChatAccessibilityService.EXTRA_CHAT_WITH) ?: ""

        lastPackage  = pkg
        lastChatWith = chatWith

        val chatKey  = "$pkg:$chatWith"
        val messages = parseMessages(messagesJson)

        if (messages.isEmpty()) {
            Log.w(TAG, "Parsed 0 valid messages from broadcast — nothing to cache")
            return
        }

        Log.d(TAG, "Bridge received ${messages.size} msgs | chat=$chatKey")

        if (chatKey != MessageCache.shared.currentChatKey) {
            // ── New / switched conversation → replace entire cache ──────────
            MessageCache.shared.replaceAll(messages, chatKey)
            Log.i(TAG, "Cache replaced for new chat: $chatKey (${messages.size} msgs loaded)")
        } else {
            // ── Same conversation → only append if genuinely newer ──────────
            //
            // Problem without this guard (observed in logs):
            //   1. Initial load: 7 msgs, cache size = 7
            //   2. Second broadcast: same 7 msgs, chatKey unchanged
            //   3. Old code: always appended messages.last() → size grew to 8
            //      with "Bs badiya guru" duplicated → API received ghost message
            //
            // Fix: compare the newest message's timestampMs to the last entry
            // already in the cache. Only append when strictly newer.
            // MessageCache.add() still applies the 500ms debounce on top of this.
            val newest        = messages.last()
            val lastCachedMs  = MessageCache.shared.lastTimestampMs()

            if (newest.timestampMs > lastCachedMs) {
                val added = MessageCache.shared.add(newest)
                if (added) {
                    Log.i(TAG, "Appended to cache: [${newest.sender}] ${newest.text.take(50)}")
                } else {
                    Log.v(TAG, "Debounce skipped: ${newest.text.take(30)}")
                }
            } else {
                Log.v(TAG, "Bridge: newest msg not newer than cache (${newest.timestampMs} ≤ $lastCachedMs), skip append")
            }
        }

        Log.d(TAG, "MessageCache.shared size=${MessageCache.shared.size()} | key=${MessageCache.shared.currentChatKey}")
    }

    // ─────────────────────────────────────────────────────────────
    // JSON PARSING
    //
    // Input (from ChatAccessibilityService.buildJsonArray):
    // [
    //   {"sequence":1,"sender":"other","message":"Kal miloge?","time":"2026-03-13T15:01:00"},
    //   {"sequence":2,"sender":"me",   "message":"Haan bhai",  "time":"2026-03-13T15:02:00"}
    // ]
    //
    // JSON is already sorted oldest→newest by the accessibility service.
    // ─────────────────────────────────────────────────────────────

    private fun parseMessages(json: String): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue

                val text      = obj.optString("message", "").trim()
                val senderStr = obj.optString("sender",  "other")
                val timeStr   = obj.optString("time",    "")

                if (text.isBlank()) continue

                val sender = if (senderStr.equals("me", ignoreCase = true))
                    ChatMessage.Sender.ME
                else
                    ChatMessage.Sender.OTHER

                val timestampMs = parseIsoToMs(timeStr)

                result.add(
                    ChatMessage(
                        text        = text,
                        sender      = sender,
                        timestampMs = if (timestampMs > 0L) timestampMs else System.currentTimeMillis()
                    )
                )
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parse error: ${e.message} | json preview: ${json.take(200)}")
        }
        return result
    }

    // ── Parse "2026-03-13T15:01:00" → epoch milliseconds ────────────────────
    private fun parseIsoToMs(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
                .parse(iso)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
