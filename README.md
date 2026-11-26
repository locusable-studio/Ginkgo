# Ginkgo · by Locusable Studio

面向小米多看电纸书一代（Android 8.1）的极简 Android 启动器。它打开和固定应用，也可以浏览本地和微信读书书架，并交给各自的原应用续读；Ginkgo 不做电子书阅读或管理。

- **首页**：最多固定 4 个常用应用，并显示最多 4 本最近打开的书。应用和书籍都跟随首页展示方式：列表模式一行一项，图标模式一行四格，分别显示真实应用图标和书籍封面。固定和取消固定不留下操作记录。
- **应用**：按屏幕可用高度分页。轻点打开；长按后在弹框里确认固定或取消固定。文件管理和微信读书有专用图标，工厂模式不显示。
- **书架**：内部存储 `Books` 中的书籍和目录，与微信读书账号的书籍和文件夹直接混排，共用「按名称」和「按最近打开」排序，不按来源或目录优先分组。保留两边原有目录层级与文件夹成员关系，目录标题旁的小箭头返回上一级。列表名称占一行，按可用宽度加省略号；封面模式每行四格，标题最多两行，微信读书封面内带有来源小标。目录用排序后的前四本书拼成封面。无封面时用小字显示书名前 15 个字，超出加省略号。翻页栏固定在底部。
- **续读与缓存**：点击书籍交给各自的原应用。本地只把书籍链接交给多看，未被多看索引的书籍不能通过链接打开；支持 EPUB 内嵌封面和 PDF 首页。本地封面和微信读书封面分别使用应用私有缓存，微信读书只缓存成功取得的封面。微信读书断网、刷新失败或需要重新登录时仍可浏览已保存的书籍与封面。每次回到书架时在后台尝试刷新；首次同步需要联网。不读取统计、不上传阅读进度、不修改云端书架。
- **关于**：以书末版权页的方式排版应用图标、名称、简介、制作署名和实际安装的版本号与构建号。默认显示，可以在设置中关闭。
- **设置**：可控制「应用」、「书架」和「关于」是否显示，并分别设置首页和书架的展示方式。「首页」和「设置」始终显示。微信读书扫码授权和账号管理位于「账号」区，点击扫码登录会打开二维码弹窗。小米固件同时提供「设备设置」和「Android 系统设置」时，Ginkgo 只显示「设备设置」，不改动系统设置本身。

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

包名为 `com.locusable.ginkgo`，最低支持 Android 8.1（API 27）。与旧包 `com.locusable.inkpaperlauncher` 是不同应用，旧包数据不会自动迁移。同包名的 debug 版因签名不同，不能被 release 覆盖；如果要卸载 debug 版，卸载会清除它的固定项、显示设置和默认桌面选择。不要禁用原厂桌面。实测的小米多看一代上，从 Ginkgo 打开原厂多看后，可用多看界面左上角的返回按钮回来。

本地书架面向小米多看一代的原厂固件，已在多看 1.2.7 上验证 EPUB 和 PDF 续读。Android 8.1/9 上首次打开书架时如未授权，需允许读取共享存储；Ginkgo 不请求写入权限。打开书籍时只将内容计算出的书籍 ID 放进 `duokan-reader://read/book/…` 链接，不共享路径、文件或读取授权，也不读取多看的私有数据。其他 Android 版本仍可使用桌面功能。应用不依赖厂商的墨水屏刷新接口。

微信读书书架使用腾讯网页端扫码登录和只读书架接口，不要求 API Key。扫码授权仅用于 Ginkgo，不替墨水屏版登录，也不会向它转交授权；读书时以墨水屏版自身的账号和权限为准。Ginkgo 将登录状态和书架快照保存在仅应用可访问、不参与备份的私有目录，不依赖 Keystore。退出 Ginkgo 的微信读书登录会清除本机书架缓存，不影响云端或原应用。网页端接口不是公开的第三方登录协议，服务端变更可能影响登录或同步。
