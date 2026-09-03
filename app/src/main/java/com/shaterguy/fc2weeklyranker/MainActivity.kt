package com.shaterguy.fc2weeklyranker

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.domain.windowFor
import com.shaterguy.fc2weeklyranker.download.VideoDownloadWorker
import com.shaterguy.fc2weeklyranker.media.NativeVideoPlayer
import com.shaterguy.fc2weeklyranker.media.RestrictedIframePlayer
import com.shaterguy.fc2weeklyranker.repo.AppRepository
import com.shaterguy.fc2weeklyranker.ui.DownloadScreen
import com.shaterguy.fc2weeklyranker.ui.MainViewModel
import com.shaterguy.fc2weeklyranker.ui.SearchScreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RankerApp() } }
    }
}

private data class TopDestination(val route: String, val label: String, val glyph: String)
private val destinations = listOf(
    TopDestination("ranking", "랭킹", "▦"),
    TopDestination("search", "검색", "⌕"),
    TopDestination("favorites", "즐겨찾기", "♥"),
    TopDestination("downloads", "다운로드", "↓"),
    TopDestination("settings", "설정", "⚙"),
)

internal data class DetailPostNeighbors(val previousId: String?, val nextId: String?)

internal fun detailPostNeighbors(postIds: List<String>, currentPostId: String): DetailPostNeighbors {
    val currentIndex = postIds.indexOf(currentPostId)
    if (currentIndex < 0 || postIds.size <= 1) return DetailPostNeighbors(null, null)
    val lastIndex = postIds.lastIndex
    return DetailPostNeighbors(
        previousId = postIds[if (currentIndex == 0) lastIndex else currentIndex - 1],
        nextId = postIds[if (currentIndex == lastIndex) 0 else currentIndex + 1],
    )
}

