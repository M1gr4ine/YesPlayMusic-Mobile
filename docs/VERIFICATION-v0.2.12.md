# v0.2.12 发布验证

日期：2026-10-18（Asia/Shanghai）。

- 功能源码提交：`d235d889b9341d022b0207da5e9558ec2e796794`；发布准备仅更新版本、说明与安装包。
- Android 版本为 0.2.12，versionCode 14；包名 `dev.yesplaymusic.probe`，非 debuggable。
- 发布包 `releases/YesPlayMusic-Mobile-v0.2.12.apk` 为 283933 字节，全部 8 项页面资源及 DEX 与已完成真机回归的功能包逐字节一致。
- APK 的 v2/v3 签名验证通过；证书 SHA-256 为 `50afa37c0a03640052904db80b28594ab70c677b510367d131513c1eb1aa2759`，与覆盖安装前一致。
- 发布包已先行覆盖安装到连接的 iQOO 16（V2606A）；安装前保存旧 APK，安装后回读设备 `base.apk`，SHA-256 与发布文件一致，读取到的 versionName/versionCode 分别为 0.2.12/14。
- 新版本启动后登录状态保留，原 105 首队列可继续播放；MediaSession 从 PLAYING 转为 PAUSED，实际进度由 120878 ms 推进至 146120 ms。当前进程未见 AndroidRuntime 崩溃或 YPMMobileUI 警告。
- 发布检查通过：JavaScript 21 项、Java 117 项。构建保留原有 Java 8/deprecation 编译警告。
- 歌单编辑、服务器结果回读、键盘、横屏、方向锁定、进程重启和后台播放的详细范围见[功能验证记录](VERIFICATION-landscape-playlist-editor.md)。本次发布安装没有重复执行其中所有远端写入场景。
- 0% 电量模拟曾触发系统关机，未计为通过；用户恢复开机后已确认硬件电量更新正常、旋转设置复原。此次发布安装未再次进行电量模拟。

SHA-256：

```text
abac691111310db6503bd7b7c70bc834574f100e71d7372884f0944259a8954e  YesPlayMusic-Mobile-v0.2.12.apk
```

发布前本地证据位于忽略目录 `.local/release-v0.2.12/`；GitHub 上的最终发布状态及附件以公开 Release 为准。
