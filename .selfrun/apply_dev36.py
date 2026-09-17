from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1))


AVSEE = "app/src/main/java/com/shaterguy/fc2weeklyranker/network/AvseeClient.kt"
replace_once(
    AVSEE,
    "import kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withPermit",
    "import kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.Semaphore\nimport kotlinx.coroutines.sync.withLock\nimport kotlinx.coroutines.sync.withPermit",
)
replace_once(
    AVSEE,
    """    val recommendationCount: Int,\n    val media: List<RemoteMedia>,\n    val tags: List<RemoteTag> = emptyList(),\n)""",
    """    val recommendationCount: Int,\n    val media: List<RemoteMedia>,\n    val tags: List<RemoteTag> = emptyList(),\n    val detailRecommendationCount: Int? = null,\n    val detailCommentCount: Int? = null,\n)""",
)
replace_once(
    AVSEE,
    """    suspend fun searchTagPosts(\n        baseUrl: String,\n        query: String,\n    ): List<RemoteTagPost> = withContext(ioDispatcher) {""",
    """    suspend fun searchTagPosts(\n        baseUrl: String,\n        query: String,\n        boardTable: String = \"javc\",\n    ): List<RemoteTagPost> = withContext(ioDispatcher) {""",
)
replace_once(AVSEE, "val first = parseTagPage(fetch(firstUrl), firstUrl)", "val first = parseTagPage(fetch(firstUrl), firstUrl, boardTable)")
replace_once(AVSEE, "parseTagPage(fetch(pageUrl, firstUrl), pageUrl)", "parseTagPage(fetch(pageUrl, firstUrl), pageUrl, boardTable)")
replace_once(
    AVSEE,
    "internal fun parseTagPage(html: String, pageUrl: String): TagPage {",
    "internal fun parseTagPage(html: String, pageUrl: String, boardTable: String = \"javc\"): TagPage {",
)
replace_once(
    AVSEE,
    """            val link = row.selectFirst(\".media-heading a[href*='bo_table=javc'][href*='wr_id=']\") ?: return@forEach\n            val url = link.absUrl(\"href\").takeIf(String::isNotBlank) ?: return@forEach\n            if (decodedQueryParam(url, \"bo_table\") != \"javc\") return@forEach""",
    """            val link = row.selectFirst(\".media-heading a[href*='bo_table=$boardTable'][href*='wr_id=']\") ?: return@forEach\n            val url = link.absUrl(\"href\").takeIf(String::isNotBlank) ?: return@forEach\n            if (decodedQueryParam(url, \"bo_table\") != boardTable) return@forEach""",
)
replace_once(
    AVSEE,
    """        val tags = if (decodedQueryParam(detailUrl, \"bo_table\") == \"javc\") parseDetailTags(doc) else emptyList()\n        return RemotePost(id, detailUrl, title, postedAt, parseRecommendation(doc), media, tags)""",
    """        val boardTable = decodedQueryParam(detailUrl, \"bo_table\")\n        val tags = if (boardTable == \"javc\" || boardTable == \"javfc2\") parseDetailTags(doc) else emptyList()\n        return RemotePost(\n            id = id,\n            url = detailUrl,\n            title = title,\n            postedAt = postedAt,\n            recommendationCount = parseRecommendation(doc),\n            media = media,\n            tags = tags,\n            detailRecommendationCount = parseDetailRecommendationCount(doc),\n            detailCommentCount = parseDetailCommentCount(doc),\n        )""",
)
replace_once(
    AVSEE,
    """        val resolved = arrayOfNulls<LocalDate>(rows.size)\n\n        suspend fun probe(index: Int): LocalDate {\n            resolved[index]?.let { return it }\n            val parsed = resolveBoardRowDate(rows[index], boardUrl, referenceInstant, dateCache)\n            resolved[index] = parsed\n            return parsed\n        }""",
    """        val resolved = arrayOfNulls<LocalDate>(rows.size)\n        val probeLocks = Array(rows.size) { Mutex() }\n\n        suspend fun probe(index: Int): LocalDate = probeLocks[index].withLock {\n            resolved[index]?.let { return@withLock it }\n            val parsed = resolveBoardRowDate(rows[index], boardUrl, referenceInstant, dateCache)\n            resolved[index] = parsed\n            parsed\n        }""",
)
replace_once(
    AVSEE,
    """        suspend fun resolveSequential(start: Int, end: Int) {\n            for (index in start..end) probe(index)\n            for (index in start until end) {""",
    """        suspend fun resolveSequential(start: Int, end: Int) {\n            coroutineScope {\n                (start..end).map { index -> async { probe(index) } }.awaitAll()\n            }\n            for (index in start until end) {""",
)
replace_once(
    AVSEE,
    """                resolveSegment(start, mid)\n                resolveSegment(mid, end)""",
    """                coroutineScope {\n                    listOf(\n                        async { resolveSegment(start, mid) },\n                        async { resolveSegment(mid, end) },\n                    ).awaitAll()\n                }""",
)
marker = "    private fun parseRecommendation(doc: Document): Int {\n"
helper = """    private fun parseDetailRecommendationCount(doc: Document): Int? {\n        doc.selectFirst(\"#wr_good\")?.text()?.let(::parseCountToken)?.let { return it }\n        doc.selectFirst(\".view-good\")?.let { scope ->\n            parseCountToken(scope.text())?.let { return it }\n        }\n        return parseMetricAfterMarker(doc.selectFirst(\".view-head .panel-heading .ellipsis\"), \".fa-thumbs-up\")\n    }\n\n    private fun parseDetailCommentCount(doc: Document): Int? {\n        parseMetricAfterMarker(doc.selectFirst(\".view-head .panel-heading .ellipsis\"), \".fa-comment\")?.let { return it }\n        val fallback = doc.selectFirst(\".view-comment\") ?: return null\n        if (fallback.selectFirst(\".fa-commenting, .fa-comment\") == null) return null\n        return parseCountToken(fallback.text())\n    }\n\n    private fun parseMetricAfterMarker(scope: Element?, markerSelector: String): Int? {\n        val marker = scope?.selectFirst(markerSelector) ?: return null\n        var node = marker.nextElementSibling()\n        while (node != null) {\n            parseCountToken(node.text())?.let { return it }\n            if (node.selectFirst(\".fa-comment, .fa-eye, .fa-thumbs-up\") != null) break\n            node = node.nextElementSibling()\n        }\n        return null\n    }\n\n"""
replace_once(AVSEE, marker, helper + marker)