@Composable
private fun RankerApp(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()
    val showBottom = destinations.any { it.route == route }
    val rankingPosts by vm.posts.collectAsState()
    val rankingPostIds = remember(rankingPosts) { rankingPosts.map { it.id } }
    var detailPostIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var detailListRoute by rememberSaveable { mutableStateOf("ranking") }
    val openDetail: (String, String, List<String>) -> Unit = { id, listRoute, postIds ->
        detailPostIds = ArrayList(postIds)
        detailListRoute = listRoute
        nav.navigate("detail/${Uri.encode(id)}")
    }
    Scaffold(
        bottomBar = {
            if (showBottom) {
                NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = route == destination.route,
                            onClick = { nav.navigate(destination.route) { launchSingleTop = true } },
                            icon = { Text(destination.glyph) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "ranking",
            modifier = Modifier.padding(padding),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
            predictivePopEnterTransition = { EnterTransition.None },
            predictivePopExitTransition = { ExitTransition.None },
        ) {
            composable("ranking") {
                RankingScreen(vm) { id -> openDetail(id, "ranking", rankingPostIds) }
            }
            composable("search") { SearchScreen(vm) }
            composable("favorites") {
                FavoritesScreen(vm) { id -> openDetail(id, "favorites", emptyList()) }
            }
            composable("downloads") {
                DownloadScreen(onPost = { id -> openDetail(id, "downloads", emptyList()) })
            }
            composable("settings") { SettingsScreen(vm) }
            composable(
                "detail/{postId}",
                arguments = listOf(navArgument("postId") { type = NavType.StringType }),
            ) { entry ->
                VideoDetailScreen(
                    vm = vm,
                    postId = entry.arguments?.getString("postId").orEmpty(),
                    navigationPostIds = detailPostIds,
                    onList = {
                        if (!nav.popBackStack(detailListRoute, inclusive = false)) {
                            nav.navigate(detailListRoute) { launchSingleTop = true }
                        }
                    },
                    onPost = { id -> nav.navigate("detail/${Uri.encode(id)}") },
                )
            }
        }
    }
}

@Composable
private fun RankingScreen(vm: MainViewModel, onPost: (String) -> Unit) {
    val posts by vm.posts.collectAsState()
    val favoritePosts by vm.favorites.collectAsState()
    val visited by vm.visitedPostIds.collectAsState()
    val anchor by vm.anchorEpochMillis.collectAsState()
    val page by vm.pageIndex.collectAsState()
    val loading by vm.isLoading.collectAsState()
    val message by vm.message.collectAsState()
    val favoriteIds = remember(favoritePosts) { favoritePosts.mapTo(hashSetOf()) { it.id } }
    val window = remember(anchor, page) { windowFor(Instant.ofEpochMilli(anchor), page) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("7일 댓글 랭킹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("기준 ${formatDateTime(anchor)}")
        Text("${window.startDate} ∼ ${window.endDate}", style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = vm::newerPage, enabled = page > 0 && !loading) { Text("더 최근") }
            TextButton(onClick = vm::refreshAnchor, enabled = !loading) { Text("기준 새로고침") }
            OutlinedButton(onClick = vm::olderPage, enabled = !loading) { Text("이전 7일") }
        }
        StatusLine(loading, message, vm::clearMessage)
        if (!loading && posts.isEmpty()) Text("이 기간에 표시할 게시물이 없습니다.", Modifier.padding(vertical = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            itemsIndexed(posts, key = { _, post -> post.id }) { index, post ->
                PostCard(
                    rank = index + 1,
                    post = post,
                    onPost = onPost,
                    visited = visited.contains(post.id),
                    favorite = post.id in favoriteIds,
                )
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: MainViewModel, onPost: (String) -> Unit) {
    val posts by vm.favorites.collectAsState()
    val visited by vm.visitedPostIds.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("즐겨찾기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (posts.isEmpty()) Text("저장한 게시물이 없습니다.")
        LazyColumn {
            itemsIndexed(posts, key = { _, post -> post.id }) { index, post ->
                PostCard(
                    rank = index + 1,
                    post = post,
                    onPost = onPost,
                    visited = visited.contains(post.id),
                    favorite = true,
                    showRank = false,
                    showMetrics = false,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val baseUrl by vm.baseUrl.collectAsState()
    val anchor by vm.anchorEpochMillis.collectAsState()
    val loading by vm.isLoading.collectAsState()
    val message by vm.message.collectAsState()
    var input by remember(baseUrl) { mutableStateOf(baseUrl) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("사이트 기본 주소") },
            supportingText = { Text("예: https://01.avsee.is · HTTPS 기본 주소만 저장됩니다.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveBaseUrl(input) }, enabled = !loading) { Text("저장 후 확인") }
            OutlinedButton(onClick = vm::testConnection, enabled = !loading) { Text("연결 테스트") }
        }
        HorizontalDivider()
        Text("고정 기준시각")
        Text(formatDateTime(anchor), fontWeight = FontWeight.SemiBold)
        Text("랭킹 화면에서 ‘기준 새로고침’을 누르기 전에는 앱을 다시 열어도 이 시간이 바뀌지 않습니다.")
        StatusLine(loading, message, vm::clearMessage)
    }
}

@Composable
private fun VideoDetailScreen(
    vm: MainViewModel,
    postId: String,
    navigationPostIds: List<String>,
    onList: () -> Unit,
    onPost: (String) -> Unit,
) {
    val videos by remember(postId) { vm.videos(postId) }.collectAsState(initial = emptyList())
    val post by remember(postId) { vm.post(postId) }.collectAsState(initial = null)
    val favorite by remember(postId) { vm.isFavorite(postId) }.collectAsState(initial = false)
    val previousPost by remember(postId) { vm.previousPost(postId) }.collectAsState(initial = null)
    val nextPost by remember(postId) { vm.nextPost(postId) }.collectAsState(initial = null)
    val loading by vm.isLoading.collectAsState()
    val message by vm.message.collectAsState()
    val uriHandler = LocalUriHandler.current
    var refreshStartedAt by remember(postId) { mutableStateOf<Long?>(null) }
    var syncFinished by remember(postId) { mutableStateOf(false) }
    var syncSucceeded by remember(postId) { mutableStateOf(false) }
    var activeMediaExpected by remember(postId) { mutableStateOf(false) }
    val neighbors = remember(postId, navigationPostIds, previousPost?.id, nextPost?.id) {
        if (navigationPostIds.isNotEmpty()) {
            detailPostNeighbors(navigationPostIds, postId)
        } else {
            DetailPostNeighbors(previousPost?.id, nextPost?.id)
        }
    }
    val directVideos = remember(videos, refreshStartedAt) { visibleDirectVideos(videos, refreshStartedAt) }
    val resolvers = remember(videos, refreshStartedAt) { visibleDetailResolvers(videos, refreshStartedAt) }
    val freshRowsArrived = directVideos.isNotEmpty() || resolvers.isNotEmpty()
    val contentReady = syncFinished && syncSucceeded && (!activeMediaExpected || freshRowsArrived)
    val originalUrl = post?.url?.takeIf { it.startsWith("https://") }

    LaunchedEffect(postId) {
        refreshStartedAt = null
        syncFinished = false
        syncSucceeded = false
        activeMediaExpected = false
        vm.openPost(postId)
        val result = vm.loadVideos(postId)
        if (result != null) {
            refreshStartedAt = result.refreshStartedAtEpochMillis
            activeMediaExpected = result.hasActiveMedia
            syncSucceeded = true
        }
        syncFinished = true
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            TextButton(onClick = onList) { Text("목록") }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val currentPost = post
                if (currentPost != null) {
                    Text(currentPost.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "${formatDate(currentPost.postedAtEpochMillis)} · 댓글 ${currentPost.recommendationCount} · 일평균 ${"%.2f".format(currentPost.dailyRate)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text("게시물", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = { neighbors.previousId?.let(onPost) },
                enabled = neighbors.previousId != null,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "이전 게시물로 이동" },
            ) { Text("← 이전", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { originalUrl?.let { url -> uriHandler.openUri(url) } },
                enabled = originalUrl != null,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "원본 게시물 페이지 열기" },
            ) { Text("원본페이지", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            if (favorite) {
                Button(
                    onClick = { vm.toggleFavorite(postId) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "즐겨찾기 해제" },
                ) { Text("즐겨찾기", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            } else {
                OutlinedButton(
                    onClick = { vm.toggleFavorite(postId) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "즐겨찾기에 추가" },
                ) { Text("즐겨찾기", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
            }
            OutlinedButton(
                onClick = { neighbors.nextId?.let(onPost) },
                enabled = neighbors.nextId != null,
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "다음 게시물로 이동" },
            ) { Text("다음 →", maxLines = 1, style = MaterialTheme.typography.labelSmall) }
        }
        StatusLine(loading, message, vm::clearMessage)

        if (contentReady) {
            resolvers.forEach { resolver ->
                RestrictedIframePlayer(
                    video = resolver,
                    onMediaDiscovered = { candidate ->
                        vm.registerProbedVideo(postId, candidate, resolver.url, resolver.ordinal)
                    },
                    modifier = Modifier.fillMaxWidth().height(1.dp).alpha(0f),
                )
            }
            if (directVideos.isEmpty() && resolvers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(8.dp))
                    Text("재생 가능한 영상을 확인 중입니다…")
                }
            }
            if (!loading && !activeMediaExpected) {
                Text("영상 소스를 찾지 못했습니다. 사이트 구조 변경 여부를 설정에서 확인하세요.")
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(directVideos, key = { _, video -> video.id }) { index, video ->
                    VideoCard(vm, index, video)
                }
            }
        } else if (syncFinished && syncSucceeded && activeMediaExpected) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text("영상 목록을 반영하는 중입니다…")
            }
        } else if (syncFinished && !syncSucceeded) {
            Text("영상 정보를 새로 불러오지 못했습니다.", color = MaterialTheme.colorScheme.error)
        }
    }
}

internal fun visibleDirectVideos(videos: List<VideoEntity>, refreshStartedAtEpochMillis: Long?): List<VideoEntity> {
    val cutoff = refreshStartedAtEpochMillis ?: return emptyList()
    val seen = linkedSetOf<String>()
    return videos.asSequence()
        .filter { it.sourceKind == AppRepository.SOURCE_DIRECT && it.discoveredAtEpochMillis >= cutoff }
        .filter { seen.add(AppRepository.canonicalMediaKey(it.url)) }
        .toList()
}

internal fun visibleDetailResolvers(videos: List<VideoEntity>, refreshStartedAtEpochMillis: Long?): List<VideoEntity> {
    val cutoff = refreshStartedAtEpochMillis ?: return emptyList()
    return videos.filter { it.sourceKind == AppRepository.SOURCE_IFRAME && it.discoveredAtEpochMillis >= cutoff }
}

@Composable
private fun VideoCard(vm: MainViewModel, index: Int, video: VideoEntity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("영상 ${index + 1}", fontWeight = FontWeight.SemiBold)
            NativeVideoPlayer(video)
            DownloadControls(vm, video, index)
        }
    }
}

@Composable
private fun DownloadControls(vm: MainViewModel, video: VideoEntity, index: Int) {
    val download by remember(video.id) { vm.download(video.id) }.collectAsState(initial = null)
    val state = download
    when {
        !VideoDownloadWorker.supportsFileDownload(video.url) -> {
            Text("스트리밍 주소는 동영상 파일로 저장할 수 없습니다.", style = MaterialTheme.typography.bodySmall)
        }
        state?.status == DownloadStatus.QUEUED -> {
            Text("다운로드 대기 중", style = MaterialTheme.typography.bodySmall)
            state.errorCode?.let { Text("재시도 대기: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(
                onClick = { vm.stopDownload(video.id) },
                modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 정지" },
            ) { Text("정지") }
        }
        state?.status == DownloadStatus.RUNNING -> {
            DownloadProgress(state.downloadedBytes, state.totalBytes)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.pauseDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 일시정지" },
                ) { Text("일시정지") }
                OutlinedButton(
                    onClick = { vm.stopDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 정지" },
                ) { Text("정지") }
            }
        }
        state?.status == DownloadStatus.PAUSED -> {
            DownloadProgress(state.downloadedBytes, state.totalBytes, prefix = "일시정지")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.queueDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 계속" },
                ) { Text("계속 다운로드") }
                OutlinedButton(
                    onClick = { vm.stopDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 정지" },
                ) { Text("정지") }
            }
        }
        state?.status == DownloadStatus.FINALIZING -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("다운로드 파일 저장을 마무리하고 있습니다…", style = MaterialTheme.typography.bodySmall)
        }
        state?.status == DownloadStatus.COMPLETED -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { vm.queueDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드" },
                ) { Text("다운로드") }
                Text(
                    "다운로드 기록 ${formatDateTime(state.updatedAtEpochMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        state?.status == DownloadStatus.FAILED -> {
            Text("다운로드 실패: ${state.errorCode ?: "알 수 없는 오류"}", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { vm.queueDownload(video.id) },
                    modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 다시 시도" },
                ) { Text("다시 다운로드") }
                if (state.contentUri != null) {
                    TextButton(
                        onClick = { vm.stopDownload(video.id) },
                        modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 부분 다운로드 삭제" },
                    ) { Text("부분 파일 삭제") }
                }
            }
        }
        state?.status == DownloadStatus.STOPPED -> {
            Text("다운로드를 정지했습니다.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { vm.queueDownload(video.id) },
                modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드 새로 시작" },
            ) { Text("다운로드") }
        }
        else -> {
            Button(
                onClick = { vm.queueDownload(video.id) },
                modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드" },
            ) { Text("다운로드") }
        }
    }
}

@Composable
private fun DownloadProgress(downloadedBytes: Long, totalBytes: Long?, prefix: String = "다운로드 중") {
    if (totalBytes != null && totalBytes > 0L) {
        LinearProgressIndicator(
            progress = { (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    Text("$prefix ${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PostCard(
    rank: Int,
    post: PostEntity,
    onPost: (String) -> Unit,
    visited: Boolean,
    favorite: Boolean,
    showRank: Boolean = true,
    showMetrics: Boolean = true,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable { onPost(post.id) }
            .semantics {
                contentDescription = when {
                    favorite && visited -> "즐겨찾기한 방문한 게시물: ${post.title}"
                    favorite -> "즐겨찾기한 게시물: ${post.title}"
                    visited -> "방문한 게시물: ${post.title}"
                    else -> "게시물: ${post.title}"
                }
            },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showRank) {
                Text(
                    "$rank",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    post.title,
                    maxLines = 2,
                    fontWeight = FontWeight.SemiBold,
                    color = if (visited) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                if (favorite) {
                    Text(
                        "★ 즐겨찾기",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (showMetrics) {
                    Text("댓글 ${post.recommendationCount} · 일평균 ${"%.2f".format(post.dailyRate)}")
                }
                Text(formatDate(post.postedAtEpochMillis), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun StatusLine(loading: Boolean, message: String?, clear: () -> Unit) {
    if (loading) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(8.dp))
            Text("불러오는 중…")
        }
    }
    if (message != null) TextButton(onClick = clear) { Text(message) }
}

private fun formatBytes(value: Long?): String {
    if (value == null || value < 0L) return "알 수 없음"
    return when {
        value >= 1_073_741_824L -> "%.1f GB".format(value / 1_073_741_824.0)
        value >= 1_048_576L -> "%.1f MB".format(value / 1_048_576.0)
        value >= 1_024L -> "%.1f KB".format(value / 1_024.0)
        else -> "$value B"
    }
}

private val SEOUL = ZoneId.of("Asia/Seoul")
private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd")
private fun formatDateTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(SEOUL).format(DATE_TIME)
private fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(SEOUL).format(DATE)
