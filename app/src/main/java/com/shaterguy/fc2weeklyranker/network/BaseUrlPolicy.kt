package com.shaterguy.fc2weeklyranker.network

import java.net.IDN
import java.net.URI

object BaseUrlPolicy {
    fun normalize(input: String): Result<String> = runCatching {
        val uri = URI(input.trim())
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS 주소만 사용할 수 있습니다." }
        require(uri.userInfo == null) { "사용자 정보가 포함된 주소는 사용할 수 없습니다." }
        require(uri.query == null && uri.fragment == null) { "쿼리나 프래그먼트가 없는 사이트 기본 주소를 입력하세요." }
        require(uri.port == -1 || uri.port == 443) { "기본 HTTPS 포트만 사용할 수 있습니다." }
        require(uri.path.isNullOrBlank() || uri.path == "/") { "게시판 경로가 아니라 사이트 기본 주소를 입력하세요." }
        val asciiHost = IDN.toASCII(requireNotNull(uri.host) { "호스트가 없습니다." }).lowercase()
        require(asciiHost.contains('.')) { "공개 도메인 형식의 주소가 필요합니다." }
        require(asciiHost != "localhost" && !asciiHost.endsWith(".local")) { "로컬 주소는 사용할 수 없습니다." }
        require(!asciiHost.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))) { "IP 주소 직접 입력은 사용할 수 없습니다." }
        "https://$asciiHost"
    }
}
