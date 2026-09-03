package com.phonedisk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phonedisk.app.data.TaskStatus

@Composable
fun MainScreen(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tasks by vm.tasks.collectAsState()
    val hotspotWait = tasks.any { TaskStatus.waitingHotspot(it) }
    if (hotspotWait) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在使用手机热点") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前连接的可能是手机热点，继续下载会消耗大量流量。是否继续？")
                    TextButton(onClick = { vm.confirmHotspot(always = true) }) {
                        Text("以后热点都允许")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmHotspot() }) { Text("继续下载") }
            },
            dismissButton = {
                TextButton(onClick = { vm.denyHotspot() }) { Text("暂停") }
            },
        )
    }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                    label = { Text("任务") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    label = { Text("文件") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Outlined.WifiTethering, contentDescription = null) },
                    label = { Text("传到电脑") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            0 -> TasksScreen(vm, modifier)
            1 -> FilesScreen(vm, modifier)
            else -> ShareScreen(vm, modifier)
        }
    }
}
