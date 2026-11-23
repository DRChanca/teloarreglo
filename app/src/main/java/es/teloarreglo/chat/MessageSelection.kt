package es.teloarreglo.chat

import java.text.Normalizer

/**
 * Messages chosen inside the chat, WhatsApp-like, to add them to an incident (#26). A long press starts the mode; then a
 * tap toggles. Messages already in the incident cannot be chosen again. Kept apart from Compose so it can be tested.
 */
data class MessageSelection(val active: Boolean = false, val ids: List<String> = emptyList()) {
    /** Opened from the incident card: the mode starts with nothing chosen. */
    fun start() = copy(active = true)
    fun cancel() = MessageSelection()
    fun longPress(id: String, unavailable: Set<String>) = if (active) tap(id, unavailable)
        else MessageSelection(true, if (id in unavailable) emptyList() else listOf(id))
    fun tap(id: String, unavailable: Set<String>) = when {
        !active || id in unavailable -> this
        id in ids -> copy(ids = ids - id)
        else -> copy(ids = ids + id)
    }
    /** After sending, what reached the incident leaves; the mode ends once everything did. */
    fun withoutAdded(added: Collection<String>): MessageSelection {
        val left = ids - added.toSet()
        return if (left.isEmpty()) MessageSelection() else copy(ids = left)
    }
}

/**
 * Text search inside one conversation, to find what to select in a long chat. Case and accents are ignored ("manana"
 * finds "Mañana"); matches keep the chat order and the newest one is shown first, as in messaging apps.
 */
class MessageSearch(messages: List<ChatMessage>, query: String) {
    private val needle = fold(query.trim())
    val ids: List<String> = if (needle.isEmpty()) emptyList()
        else messages.filter { fold(it.body).contains(needle) }.map { it.id }
    val initial get() = ids.lastIndex
    fun idAt(index: Int) = ids.getOrNull(index)
    /** The next (+1) or previous (-1) match, wrapping around. */
    fun step(index: Int, delta: Int) = if (ids.isEmpty()) -1 else Math.floorMod(index + delta, ids.size)

    /** Where the query appears in [text], as indices of the original text (folding keeps one char per char). */
    fun ranges(text: String): List<IntRange> {
        if (needle.isEmpty()) return emptyList()
        val haystack = fold(text)
        val found = mutableListOf<IntRange>()
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            found += from until from + needle.length
            from = haystack.indexOf(needle, from + needle.length)
        }
        return found
    }

    private companion object {
        private val marks = Regex("\\p{Mn}+")
        fun fold(text: String) = buildString(text.length) {
            text.forEach { char -> append(Normalizer.normalize(char.toString(), Normalizer.Form.NFD).replace(marks, "")
                .firstOrNull()?.lowercaseChar() ?: char) }
        }
    }
}
