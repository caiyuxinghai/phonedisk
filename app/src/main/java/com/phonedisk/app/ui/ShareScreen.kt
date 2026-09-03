package com.phonedisk.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonedisk.app.share.qrImage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("传到电脑") },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "使用说明")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "下完后有两种方式拷到电脑：用数据线，或让电脑和手机连同一 Wi‑Fi，用浏览器打开下面的地址。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("USB 拷贝", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (vm.usingPublicFolder()) {
                            "插上数据线后，在电脑里打开手机存储 → Download → PhoneDisk。"
                        } else {
                            "当前没有「所有文件访问」权限，文件在应用私有目录，USB 可能看不到。请到「任务」页授权，或用下面的 Wi‑Fi 取文件。"
                        },
                    )
                    Text(
                        vm.saveFolder().absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wi‑Fi 取文件", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("开启局域网服务")
                        Switch(checked = vm.shareOn, onCheckedChange = { vm.toggleShare(it) })
                    }
                    vm.shareUrl?.let { url ->
                        Text(url, style = MaterialTheme.typography.titleMedium)
                        val qr = remember(url) { qrImage(url) }
                        qr?.let {
                            Image(
                                bitmap = it,
                                contentDescription = "扫码取文件",
                                modifier = Modifier
                                    .size(200.dp)
                                    .padding(top = 8.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Text("电脑浏览器扫这个码即可打开文件列表", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("url", url))
                        }) { Text("复制地址") }
                    }
                    vm.shareError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    if (vm.shareOn) {
                        Text(
                            "电脑浏览器打开这个地址，点文件即可下载。只用在家里的 Wi‑Fi，不要开给公共网络。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("下载限速", style = MaterialTheme.typography.titleMedium)
                    Text("避免把宿舍网或热点占满。改完对正在下的任务也会逐渐生效。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "不限速", 512 to "512 KB/s", 1024 to "1 MB/s", 2048 to "2 MB/s", 5120 to "5 MB/s").forEach { (kbps, label) ->
                            FilterChip(
                                selected = vm.speedLimitKBps == kbps,
                                onClick = { vm.setSpeedLimit(kbps) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("长时间下载", style = MaterialTheme.typography.titleMedium)
                    Text("大文件建议关掉电池优化，避免系统把下载杀掉。")
                    OutlinedButton(onClick = { requestIgnoreBattery(context) }) {
                        Text("允许忽略电池优化")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showHelp) {
        HelpDialog(onDismiss = { showHelp = false })
    }
}

private fun requestIgnoreBattery(context: Context) {
    if (Build.VERSION.SDK_INT < 23) return
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
