package com.shaterguy.fc2weeklyranker

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.shaterguy.fc2weeklyranker.data.PostEntity
import com.shaterguy.fc2weeklyranker.data.VideoEntity
import com.shaterguy.fc2weeklyranker.domain.windowFor
import com.shaterguy.fc2weeklyranker.media.NativeVideoPlayer
import com.shaterguy.fc2weeklyranker.media.RestrictedIframePlayer
import com.shaterguy.fc2weeklyranker.ui.MainViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { RankerApp() } } }
}

private data class TopDestination(val route: String, val label: String, val glyph: String)
private val destinations = listOf(TopDestination("ranking", "랭킹", "▦"), TopDestination("favorites", "즐겨찾기", "♥"), TopDestination("settings", "설정", "⚙"))

@Composable
private fun RankerApp(vm: MainViewModel = viewModel()) {
    val nav = rememberNavController(); val backStack by nav.currentBackStackEntryAsState(); val route = backStack?.destination?.route.orEmpty(); val showBottom = destinations.any { it.route == route }
    Scaffold(bottomBar = { if (showBottom) NavigationBar { destinations.forEach { destination -> NavigationBarItem(selected = route == destination.route, onClick = { nav.navigate(destination.route) { launchSingleTop = true } }, icon = { Text(destination.glyph) }, label = { Text(destination.label) }) } } }) { padding ->
        NavHost(navController = nav, startDestination = "ranking", modifier = Modifier.padding(padding)) {
            composable("ranking") { RankingScreen(vm) { id -> nav.navigate("detail/${Uri.encode(id)}") } }
            composable("favorites") { FavoritesScreen(vm) { id -> nav.navigate("detail/${Uri.encode(id)}") } }
            composable("settings") { SettingsScreen(vm) }
            composable("detail/{postId}", arguments = listOf(navArgument("postId") { type = NavType.StringType })) { entry -> VideoDetailScreen(vm, entry.arguments?.getString("postId").orEmpty()) { nav.popBackStack() } }
        }
    }
}

@Composable
private fun RankingScreen(vm: MainViewModel, onPost: (String) -> Unit) {
    val posts by vm.posts.collectAsState(); val anchor by vm.anchorEpochMillis.collectAsState(); val page by vm.pageIndex.collectAsState(); val loading by vm.isLoading.collectAsState(); val message by vm.message.collectAsState(); val window = remember(anchor, page) { windowFor(Instant.ofEpochMilli(anchor), page) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp)); Text("7일 추천 랭킹", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("기준 ${formatDateTime(anchor)}"); Text("${window.startDate} ∼ ${window.endDate}", style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { OutlinedButton(onClick = vm::newerPage, enabled = page > 0 && !loading) { Text("더 최근") }; TextButton(onClick = vm::refreshAnchor, enabled = !loading) { Text("기준 새로고침") }; OutlinedButton(onClick = vm::olderPage, enabled = !loading) { Text("이전 7일") } }
        StatusLine(loading, message, vm::clearMessage); if (!loading && posts.isEmpty()) Text("이 기간에 표시할 게시물이 없습니다.", Modifier.padding(vertical = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) { itemsIndexed(posts, key = { _, post -> post.id }) { index, post -> PostCard(index + 1, post, onPost, { vm.toggleFavorite(post.id) }) } }
    }
}

@Composable
private fun FavoritesScreen(vm: MainViewModel, onPost: (String) -> Unit) {
    val posts by vm.favorites.collectAsState(); Column(Modifier.fillMaxSize().padding(16.dp)) { Text("즐겨찾기", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); if (posts.isEmpty()) Text("저장한 게시물이 없습니다."); LazyColumn { itemsIndexed(posts, key = { _, post -> post.id }) { index, post -> PostCard(index + 1, post, onPost, { vm.toggleFavorite(post.id) }, false) } } }
}

@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val baseUrl by vm.baseUrl.collectAsState(); val anchor by vm.anchorEpochMillis.collectAsState(); val loading by vm.isLoading.collectAsState(); val message by vm.message.collectAsState(); var input by remember(baseUrl) { mutableStateOf(baseUrl) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("사이트 기본 주소") }, supportingText = { Text("예: https://01.avsee.is · HTTPS 기본 주소만 저장됩니다.") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.saveBaseUrl(input) }, enabled = !loading) { Text("저장 후 확인") }; OutlinedButton(onClick = vm::testConnection, enabled = !loading) { Text("연결 테스트") } }; HorizontalDivider(); Text("고정 기준시각"); Text(formatDateTime(anchor), fontWeight = FontWeight.SemiBold); Text("랭킹 화면에서 ‘기준 새로고침’을 누르기 전에는 앱을 다시 열어도 이 시간이 바뀌지 않습니다."); StatusLine(loading, message, vm::clearMessage)
    }
}

@Composable
private fun VideoDetailScreen(vm: MainViewModel, postId: String, onBack: () -> Unit) {
    val videosFlow = remember(postId) { vm.videos(postId) }; val videos by videosFlow.collectAsState(initial = emptyList()); val loading by vm.isLoading.collectAsState(); val message by vm.message.collectAsState(); LaunchedEffect(postId) { vm.loadVideos(postId) }
    Column(Modifier.fillMaxSize().padding(16.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = onBack) { Text("← 뒤로") }; Text("영상", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }; StatusLine(loading, message, vm::clearMessage); if (!loading && videos.isEmpty()) Text("영상 소스를 찾지 못했습니다. 사이트 구조 변경 여부를 설정에서 확인하세요."); LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) { itemsIndexed(videos, key = { _, video -> video.id }) { index, video -> VideoCard(vm, postId, index, video) } } }
}

@Composable
private fun VideoCard(vm: MainViewModel, postId: String, index: Int, video: VideoEntity) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("영상 ${index + 1}", fontWeight = FontWeight.SemiBold); if (video.sourceKind == "DIRECT") { NativeVideoPlayer(video); Button(onClick = { vm.queueDownload(video.id) }, modifier = Modifier.semantics { contentDescription = "영상 ${index + 1} 다운로드" }) { Text("다운로드") } } else { Text("원본 iframe 재생 문맥으로 영상을 불러옵니다."); RestrictedIframePlayer(video, { candidate -> vm.registerProbedVideo(postId, candidate, video.url, index + 100) }) } } }
}

@Composable
private fun PostCard(rank: Int, post: PostEntity, onPost: (String) -> Unit, onFavorite: () -> Unit, showRank: Boolean = true) {
    Card(Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPost(post.id) }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { if (showRank) Text("$rank", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp)); Column(Modifier.weight(1f)) { Text(post.title, maxLines = 2, fontWeight = FontWeight.SemiBold); Text("추천 ${post.recommendationCount} · 일평균 ${"%.2f".format(post.dailyRate)}"); Text(formatDate(post.postedAtEpochMillis), style = MaterialTheme.typography.bodySmall) }; TextButton(onClick = onFavorite, modifier = Modifier.semantics { contentDescription = "즐겨찾기 전환" }) { Text("♥") } } }
}

@Composable
private fun StatusLine(loading: Boolean, message: String?, clear: () -> Unit) { if (loading) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(8.dp)); Text("불러오는 중…") }; if (message != null) TextButton(onClick = clear) { Text(message) } }
private val SEOUL = ZoneId.of("Asia/Seoul"); private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"); private val DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")
private fun formatDateTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(SEOUL).format(DATE_TIME)
private fun formatDate(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).atZone(SEOUL).format(DATE)
