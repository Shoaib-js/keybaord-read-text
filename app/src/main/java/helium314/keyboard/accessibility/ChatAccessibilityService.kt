package helium314.keyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
 * ⚠️ IMPORTANT — Google Play Policy Note:
 * This service ONLY reads text that is visibly rendered on screen.
 * It does NOT intercept network traffic or encrypted data.
 * You MUST declare this usage clearly in your app's Data Safety section on Play Console.
 * Only use for "Reply Suggestions" feature — make this explicit in your Privacy Policy.
 *
 * How to enable: Settings → Accessibility → HeliboardL Chat Assistant → Enable
 */
class ChatAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChatAccessibility"

        // Supported chat app packages
        private val SUPPORTED_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",           // WhatsApp Business
            "com.facebook.orca",           // Messenger
            "com.instagram.android",       // Instagram
            "org.telegram.messenger",      // Telegram
            "com.google.android.apps.messaging", // Google Messages
            "com.viber.voip",              // Viber
            "kik.android"                  // Kik
        )

        // Debounce: avoid spamming the AI API on every keystroke
        private const val DEBOUNCE_MS = 1500L

        // Broadcast action to send messages to the keyboard service
        const val ACTION_CHAT_CONTEXT = "helium314.keyboard.CHAT_CONTEXT"
        const val EXTRA_MESSAGES = "chat_messages"
        const val EXTRA_PACKAGE = "source_package"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastProcessedTime = 0L
    private var currentPackage: String? = null

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 300  // ms between events
            packageNames = SUPPORTED_PACKAGES.toTypedArray()
        }
        serviceInfo = info
        Log.i(TAG, "ChatAccessibilityService connected ✅")
    }

    override fun onInterrupt() {
        Log.w(TAG, "ChatAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "ChatAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    // ─────────────────────────────────────────────
    // Main Event Handler
    // ─────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED_PACKAGES) return

        currentPackage = pkg

        // Debounce rapid events
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < DEBOUNCE_MS) return
        lastProcessedTime = now

        // Process on background thread to avoid blocking UI
        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow ?: return@launch
                val messages = extractChatMessages(rootNode)
                rootNode.recycle()

                if (messages.isNotEmpty()) {
                    Log.d(TAG, "Captured ${messages.size} messages from $pkg")
                    broadcastChatContext(pkg, messages)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting messages: ${e.message}")
            }
        }
    }

    // ─────────────────────────────────────────────
    // Node Traversal — Extract Chat Messages
    // ─────────────────────────────────────────────

    private fun extractChatMessages(rootNode: AccessibilityNodeInfo): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        traverseNode(rootNode, messages, depth = 0)

        // Deduplicate and filter noise
        return messages
            .distinctBy { it.text }
            .filter { it.text.length > 2 }
            .takeLast(20) // Keep last 20 messages for context window
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo?,
        messages: MutableList<ChatMessage>,
        depth: Int
    ) {
        if (node == null || depth > 15) return // limit recursion depth

        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()

        // Extract text from leaf nodes that look like messages
        if (!text.isNullOrEmpty() && isChatMessageNode(node, text)) {
            val isOwn = detectIfOwnMessage(node)
            messages.add(ChatMessage(
                text = text,
                isOwn = isOwn,
                timestamp = System.currentTimeMillis()
            ))
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), messages, depth + 1)
        }
    }

    /**
     * Heuristic: Is this node a chat message bubble?
     * Filters out buttons, timestamps, status indicators, etc.
     */
    private fun isChatMessageNode(node: AccessibilityNodeInfo, text: String): Boolean {
        // Skip very short strings (timestamps like "12:30", status like "✓✓")
        if (text.length < 3) return false

        // Skip pure numeric strings (timestamps, phone numbers in headers)
        if (text.matches(Regex("\\d{1,2}:\\d{2}(\\s?(AM|PM))?"))) return false

        // Skip "Delivered", "Read", "Seen" status strings
        val skipWords = setOf("delivered", "read", "seen", "typing...", "online", "yesterday", "today")
        if (text.lowercase() in skipWords) return false

        // Prefer TextView-like nodes (not buttons/checkboxes)
        val className = node.className?.toString() ?: ""
        if (className.contains("Button") || className.contains("CheckBox")) return false

        return true
    }

    /**
     * Rough heuristic to detect if a message was sent by the current user.
     * Works for most apps by checking node position (right-aligned = own message).
     * Not 100% accurate across all apps.
     */
    private fun detectIfOwnMessage(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        return try {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val parentRect = android.graphics.Rect()
            parent.getBoundsInScreen(parentRect)
            // If node is in the right ~60% of screen, likely own message
            val screenWidth = resources.displayMetrics.widthPixels
            rect.centerX() > (screenWidth * 0.55f)
        } catch (e: Exception) {
            false
        } finally {
            parent.recycle()
        }
    }

    // ─────────────────────────────────────────────
    // Broadcast to Keyboard Service
    // ─────────────────────────────────────────────

    private fun broadcastChatContext(packageName: String, messages: List<ChatMessage>) {
        val messageArray = messages.map { "${if (it.isOwn) "ME" else "OTHER"}: ${it.text}" }
            .toTypedArray()

        val intent = Intent(ACTION_CHAT_CONTEXT).apply {
            setPackage(applicationContext.packageName) // Internal broadcast only
            putExtra(EXTRA_PACKAGE, packageName)
            putExtra(EXTRA_MESSAGES, messageArray)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Broadcasted ${messages.size} messages")
    }
}

// ─────────────────────────────────────────────
// Data Model
// ─────────────────────────────────────────────

data class ChatMessage(
    val text: String,
    val isOwn: Boolean,
    val timestamp: Long
)
