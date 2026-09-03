package com.phonedisk.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonedisk.app.data.DownloadTaskEntity
import com.phonedisk.app.data.TaskStatus
import com.phonedisk.app.util.Format
import com.phonedisk.app.util.Storage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    LaunchedEffect(vm.incomingDraft) {
        if (!vm.incomingDraft.isNullOrBlank()) showAdd = true
    }
    val active = tasks.filter { it.status != TaskStatus.COMPLETED }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("任务") },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.Outlined.HelpOutline, contentDescription = "使用说明")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "添加下载")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "把链接贴进来，或从浏览器点「分享到 随身下载盘」。支持直链、Google Drive / Dropbox / OneDrive、GitHub、Hugging Face。不支持 Steam 游戏库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SpaceBanner(vm.saveFolder()) }
            if (Build.VERSION.SDK_INT >= 30 && !Storage.canWritePublic()) {
                item {
                    PermissionCard(context)
                }
            }
            if (active.isEmpty()) {
                item {
                    Text(
                        "还没有任务。点右下角加号，粘贴下载链接。",
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(active, key = { it.id }) { task ->
                TaskCard(task, vm)
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }

    if (showAdd) {
        AddDialog(
            context = context,
            folder = vm.saveFolder(),
            initialUrl = vm.incomingDraft,
            onDismiss = {
                showAdd = false
                vm.clearIncoming()
            },
            onAdd = { url, name, wifi ->
                val err = vm.addTask(url, name, wifi)
                if (err == null) {
                    showAdd = false
                    vm.clearIncoming()
                }
                err
            },
        )
    }
}

@Composable
private fun PermissionCard(context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("需要「所有文件访问」权限", style = MaterialTheme.typography.titleMedium)
            Text(
                "这样文件会下到手机「下载/PhoneDisk」文件夹，USB 连电脑就能直接拷走。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }) {
                Text("去授权")
            }
        }
    }
}

@Composable
private fun TaskCard(task: DownloadTaskEntity, vm: AppViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(task.fileName, style = MaterialTheme.typography.titleMedium)
            Text(statusLabel(task), style = MaterialTheme.typography.bodySmall)
            if (task.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { Format.percent(task.downloadedBytes, task.totalBytes) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (task.status == TaskStatus.RUNNING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                "${Format.bytes(task.downloadedBytes)} / ${Format.bytes(task.totalBytes)}" +
                    if (task.status == TaskStatus.RUNNING) " · ${Format.speed(task.speedBps)}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            task.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (task.status) {
                    TaskStatus.RUNNING, TaskStatus.QUEUED -> {
                        OutlinedButton(onClick = { vm.pause(task.id) }) { Text("暂停") }
                        OutlinedButton(onClick = { vm.cancel(task.id) }) { Text("取消") }
                    }
                    TaskStatus.PAUSED, TaskStatus.FAILED, TaskStatus.CANCELED -> {
                        Button(onClick = { vm.retry(task.id) }) { Text("继续") }
                        OutlinedButton(onClick = { vm.deleteTask(task) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

private fun statusLabel(task: DownloadTaskEntity): String = when (task.status) {
    TaskStatus.QUEUED -> "排队中"
    TaskStatus.RUNNING -> "下载中"
    TaskStatus.PAUSED -> "已暂停"
    TaskStatus.FAILED -> "失败"
    TaskStatus.CANCELED -> "已取消"
    else -> task.status
}

@Composable
private fun AddDialog(
    context: Context,
    folder: java.io.File,
    initialUrl: String?,
    onDismiss: () -> Unit,
    onAdd: (String, String?, Boolean) -> String?,
) {
    var url by remember { mutableStateOf(initialUrl?.trim()?.ifBlank { null } ?: clipboardText(context)) }
    var name by remember { mutableStateOf("") }
    var wifiOnly by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加下载") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("链接（可多条，一行一个）") },
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件名（可选）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
                    Text("仅 Wi‑Fi 下载")
                }
                val free = Storage.availableBytes(folder)
                Text(
                    "当前剩余 ${if (free < 0) "未知" else Format.bytes(free)}。大文件开始下之前会再核对一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { error = onAdd(url, name.ifBlank { null }, wifiOnly) }) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun clipboardText(context: Context): String {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    val text = clip.getItemAt(0).coerceToText(context).toString().trim()
    return if (text.contains("http://") || text.contains("https://")) text else ""
}
