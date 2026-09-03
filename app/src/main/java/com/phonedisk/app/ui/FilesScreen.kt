package com.phonedisk.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonedisk.app.data.TaskStatus
import com.phonedisk.app.util.Format
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val files = tasks.filter { it.status == TaskStatus.COMPLETED }
    val context = LocalContext.current
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    var showHelp by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("文件") },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "使用说明")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SpaceBanner(vm.saveFolder()) }
            item {
                Text(
                    "保存位置：${vm.saveFolder().absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (files.isEmpty()) {
                item {
                    Text("下完的文件会出现在这里，也可以用 USB 从电脑里拷走。")
                }
            }
            items(files, key = { it.id }) { task ->
                val exists = File(task.filePath).isFile
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.fileName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            buildString {
                                append(Format.bytes(task.totalBytes.takeIf { it > 0 } ?: task.downloadedBytes))
                                task.completedAt?.let { append(" · "); append(fmt.format(Date(it))) }
                                if (!exists) append(" · 文件已不在手机上")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (exists) {
                                OutlinedButton(onClick = {
                                    vm.shareFile(task)?.let { context.startActivity(Intent.createChooser(it, "发送文件")) }
                                }) { Text("分享") }
                            }
                            OutlinedButton(onClick = { vm.deleteTask(task) }) { Text("删除") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}
