# Ginkgo · by Locusable Studio

面向小米多看电纸书一代（Android 8.1）的极简 Android 启动器。它打开和固定应用，也可以浏览一个自选目录。文件由系统为该格式选择的应用打开；Ginkgo 不做电子书阅读或管理。

- **首页**：最多固定八个应用。列表使用黑白图标；图标网格每行四格，使用应用的原始图标。固定和取消固定不会在首页留下操作记录。
- **应用**：按屏幕可用高度分页。轻点打开；长按后在弹框里确认固定或取消固定。文件管理和微信读书有专用图标，工厂模式不显示。
- **自定义目录**：在设置中点「自定义目录」右箭头选择一个目录。勾选后在底部导航显示所选目录名；可以重选，取消选择会保留原目录。支持子目录、按屏幕高度分页的列表和每行四格的书架。翻页栏固定在目录页底部，无法翻页的按钮置灰。标题旁可切换按文件名、按上次打开时间或按类型排序，三种方式都将目录放在文件之前。按类型时，文件按扩展名分组，同组按文件名排列；打开时间只记录经 Ginkgo 打开的项目，不显示历史列表。书架用同一比例裁切 EPUB 内嵌封面或 PDF 首页；无封面时显示名称的首字母或首字，目录用排序后的前四个文件拼成封面。缩略图在后台生成：普通封面有内存和最多 24 MiB 的应用私有磁盘缓存，已拼好的目录封面在内存中复用。
- **关于**：展示应用图标、简介、实际安装的版本号与构建号，以及开发者。默认显示，可以在设置中关闭。
- **设置**：可分别控制「应用」、自选目录和「关于」是否显示，以及首页和目录的展示方式，两者默认都用列表。「首页」和「设置」始终显示，勾选框置灰。小米固件同时提供「设备设置」和「Android 系统设置」时，Ginkgo 只显示「设备设置」，不改动系统设置本身。

## 构建和安装

需要 JDK 17、Android SDK Platform 37.0、Build Tools（含 `apksigner`）和 Platform Tools（含 `adb`）。设置好 `JAVA_HOME` 和 `ANDROID_HOME`，使用 Gradle Wrapper 构建 release，再用项目外的发布密钥签名。正式升级应复用同一密钥，勿将密钥或口令提交到仓库。

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

本项目的发布密钥库保存在 `~/Documents/Ginkgo签名/ginkgo-release.p12`，不在源码目录。构建成功后，设置 `KEYSTORE` 为发布密钥库的路径、`READER_SERIAL` 为 `adb devices -l` 中 EPD106 的序列号。把 `36.0.0` 和 `ginkgo` 分别换成实际安装的 Build Tools 版本和密钥别名。下面的 `apksigner` 命令会交互式读取口令；也可以在 Mac 终端用 `sh tools/sign-release.sh` 签名。

```sh
APKSIGNER="$ANDROID_HOME/build-tools/36.0.0/apksigner"
APK_DIR=app/build/outputs/apk/release
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias ginkgo \
  --out "$APK_DIR/app-release.apk" "$APK_DIR/app-release-unsigned.apk" &&
"$APKSIGNER" verify --print-certs "$APK_DIR/app-release.apk" &&
adb -s "$READER_SERIAL" install -r "$APK_DIR/app-release.apk"
```

包名为 `com.locusable.ginkgo`，最低支持 Android 8.1（API 27）。与旧包 `com.locusable.inkpaperlauncher` 是不同应用，旧包数据不会自动迁移。同包名的 debug 版因签名不同，不能被 release 覆盖；如果要卸载 debug 版，卸载会清除它的固定项、目录绑定和默认桌面选择。不要禁用原厂桌面。实测的小米多看一代上，从 Ginkgo 打开原厂多看后，可用多看界面左上角的返回按钮回来。

有外部存储文档提供程序的固件使用 Android 系统选择器，只保留当前所选目录的读取授权。小米多看一代的 Android 8.1 固件未提供此组件，系统选择器只列出「下载」；Ginkgo 在这类 Android 8.1/9 设备上改用内置目录选择器，首次使用时会请求读取共享存储的权限，不请求写入权限。应用不依赖厂商的墨水屏刷新接口。
