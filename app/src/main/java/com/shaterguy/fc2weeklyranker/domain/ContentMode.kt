package com.shaterguy.fc2weeklyranker.domain

enum class ContentMode(
    val sourceKey: String,
    val boardTable: String,
) {
    FC2("FC2", "javfc2"),
    JAV("JAV", "javc"),
    ;

    fun localPostId(remoteId: String): String =
        if (this == FC2) remoteId else "jav:$remoteId"

    fun remotePostId(localId: String): String =
        if (this == FC2) localId else localId.removePrefix("jav:")

    companion object {
        fun fromSourceKey(value: String?): ContentMode =
            entries.firstOrNull { it.sourceKey == value } ?: FC2
    }
}
