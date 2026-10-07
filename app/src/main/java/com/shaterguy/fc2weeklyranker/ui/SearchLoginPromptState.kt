package com.shaterguy.fc2weeklyranker.ui

/** The same failed request prompts once; dismiss/return never submits a duplicate retry. */
internal data class SearchLoginPromptState(
    val failureKey: String? = null,
    val visible: Boolean = false,
) {
    fun observe(requestKey: String?, message: String?, busy: Boolean, officialLoginAvailable: Boolean): SearchLoginPromptState {
        if (message == null) return copy(failureKey = null)
        if (busy || !officialLoginAvailable || !searchNeedsAuthentication(message)) return this
        val key = requestKey ?: message
        return if (failureKey == key) this else copy(failureKey = key, visible = true)
    }

    fun open() = copy(visible = true)
    fun dismiss() = copy(visible = false)
    fun complete(): Pair<SearchLoginPromptState, Boolean> = dismiss() to visible
    fun savedValue(): List<String> = listOf(failureKey.orEmpty(), if (visible) "1" else "0")

    companion object {
        fun restore(value: List<String>) = SearchLoginPromptState(
            failureKey = value.getOrNull(0)?.takeIf(String::isNotEmpty),
            visible = value.getOrNull(1) == "1",
        )
    }
}
