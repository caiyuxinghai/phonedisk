package com.phonedisk.app.ui

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
            delay(4000)
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
    val text = when {
        free < 0 -> "剩余空间未知"
        critical -> "剩余 ${Format.bytes(free)}，空间快满，下大文件会被拦住"
        low -> "剩余 ${Format.bytes(free)}，不到 2 GB，下镜像前请确认够用"
        else -> "剩余 ${Format.bytes(free)}"
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}