SETTINGS = "app/src/main/java/com/shaterguy/fc2weeklyranker/data/SettingsStore.kt"
replace_once(
    SETTINGS,
    "val javFavoriteTags: Flow<Set<String>> = dataStore.data.map { it[JAV_FAVORITE_TAGS].orEmpty() }",
    """val javFavoriteTags: Flow<Set<String>> = favoriteTags(ContentMode.JAV)\n    val fc2FavoriteTags: Flow<Set<String>> = favoriteTags(ContentMode.FC2)""",
)
replace_once(
    SETTINGS,
    """    suspend fun toggleJavFavoriteTag(query: String) {\n        val tag = query.trim()\n        if (tag.isEmpty()) return\n        dataStore.edit { prefs ->\n            val current = prefs[JAV_FAVORITE_TAGS].orEmpty()\n            prefs[JAV_FAVORITE_TAGS] = if (tag in current) current - tag else current + tag\n        }\n    }""",
    """    fun favoriteTags(mode: ContentMode): Flow<Set<String>> =\n        dataStore.data.map { it[favoriteTagsKey(mode)].orEmpty() }\n\n    suspend fun toggleFavoriteTag(query: String, mode: ContentMode) {\n        val tag = query.trim()\n        if (tag.isEmpty()) return\n        val key = favoriteTagsKey(mode)\n        dataStore.edit { prefs ->\n            val current = prefs[key].orEmpty()\n            prefs[key] = if (tag in current) current - tag else current + tag\n        }\n    }\n\n    suspend fun toggleJavFavoriteTag(query: String) = toggleFavoriteTag(query, ContentMode.JAV)""",
)
replace_once(
    SETTINGS,
    """    private fun baseUrlKey(mode: ContentMode): Preferences.Key<String> =\n        if (mode == ContentMode.FC2) BASE_URL else JAV_BASE_URL\n""",
    """    private fun baseUrlKey(mode: ContentMode): Preferences.Key<String> =\n        if (mode == ContentMode.FC2) BASE_URL else JAV_BASE_URL\n\n    private fun favoriteTagsKey(mode: ContentMode): Preferences.Key<Set<String>> =\n        if (mode == ContentMode.FC2) FC2_FAVORITE_TAGS else JAV_FAVORITE_TAGS\n""",
)
replace_once(
    SETTINGS,
    "private val JAV_FAVORITE_TAGS = stringSetPreferencesKey(\"jav_favorite_tags\")",
    """private val JAV_FAVORITE_TAGS = stringSetPreferencesKey(\"jav_favorite_tags\")\n        private val FC2_FAVORITE_TAGS = stringSetPreferencesKey(\"fc2_favorite_tags\")""",
)

