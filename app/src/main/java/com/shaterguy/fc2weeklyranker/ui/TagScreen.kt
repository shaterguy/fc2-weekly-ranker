package com.shaterguy.fc2weeklyranker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shaterguy.fc2weeklyranker.network.RemoteTagPost

internal enum class TagSortMode { ORIGINAL, COMMENTS, VIEWS }

internal fun sortTagResults(
    results: List<RemoteTagPost>,
    mode: TagSortMode,
): List<RemoteTagPost> = when (mode) {
    TagSortMode.ORIGINAL -> results
    TagSortMode.COMMENTS -> results.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<RemoteTagPost>> { it.value.commentCount }
                .thenBy { it.index },
        )
        .map { it.value }
    TagSortMode.VIEWS -> results.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<RemoteTagPost>> { it.value.viewCount }
                .thenBy { it.index },
        )
        .map { it.value }
}

@Composable
fun TagScreen(
    vm: TagFeatureViewModel,
    onSearch: (String) -> Unit,
) {
    val favorites by vm.favoriteTags.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val favoriteList = remember(favorites) { favorites.sortedWith(String.CASE_INSENSITIVE_ORDER) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("JAV 태그", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("태그 검색 결과의 댓글수·조회수를 확인하고 정렬할 수 있습니다.")
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("태그") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onSearch(query) },
                enabled = query.isNotBlank(),
            ) { Text("검색") }
        }
        Text("즐겨찾는 태그", fontWeight = FontWeight.SemiBold)
        if (favoriteList.isEmpty()) {
            Text("저장한 태그가 없습니다.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(favoriteList, key = { it }) { tag ->
                    OutlinedButton(
                        onClick = { onSearch(tag) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "즐겨찾는 태그 $tag 검색" },
                    ) { Text(tag) }
                }
            }
        }
    }
}

@Composable
fun TagResultsScreen(
    vm: TagFeatureViewModel,
    onBack: () -> Unit,
    onPost: (RemoteTagPost, List<String>) -> Unit,
) {
    val query by vm.tagQuery.collectAsState()
    val results by vm.tagResults.collectAsState()
    val loading by vm.isTagLoading.collectAsState()
    val message by vm.tagMessage.collectAsState()
    val openingPostId by vm.tagOpeningPostId.collectAsState()
    val favorites by vm.favoriteTags.collectAsState()
    var sortName by rememberSaveable(query) { mutableStateOf(TagSortMode.ORIGINAL.name) }
    val sortMode = remember(sortName) {
        runCatching { TagSortMode.valueOf(sortName) }.getOrDefault(TagSortMode.ORIGINAL)
    }
    val displayed = remember(results, sortMode) { sortTagResults(results, sortMode) }
    val postIds = remember(displayed) { displayed.map { it.id } }
    val favorite = query.isNotBlank() && query in favorites

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("태그") }
            Text(
                if (query.isBlank()) "태그 검색" else "#$query",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            if (query.isNotBlank()) {
                if (favorite) {
                    Button(onClick = { vm.toggleFavoriteTag(query) }) { Text("★ 저장됨") }
                } else {
                    OutlinedButton(onClick = { vm.toggleFavoriteTag(query) }) { Text("☆ 저장") }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TagSortButton("기본", TagSortMode.ORIGINAL, sortMode, Modifier.weight(1f)) { sortName = it.name }
            TagSortButton("댓글순", TagSortMode.COMMENTS, sortMode, Modifier.weight(1f)) { sortName = it.name }
            TagSortButton("조회순", TagSortMode.VIEWS, sortMode, Modifier.weight(1f)) { sortName = it.name }
        }

        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Text("태그 게시물을 불러오는 중…")
            }
        }
        if (message != null) {
            TextButton(onClick = vm::clearTagMessage) { Text(message!!) }
        }
        if (!loading && message == null) {
            Text("게시물 ${displayed.size}건", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(displayed, key = { it.id }) { post ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = openingPostId == null) { onPost(post, postIds) }
                        .semantics { contentDescription = "태그 검색 결과 게시물: ${post.title}" },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(post.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            "댓글 ${post.commentCount} · 조회 ${post.viewCount}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (openingPostId == post.id) {
                            Text("게시물을 여는 중…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagSortButton(
    label: String,
    mode: TagSortMode,
    selected: TagSortMode,
    modifier: Modifier,
    onSelect: (TagSortMode) -> Unit,
) {
    if (mode == selected) {
        Button(onClick = { onSelect(mode) }, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = { onSelect(mode) }, modifier = modifier) { Text(label) }
    }
}