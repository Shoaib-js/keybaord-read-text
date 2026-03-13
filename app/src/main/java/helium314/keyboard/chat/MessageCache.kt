package helium314.keyboard.chat

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe ring buffer for the last N chat messages.
 *
 * ─── Design decisions ────────────────────────────────────────────
 * • ReentrantReadWriteLock: concurrent AI reads never block writes.
 * • 500ms debounce: suppresses duplicate notification events, NOT
 *   duplicate message content. Same text at different times → stored.
 * • [currentChatKey] tracks the active conversation so the Bridge
 *   knows when to replace vs. append.
 * • [shared] singleton: the single source of truth across all
 *   services in the keyboard process.
 * ─────────────────────────────────────────────────────────────────
 *
 * Constraint coverage:
 *   #1 Rapid burst    → NLS/accessibility already handles; we store all
 *   #2 Duplicate text → debounce is time-based, not text-based
 *   #3 Sender identity→ ChatMessage.Sender is always set by caller
 *   #4 Scroll safety  → accessibility only fires TYPE_WINDOW_STATE_CHANGED
 *   #5 Lightweight    → passive receiver + O(1) ring-buffer operations
 */
class MessageCache(private val maxSize: Int = 15) {

    companion object {
        /**
         * Shared singleton used by every component:
         *   ChatBridgeReceiver  → writes (from accessibility broadcast)
         *   MyOwnMessageTracker → writes (from keyboard send action)
         *   AiSuggestionEngine  → reads  (to build AI prompt)
         */
        val shared = MessageCache(maxSize = 15)
    }

    private val buffer = ArrayDeque<ChatMessage>(maxSize + 1)
    private val lock   = ReentrantReadWriteLock()

    /** e.g. "com.whatsapp:Rahul" or "com.instagram.android:john_doe" */
    @Volatile var currentChatKey: String? = null
        private set

    // ─────────────────────────────────────────
    // WRITE OPERATIONS
    // ─────────────────────────────────────────

    /**
     * Append [message] to the buffer.
     *
     * Debounce rules:
     *  • ME sender   → NEVER debounce. The keyboard send action fires exactly
     *    once per user send. There is no duplicate-delivery path for ME messages.
     *    Skipping any ME message here = permanent data loss.
     *  • OTHER sender → 500ms debounce only. WhatsApp/Instagram sometimes
     *    re-posts the same notification within milliseconds. The debounce
     *    suppresses that re-post without losing real messages.
     *
     * Returns false (and does NOT add) only if OTHER message is a debounce duplicate.
     */
    fun add(message: ChatMessage): Boolean = lock.write {
        // ME messages: always add, no debounce
        if (message.sender == ChatMessage.Sender.ME) {
            buffer.addLast(message)
            if (buffer.size > maxSize) buffer.removeFirst()
            return@write true
        }

        // OTHER messages: 500ms debounce to suppress notification re-deliveries
        val lastOther = buffer.lastOrNull { it.sender == ChatMessage.Sender.OTHER }
        if (lastOther != null && message.isDebounceDuplicateOf(lastOther)) {
            return@write false
        }
        buffer.addLast(message)
        if (buffer.size > maxSize) buffer.removeFirst()
        true
    }

    /**
     * Discard current content and load [messages] as a fresh context snapshot.
     * Called when a new chat is opened (initial history pre-load).
     */
    fun replaceAll(messages: List<ChatMessage>, chatKey: String) = lock.write {
        buffer.clear()
        currentChatKey = chatKey
        messages.takeLast(maxSize).forEach { buffer.addLast(it) }
    }

    /**
     * Clear buffer and set [newChatKey].
     * Called when the user switches to a different conversation.
     * No-op if already on [newChatKey].
     */
    fun switchChat(newChatKey: String) = lock.write {
        if (currentChatKey == newChatKey) return@write
        buffer.clear()
        currentChatKey = newChatKey
    }

    /** Fully reset — called on keyboard destroy. */
    fun clear() = lock.write {
        buffer.clear()
        currentChatKey = null
    }

    // ─────────────────────────────────────────
    // READ OPERATIONS
    // ─────────────────────────────────────────

    /** Returns a snapshot of all messages, oldest first. */
    fun getAll(): List<ChatMessage> = lock.read { buffer.toList() }

    fun size(): Int        = lock.read { buffer.size }
    fun isEmpty(): Boolean = lock.read { buffer.isEmpty() }
}