TAG_VM = "app/src/main/java/com/shaterguy/fc2weeklyranker/ui/TagFeatureViewModel.kt"
replace_once(
    TAG_VM,
    """    val tags: List<RemoteTag>,\n)""",
    """    val tags: List<RemoteTag>,\n    val detailRecommendationCount: Int?,\n    val detailCommentCount: Int?,\n)""",
)
replace_once(
    TAG_VM,
    "private val mutableTagOpeningPostId = MutableStateFlow<String?>(null)",
    """private val mutableTagOpeningPostId = MutableStateFlow<String?>(null)\n    private val mutableFavoriteTags = MutableStateFlow<Set<String>>(emptySet())""",
)
replace_once(
    TAG_VM,
    "private var tagSearchJob: Job? = null",
    """private var tagSearchJob: Job? = null\n    private var favoriteTagsJob: Job? = null""",
)
replace_once(
    TAG_VM,
    "val favoriteTags = repo.settings.javFavoriteTags.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())",
    "val favoriteTags = mutableFavoriteTags.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())",
)
replace_once(
    TAG_VM,
    """    val detailMessage = mutableDetailMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)\n\n    fun onContentModeChanged(mode: ContentMode) {\n        if (mode == currentMode) return\n        currentMode = mode\n        modeVersion += 1\n        if (mode != ContentMode.JAV) clearTagFeatureState()\n    }""",
    """    val detailMessage = mutableDetailMessage.stateIn(viewModelScope, SharingStarted.Eagerly, null)\n\n    init { refreshFavoriteTags(ContentMode.FC2) }\n\n    fun onContentModeChanged(mode: ContentMode) {\n        if (mode == currentMode) return\n        clearTagFeatureState()\n        currentMode = mode\n        modeVersion += 1\n        refreshFavoriteTags(mode)\n    }""",
)
replace_once(TAG_VM, "        if (currentMode != ContentMode.JAV) return\n        val term = query.trim()", "        val term = query.trim()")
replace_once(
    TAG_VM,
    """        if (term.isEmpty()) {\n            mutableTagQuery.value = \"\"\n            mutableTagResults.value = emptyList()\n            mutableTagMessage.value = null\n            tagLoading.value = false\n            return\n        }""",
    """        if (term.isEmpty()) {\n            tagSearchVersion += 1\n            tagSearchJob?.cancel()\n            tagSearchJob = null\n            mutableTagQuery.value = \"\"\n            mutableTagResults.value = emptyList()\n            mutableTagMessage.value = null\n            mutableTagOpeningPostId.value = null\n            tagLoading.value = false\n            return\n        }""",
)
replace_once(
    TAG_VM,
    """        val searchVersion = tagSearchVersion\n        val startModeVersion = modeVersion""",
    """        val searchVersion = tagSearchVersion\n        val startModeVersion = modeVersion\n        val requestMode = currentMode""",
)
replace_once(
    TAG_VM,
    """                val baseUrl = repo.settings.baseUrl(ContentMode.JAV).first()\n                val results = source.searchTagPosts(baseUrl, term).map { post ->\n                    post.copy(id = ContentMode.JAV.localPostId(post.id))\n                }\n                if (!isCurrentTagRequest(searchVersion, startModeVersion)) return@launch""",
    """                val baseUrl = repo.settings.baseUrl(requestMode).first()\n                val results = source.searchTagPosts(baseUrl, term, requestMode.boardTable).map { post ->\n                    post.copy(id = requestMode.localPostId(post.id))\n                }\n                if (!isCurrentTagRequest(searchVersion, startModeVersion, requestMode)) return@launch""",
)
replace_once(TAG_VM, "if (isCurrentTagRequest(searchVersion, startModeVersion)) {", "if (isCurrentTagRequest(searchVersion, startModeVersion, requestMode)) {")
replace_once(TAG_VM, "if (isCurrentTagRequest(searchVersion, startModeVersion)) tagLoading.value = false", "if (isCurrentTagRequest(searchVersion, startModeVersion, requestMode)) tagLoading.value = false")
replace_once(
    TAG_VM,
    """        if (currentMode != ContentMode.JAV || mutableTagOpeningPostId.value != null) return\n        val searchVersion = tagSearchVersion\n        val startModeVersion = modeVersion\n        val localId = ContentMode.JAV.localPostId(ContentMode.JAV.remotePostId(post.id))""",
    """        if (mutableTagOpeningPostId.value != null) return\n        val searchVersion = tagSearchVersion\n        val startModeVersion = modeVersion\n        val requestMode = currentMode\n        val localId = requestMode.localPostId(requestMode.remotePostId(post.id))""",
)
replace_once(TAG_VM, "sourceKey = ContentMode.JAV.sourceKey,", "sourceKey = requestMode.sourceKey,")
replace_once(TAG_VM, "if (isCurrentTagRequest(searchVersion, startModeVersion)) onReady(localId)", "if (isCurrentTagRequest(searchVersion, startModeVersion, requestMode)) onReady(localId)")
replace_once(TAG_VM, "if (isCurrentTagRequest(searchVersion, startModeVersion)) {\n                    mutableTagMessage.value = \"게시물 열기 실패: ${safeMessage(error)}\"", "if (isCurrentTagRequest(searchVersion, startModeVersion, requestMode)) {\n                    mutableTagMessage.value = \"게시물 열기 실패: ${safeMessage(error)}\"")
replace_once(
    TAG_VM,
    """                tags = if (mode == ContentMode.JAV) detail.tags else emptyList(),\n            )""",
    """                tags = detail.tags,\n                detailRecommendationCount = detail.detailRecommendationCount,\n                detailCommentCount = detail.detailCommentCount,\n            )""",
)
replace_once(
    TAG_VM,
    """    fun toggleFavoriteTag(query: String) {\n        if (currentMode != ContentMode.JAV || query.isBlank()) return\n        viewModelScope.launch { repo.settings.toggleJavFavoriteTag(query) }\n    }""",
    """    fun toggleFavoriteTag(query: String) {\n        if (query.isBlank()) return\n        val requestMode = currentMode\n        val startModeVersion = modeVersion\n        viewModelScope.launch {\n            repo.settings.toggleFavoriteTag(query, requestMode)\n            if (currentMode == requestMode && modeVersion == startModeVersion) {\n                mutableFavoriteTags.value = repo.settings.favoriteTags(requestMode).first()\n            }\n        }\n    }""",
)
replace_once(
    TAG_VM,
    """    private fun isCurrentTagRequest(searchVersion: Long, startModeVersion: Long): Boolean =\n        currentMode == ContentMode.JAV &&\n            modeVersion == startModeVersion &&\n            tagSearchVersion == searchVersion\n""",
    """    private fun refreshFavoriteTags(mode: ContentMode) {\n        favoriteTagsJob?.cancel()\n        val startModeVersion = modeVersion\n        favoriteTagsJob = viewModelScope.launch {\n            val tags = repo.settings.favoriteTags(mode).first()\n            if (currentMode == mode && modeVersion == startModeVersion) mutableFavoriteTags.value = tags\n        }\n    }\n\n    private fun isCurrentTagRequest(searchVersion: Long, startModeVersion: Long, requestMode: ContentMode): Boolean =\n        currentMode == requestMode &&\n            modeVersion == startModeVersion &&\n            tagSearchVersion == searchVersion\n""",
)

