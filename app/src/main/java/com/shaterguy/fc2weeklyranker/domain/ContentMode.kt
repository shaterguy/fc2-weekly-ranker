package com.shaterguy.fc2weeklyranker.domain

enum class ContentMode(
    val sourceKey: String,
    val boardTable: String,
    val defaultBaseUrl: String,
    val rankingTag: String? = null,
) {
    FC2("FC2", "javfc2", "https://02.avsee.is"),
    JAV("JAV", "javc", "https://02.avsee.is"),
    LTX("LTX", "javm", "https://02.avsee.is", rankingTag = "#LTX"),
    ;

    fun localPostId(remoteId: String): String = when (this) {
        FC2 -> remoteId
        JAV -> "jav:$remoteId"
        LTX -> "ltx:$remoteId"
    }

    fun remotePostId(localId: String): String = when (this) {
        FC2 -> localId
        JAV -> localId.removePrefix("jav:")
        LTX -> localId.removePrefix("ltx:")
    }

    companion object {
        fun fromSourceKey(value: String?): ContentMode =
            entries.firstOrNull { it.sourceKey == value } ?: FC2
    }
}
