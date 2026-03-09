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

/**
 * ChatAccessibilityService
 *
 * Captures visible chat messages from foreground apps (WhatsApp, Telegram, etc.)
 * when the user explicitly enables this service in Android Accessibility Settings.
 *
 * How to enable: Settings → Accessibility → HeliboardL Chat Assistant → Enable
 */
class ChatAccessibilityService : AccessibilityService() {

    // ─────────────────────────────────────────────
    // Constants — companion object mein sirf constants
    // ─────────────────────────────────────────────

    companion object {
        const val TAG = "CHAT_DEBUG"

        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",                   // WhatsApp Business
            "com.facebook.orca",                   // Messenger
            "com.instagram.android",               // Instagram
            "org.telegram.messenger",              // Telegram
            "com.google.android.apps.messaging",   // Google Messages
            "com.viber.voip",                      // Viber
            "kik.android"                          // Kik
        )

        private const val DEBOUNCE_MS = 1500L

        const val ACTION_CHAT_CONTEXT = "helium314.keyboard.CHAT_CONTEXT"
        const val EXTRA_MESSAGES      = "chat_messages"
        const val EXTRA_PACKAGE       = "source_package"
    }

    // ─────────────────────────────────────────────
    // Instance variables
    // ─────────────────────────────────────────────

    private val serviceScope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedTime = 0L
    private var currentPackage: String? = null

    // ═════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED      or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED          or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 300
            packageNames = SUPPORTED_PACKAGES.toTypedArray()
        }
        serviceInfo = info

        Log.i(TAG, "╔══════════════════════════════════════════════════╗")
        Log.i(TAG, "║   ChatAccessibilityService CONNECTED ✅           ║")
        Log.i(TAG, "╚══════════════════════════════════════════════════╝")
    }

    override fun onInterrupt() {
        Log.w(TAG, "ChatAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "ChatAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    // ═════════════════════════════════════════════
    // MAIN EVENT HANDLER
    // ═════════════════════════════════════════════

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        currentPackage = pkg

        Log.d(TAG, "─────────────────────────────────────────────────────")
        Log.d(TAG, "📡 EVENT  pkg=$pkg  type=${event.eventType}")

        // Debounce — rapid events ignore karo
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) return
        lastProcessedTime = now

        // Background thread pe process karo
        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow ?: run {
                    Log.w(TAG, "rootInActiveWindow is NULL — WhatsApp screen pe visible nahi hai")
                    return@launch
                }

                // ── STEP 1: Poora tree dump karo (VERBOSE level pe dikhega) ──
                Log.v(TAG, "▼▼▼▼▼▼▼▼▼▼  FULL ACCESSIBILITY TREE  ▼▼▼▼▼▼▼▼▼▼")
                dumpTree(rootNode, 0)
                Log.v(TAG, "▲▲▲▲▲▲▲▲▲▲  END OF TREE  ▲▲▲▲▲▲▲▲▲▲")

                // ── STEP 2: Messages extract karo ──
                val messages = extractChatMessages(rootNode)
                rootNode.recycle()

                // ── STEP 3: Messages logcat mein print karo ──
                if (messages.isNotEmpty()) {
                    Log.i(TAG, "╔══════════════════════════════════════════════════════╗")
                    Log.i(TAG, "  CHAT_DEBUG MESSAGES  ←  $pkg")
                    Log.i(TAG, "  ${messages.size} message(s) extracted")
                    Log.i(TAG, "╚══════════════════════════════════════════════════════╝")
                    messages.forEachIndexed { index, msg ->
                        val sender = if (msg.isOwn) "👤 USER " else "💬 OTHER"
                        Log.i(TAG, "  [$index] $sender: ${msg.text}")
                    }
                    Log.i(TAG, "══════════════════════════════════════════════════════")

                    broadcastChatContext(pkg, messages)

                } else {
                    Log.w(TAG, "No messages extracted from $pkg — kisi chat ke andar jao")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error extracting messages: ${e.message}")
            }
        }
    }

    // ═════════════════════════════════════════════
    // TREE DUMP  ← companion object ke BAHAR ✅
    // ═════════════════════════════════════════════

    private fun dumpTree(node: AccessibilityNodeInfo?, depth: Int) {
        node ?: return

        val indent = "  ".repeat(depth)
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        Log.v(TAG,
            "${indent}Depth $depth → ${node.className}" +
                "  text=\"${node.text}\"" +
                "  id=\"${node.viewIdResourceName}\"" +
                "  bounds=[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
        )

        for (i in 0 until node.childCount) {
            dumpTree(node.getChild(i), depth + 1)
        }
    }

    // ═════════════════════════════════════════════
    // MESSAGE EXTRACTION
    // ═════════════════════════════════════════════

    private fun extractChatMessages(rootNode: AccessibilityNodeInfo): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        traverseNode(rootNode, messages, depth = 0)

        return messages
            .distinctBy { it.text }            // duplicate text remove
            .filter    { it.text.length > 2 }  // noise remove
            .takeLast  (20)                    // last 20 messages rakho
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        messages: MutableList<ChatMessage>,
        depth: Int
    ) {
        if (node == null || depth > 15) return

        val text = node.text?.toString()?.trim()

        if (!text.isNullOrEmpty() && isChatMessageNode(node, text)) {
            val isOwn = detectIfOwnMessage(node)
            messages.add(ChatMessage(
                text      = text,
                isOwn     = isOwn,
                timestamp = System.currentTimeMillis()
            ))
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), messages, depth + 1)
        }
    }

    // ─────────────────────────────────────────────
    // Filter: Kya ye node ek chat message hai?
    // ─────────────────────────────────────────────

    private fun isChatMessageNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Chhoti strings skip (timestamps "12:30", ticks "✓✓")
        if (text.length < 3) return false

        // Timestamps skip
        if (text.matches(Regex("\\d{1,2}:\\d{2}(\\s?(AM|PM))?"))) return false

        // Status words skip
        val skipWords = setOf(
            "delivered", "read", "seen",
            "typing...", "online", "yesterday", "today"
        )
        if (text.lowercase() in skipWords) return false

        // Buttons aur checkboxes skip
        val className = node.className?.toString() ?: ""
        if (className.contains("Button") || className.contains("CheckBox")) return false

        return true
    }

    // ─────────────────────────────────────────────
    // Detect: USER ka message hai ya OTHER ka?
    // Right side = USER apna message
    // Left side  = OTHER ka message
    // ─────────────────────────────────────────────

    private fun detectIfOwnMessage(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val screenWidth = resources.displayMetrics.widthPixels
            // Screen ke right 55%+ pe ho toh USER ka message
            rect.centerX() > (screenWidth * 0.55f)
        } catch (e: Exception) {
            false
        } finally {
            parent.recycle()
        }
    }

    // ═════════════════════════════════════════════
    // BROADCAST TO KEYBOARD
    // ═════════════════════════════════════════════

    private fun broadcastChatContext(packageName: String, messages: List<ChatMessage>) {
        val messageArray = messages
            .map { "${if (it.isOwn) "ME" else "OTHER"}: ${it.text}" }
            .toTypedArray()

        val intent = Intent(ACTION_CHAT_CONTEXT).apply {
            setPackage(applicationContext.packageName) // Internal broadcast only
            putExtra(EXTRA_PACKAGE,  packageName)
            putExtra(EXTRA_MESSAGES, messageArray)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcasted ${messages.size} messages to keyboard")
    }
}

// ═════════════════════════════════════════════
// DATA MODEL
// ═════════════════════════════════════════════

data class ChatMessage(
    val text:      String,
    val isOwn:     Boolean,
    val timestamp: Long
)