MAIN = "app/src/main/java/com/shaterguy/fc2weeklyranker/MainActivity.kt"
replace_once(
    MAIN,
    """private val fc2Destinations = listOf(\n    TopDestination(\"ranking\", \"랭킹\", \"▦\"),\n    TopDestination(\"search\", \"검색\", \"⌕\"),\n    TopDestination(\"favorites\", \"즐겨찾기\", \"♥\"),""",
    """private val fc2Destinations = listOf(\n    TopDestination(\"ranking\", \"랭킹\", \"▦\"),\n    TopDestination(\"search\", \"검색\", \"⌕\"),\n    TopDestination(\"tags\", \"태그\", \"#\"),\n    TopDestination(\"favorites\", \"즐겨찾기\", \"♥\"),""",
)
replace_once(
    MAIN,
    """    LaunchedEffect(contentMode, route) {\n        tagVm.onContentModeChanged(contentMode)\n        if (contentMode != ContentMode.JAV && route in setOf(\"tags\", \"tag-results\")) {\n            nav.navigate(\"ranking\") {\n                popUpTo(\"ranking\") { inclusive = false }\n                launchSingleTop = true\n            }\n        }\n    }""",
    """    LaunchedEffect(contentMode, route) {\n        tagVm.onContentModeChanged(contentMode)\n    }""",
)
replace_once(MAIN, "TagScreen(tagVm) { query ->", "TagScreen(tagVm, contentMode) { query ->")
replace_once(
    MAIN,
    """    var activeMediaExpected by remember(postId) { mutableStateOf(false) }\n    var detailTags by remember(postId) { mutableStateOf<List<RemoteTag>>(emptyList()) }""",
    """    var activeMediaExpected by remember(postId) { mutableStateOf(false) }\n    var detailTags by remember(postId) { mutableStateOf<List<RemoteTag>>(emptyList()) }\n    var detailRecommendationCount by remember(postId) { mutableStateOf<Int?>(null) }\n    var detailCommentCount by remember(postId) { mutableStateOf<Int?>(null) }""",
)
replace_once(
    MAIN,
    """        activeMediaExpected = false\n        detailTags = emptyList()\n        vm.openPost(postId)""",
    """        activeMediaExpected = false\n        detailTags = emptyList()\n        detailRecommendationCount = null\n        detailCommentCount = null\n        vm.openPost(postId)""",
)
replace_once(
    MAIN,
    """            activeMediaExpected = result.hasActiveMedia\n            detailTags = result.tags\n            syncSucceeded = true""",
    """            activeMediaExpected = result.hasActiveMedia\n            detailTags = result.tags\n            detailRecommendationCount = result.detailRecommendationCount\n            detailCommentCount = result.detailCommentCount\n            syncSucceeded = true""",
)
replace_once(
    MAIN,
    """                    }\n                } else {\n                    Text(\"게시물\", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)""",
    """                    }\n                    if (syncFinished) {\n                        Text(\n                            \"추천 ${detailRecommendationCount?.toString() ?: \"확인 불가\"} · 댓글 ${detailCommentCount?.toString() ?: \"확인 불가\"}\",\n                            style = MaterialTheme.typography.bodyMedium,\n                        )\n                    }\n                } else {\n                    Text(\"게시물\", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)""",
)

