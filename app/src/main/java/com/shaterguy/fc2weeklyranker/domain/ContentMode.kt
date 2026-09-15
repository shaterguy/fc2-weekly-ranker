package com.shaterguy.fc2weeklyranker.domain

enum class ContentMode(
    val sourceKey: String,
    val boardTable: String,
    val defaultBaseUrl: String,
) {
    FC2("FC2", "javfc2", "https://01.avsee.is"),
    JAV("JAV", "javc", "https://02.avsee.is"),
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
