package com.phonedisk.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.phonedisk.app.util.AppLog

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("使用方法与注意事项") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("使用方法", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. 允许通知。Android 11+ 在「任务」页打开「所有文件访问」，否则 USB 连电脑可能看不到文件。\n" +
                        "2. 下大文件前，到「传到电脑」允许忽略电池优化。\n" +
                        "3. 点右下角 + 粘贴链接，可一次多条。浏览器里也可「分享到 随身下载盘」。建议勾选仅 Wi-Fi。\n" +
                        "4. 除直链外，还可试：Google Drive / 文档、Dropbox、OneDrive、GitHub 文件、Hugging Face、MediaFire 公开分享。\n" +
                        "5. USB：手机选传输文件，电脑打开 Download/PhoneDisk 拷走。\n" +
                        "6. Wi-Fi：手机和电脑连同一 Wi-Fi，打开「传到电脑」里的局域网服务。电脑扫二维码或打开地址取文件。用完关掉。\n" +
                        "7. 连手机热点时会先问你，避免把流量用爆。可在「传到电脑」里限速。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("注意事项", style = MaterialTheme.typography.titleMedium)
                Text(
                    "• 不能下 Steam / Epic / 战网游戏库，商店页不是文件直链。\n" +
                        "• 不支持磁力、BT。百度网盘等要登录的分享页也不行；网盘链接必须是公开可下的。\n" +
                        "• 任务页会显示剩余空间。文件太大时会提示还差多少并停住；下到一半写满会暂停，清出空间后可继续。\n" +
                        "• 不是所有网站都支持断点续传。\n" +
                        "• 局域网取文件没有密码，只在家里用，用完即关。\n" +
                        "• 请只从本应用对应的 GitHub Releases 安装，不要用别人转发的来路不明 APK。\n" +
                        "• 若没给全部文件权限，卸载应用可能把已下文件一起删掉，先拷到电脑。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("调试信息", style = MaterialTheme.typography.titleMedium)
                Text(
                    AppLog.snapshot(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("phonedisk-debug", AppLog.snapshot()))
            }) { Text("复制调试信息") }
        },
    )
}