TAG_SCREEN = "app/src/main/java/com/shaterguy/fc2weeklyranker/ui/TagScreen.kt"
replace_once(
    TAG_SCREEN,
    "import com.shaterguy.fc2weeklyranker.network.RemoteTagPost",
    "import com.shaterguy.fc2weeklyranker.domain.ContentMode\nimport com.shaterguy.fc2weeklyranker.network.RemoteTagPost",
)
replace_once(
    TAG_SCREEN,
    """fun TagScreen(\n    vm: TagFeatureViewModel,\n    onSearch: (String) -> Unit,\n) {""",
    """fun TagScreen(\n    vm: TagFeatureViewModel,\n    contentMode: ContentMode,\n    onSearch: (String) -> Unit,\n) {""",
)
replace_once(TAG_SCREEN, "Text(\"JAV 태그\", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)", "Text(\"${contentMode.sourceKey} 태그\", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)")

INSTRUMENTED = "app/src/androidTest/java/com/shaterguy/fc2weeklyranker/data/SettingsStoreInstrumentedTest.kt"
replace_once(
    INSTRUMENTED,
    "import androidx.test.platform.app.InstrumentationRegistry",
    "import androidx.test.platform.app.InstrumentationRegistry\nimport com.shaterguy.fc2weeklyranker.domain.ContentMode",
)
replace_once(
    INSTRUMENTED,
    """    @Test\n    fun javFavoriteTagPersistsAcrossStoreWrappers() = runBlocking {""",
    """    @Test\n    fun modeFavoriteTagsRemainSeparated() = runBlocking {\n        val context = InstrumentationRegistry.getInstrumentation().targetContext\n        val tag = \"selfrun-dev36-mode-tag\"\n        val store = SettingsStore(context)\n        if (tag in store.favoriteTags(ContentMode.FC2).first()) store.toggleFavoriteTag(tag, ContentMode.FC2)\n        if (tag in store.favoriteTags(ContentMode.JAV).first()) store.toggleFavoriteTag(tag, ContentMode.JAV)\n        try {\n            store.toggleFavoriteTag(tag, ContentMode.FC2)\n            assertTrue(tag in store.favoriteTags(ContentMode.FC2).first())\n            assertFalse(tag in store.favoriteTags(ContentMode.JAV).first())\n            store.toggleFavoriteTag(tag, ContentMode.JAV)\n            assertTrue(tag in store.favoriteTags(ContentMode.JAV).first())\n        } finally {\n            val cleanup = SettingsStore(context)\n            if (tag in cleanup.favoriteTags(ContentMode.FC2).first()) cleanup.toggleFavoriteTag(tag, ContentMode.FC2)\n            if (tag in cleanup.favoriteTags(ContentMode.JAV).first()) cleanup.toggleFavoriteTag(tag, ContentMode.JAV)\n        }\n    }\n\n    @Test\n    fun javFavoriteTagPersistsAcrossStoreWrappers() = runBlocking {""",
)

BUILD = "app/build.gradle.kts"
replace_once(BUILD, "versionCode = 59", "versionCode = 60")
replace_once(BUILD, 'versionNameSuffix = "-dev35"', 'versionNameSuffix = "-dev36"')

README = "README.md"
replace_once(README, "current development target is `v0.2.0-dev26`", "current development target is `v0.2.0-dev36`")
replace_once(README, "Source version: `0.2.0-dev26`, `versionCode=50`", "Source version: `0.2.0-dev36`, `versionCode=60`")

print("dev36 production transformation applied")
