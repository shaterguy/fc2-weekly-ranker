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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shaterguy.fc2weeklyranker.network.RemoteSearchPost

@Composable
fun SearchScreen(
    vm: MainViewModel,
    onPost: (RemoteSearchPost, List<String>) -> Unit,
) {
    val results by vm.searchResults.collectAsState()
    val loading by vm.isSearchLoading.collectAsState()
    val cancelling by vm.isSearchCancelling.collectAsState()
    val progress by vm.searchProgress.collectAsState()
    val message by vm.searchMessage.collectAsState()
    val openingPostId by vm.searchOpeningPostId.collectAsState()
    val resultIds = remember(results) { results.map { it.id } }
    var query by rememberSaveable { mutableStateOf("") }
    var hasSearched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(progress?.query) {
        progress?.query?.let { restored ->
            if (query.isBlank() || loading) query = restored
            hasSearched = true
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("FC2 검색", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "사이트 통합검색 전체 결과에서 FC2 게시물만 모아 중복을 제거합니다.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("검색어") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    hasSearched = true
                    vm.searchPosts(query)
                },
                enabled = query.isNotBlank() && !loading && !cancelling,
            ) { Text("검색") }
        }

        if (loading) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(Modifier.padding(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("검색 중: ${progress?.query ?: query}")
                    val current = progress
                    if (current != null && current.totalPages > 0) {
                        Text(
                            "${current.completedPages.coerceAtMost(current.totalPages)} / ${current.totalPages}페이지 확인",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("검색 범위를 확인하는 중…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TextButton(onClick = vm::cancelSearch, enabled = !cancelling) {
                    Text(if (cancelling) "중지 중…" else "중지")
                }
            }
        }
        if (message != null) {
            TextButton(onClick = vm::clearSearchMessage) { Text(message!!) }
        }
        if (!loading && hasSearched && message == null && results.isEmpty()) {
            Text("검색 결과가 없습니다.")
        }
        if (!loading && results.isNotEmpty()) {
            Text("FC2 게시물 ${results.size}건", fontWeight = FontWeight.SemiBold)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.id }) { post ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = openingPostId == null) { onPost(post, resultIds) }
                        .semantics { contentDescription = "검색 결과 게시물: ${post.title}" },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(post.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (openingPostId == post.id) "게시물을 여는 중…" else "앱에서 게시물 보기",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
