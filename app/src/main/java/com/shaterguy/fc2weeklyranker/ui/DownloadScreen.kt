package com.shaterguy.fc2weeklyranker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shaterguy.fc2weeklyranker.data.DownloadListItem
import com.shaterguy.fc2weeklyranker.data.DownloadStatus
import com.shaterguy.fc2weeklyranker.download.VideoDownloadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DownloadScreen(
    onPost: (String) -> Unit,
    vm: DownloadViewModel = viewModel(),
) {
    val active by vm.activeDownloads.collectAsState()
    val history by vm.completedDownloads.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        item {
            Text("다운로드", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("다운로드 중", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(active, key = { it.videoId }) { item ->
            ActiveDownloadCard(item, vm)
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("다운로드 내역", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(history, key = { it.videoId }) { item ->
            CompletedDownloadCard(item, vm, onPost)
        }
    }
}

@Composable
private fun ActiveDownloadCard(item: DownloadListItem, vm: DownloadViewModel) {
    val fileName = rememberDisplayFileName(item)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fileName, fontWeight = FontWeight.SemiBold)
            Text(activeStatus(item), style = MaterialTheme.typography.bodySmall)
            when (item.status) {
                DownloadStatus.RUNNING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.pause(item.videoId) }) { Text("일시정지") }
                    OutlinedButton(onClick = { vm.cancel(item.videoId) }) { Text("취소") }
                }
                DownloadStatus.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.resume(item.videoId) }) { Text("재개") }
                    OutlinedButton(onClick = { vm.cancel(item.videoId) }) { Text("취소") }
                }
                DownloadStatus.QUEUED -> OutlinedButton(onClick = { vm.cancel(item.videoId) }) { Text("취소") }
            }
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    item: DownloadListItem,
    vm: DownloadViewModel,
    onPost: (String) -> Unit,
) {
    val context = LocalContext.current
    val fileName = rememberDisplayFileName(item)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fileName, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openDownloadedVideo(context, item) },
                    modifier = Modifier.weight(1f),
                ) { Text("열기") }
                OutlinedButton(
                    onClick = { onPost(item.postId) },
                    modifier = Modifier.weight(1f),
                ) { Text("게시물 열기") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { openExternalPost(context, item.postUrl) },
                    modifier = Modifier.weight(1f),
                ) { Text("원본 게시물 열기") }
                TextButton(
                    onClick = { vm.deleteHistory(item.videoId) },
                    modifier = Modifier.weight(1f),
                ) { Text("이력 삭제") }
            }
        }
    }
}

@Composable
private fun rememberDisplayFileName(item: DownloadListItem): String {
    val context = LocalContext.current
    val fallback = remember(item.videoUrl, item.postId, item.videoOrdinal) {
        VideoDownloadWorker.outputFileName(item.postId, item.videoOrdinal, item.videoUrl)
    }
    val name by produceState(fallback, item.contentUri, fallback) {
        value = item.contentUri?.let { uri ->
            withContext(Dispatchers.IO) { mediaStoreDisplayName(context, uri) }
        } ?: fallback
    }
    return name
}

private fun mediaStoreDisplayName(context: Context, contentUri: String): String? = runCatching {
    val uri = Uri.parse(contentUri)
    if (uri.scheme != "content") return@runCatching null
    context.contentResolver.query(
        uri,
        arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        cursor.getString(0)?.takeIf(String::isNotBlank)
    }
}.getOrNull()

private fun activeStatus(item: DownloadListItem): String = when (item.status) {
    DownloadStatus.QUEUED -> "대기 중"
    DownloadStatus.PAUSED -> "일시정지 · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    DownloadStatus.FINALIZING -> "저장 중"
    else -> {
        val total = item.totalBytes
        if (total != null && total > 0L) {
            val percent = ((item.downloadedBytes * 100L) / total).coerceIn(0L, 100L)
            "다운로드 중 $percent% · ${formatBytes(item.downloadedBytes)} / ${formatBytes(total)}"
        } else {
            "다운로드 중 · ${formatBytes(item.downloadedBytes)}"
        }
    }
}

private fun openDownloadedVideo(context: Context, item: DownloadListItem) {
    val contentUri = item.contentUri ?: return
    val uri = runCatching { Uri.parse(contentUri) }.getOrNull()?.takeIf { it.scheme == "content" } ?: return
    val mimeType = context.contentResolver.getType(uri) ?: VideoDownloadWorker.mediaMimeType(item.videoUrl)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (intent.resolveActivity(context.packageManager) != null) runCatching { context.startActivity(intent) }
}

private fun openExternalPost(context: Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull()?.takeIf { it.scheme == "https" } ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (intent.resolveActivity(context.packageManager) != null) runCatching { context.startActivity(intent) }
}

private fun formatBytes(value: Long?): String {
    if (value == null || value < 0L) return "?"
    return when {
        value >= 1_073_741_824L -> "%.1f GB".format(value / 1_073_741_824.0)
        value >= 1_048_576L -> "%.1f MB".format(value / 1_048_576.0)
        value >= 1_024L -> "%.1f KB".format(value / 1_024.0)
        else -> "$value B"
    }
}
