package com.phonedisk.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.phonedisk.app.util.Format
import com.phonedisk.app.util.Storage
import java.io.File
import kotlinx.coroutines.delay

@Composable
fun SpaceBanner(folder: File) {
    var free by remember { mutableLongStateOf(Storage.availableBytes(folder)) }
    LaunchedEffect(folder.absolutePath) {
        while (true) {
            free = Storage.availableBytes(folder)
            delay(2000)
        }
    }
    val critical = free in 0 until Storage.CRITICAL_BYTES
    val low = free in 0 until Storage.LOW_BYTES
    val colors = when {
        critical -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        low -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> CardDefaults.cardColors()
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp)) {
            Text("手机剩余空间：${if (free < 0) "未知" else Format.bytes(free)}", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    critical -> "空间快满。再下大文件会被拦住，请先把 Download/PhoneDisk 里的文件拷到电脑或删掉。"
                    low -> "剩余不到 2 GB。镜像和安装包很大，添加下载后会按文件大小检查，不够会停住。"
                    else -> "大文件开始前会检查体积。空间不够会提示还差多少，不会把手机写满。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
