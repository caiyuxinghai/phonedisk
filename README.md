# 随身下载盘

把手机当成联网硬盘：粘贴 http(s) 文件直链，下到手机存储，再用 USB 或同一 Wi-Fi 拷到电脑。

## 下载安装

1. 打开 [Releases](https://github.com/caiyuxinghai/phonedisk/releases) 下载 `PhoneDisk.apk`
2. 手机允许「安装未知来源应用」后安装
3. 首次打开：允许通知；Android 11+ 再给「所有文件访问权限」，文件才会进 `下载/PhoneDisk`，USB 连电脑才能直接看到

这是 debug 签名包，仅供自己侧载，未上架应用商店。

## 能做什么

- 下载浏览器能直接点下去的文件（`.exe` / `.iso` / `.zip` / `.7z` 等）
- 后台下载、暂停、断点续传（服务器支持 `Range` 时）
- 仅 Wi-Fi 下载，避免流量爆炸
- USB：电脑里打开手机存储 → `Download` → `PhoneDisk`
- Wi-Fi：App 里开启局域网服务，电脑浏览器打开显示的地址取文件

## 做不到什么

Steam / Epic / 战网游戏库**不能**靠粘贴商店页下载。那些不是文件直链，必须用电脑上的官方客户端。

也不支持磁力 / BT。

## 自己编译

需要 JDK 17 和 Android SDK（compileSdk 35）。

```bat
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
gradlew.bat assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`
