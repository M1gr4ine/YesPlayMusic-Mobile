# YesPlayMusic Mobile v0.2.10

本版修复音源匹配、收藏和播放错误提示，并包含歌曲操作与播放器状态同步的改进。

- 修复藤井風／藤井风等名称差异导致的 QQ 音源匹配失败；支持曲库返回的歌手原名、别名和译名，覆盖鱼韵／サカナクション／Sakanaction 的别名匹配。
- 播放器音源标记跟随实际播放来源；匹配中或不可用时显示对应状态。
- 收藏统一到网易云音乐“我喜欢”。QQ 曲库歌曲先匹配到网易云歌曲，找不到对应歌曲时会提示，避免收藏到错误的曲目。
- 修复设置中点击音源选项时的圆角外白边。
- 无法播放的顶部提示约 4 秒后自动折叠；修复提示框被页面布局压缩、文字贴近底边的问题。
- 改进歌曲操作菜单、网易云歌单添加、缓存清理，以及原生播放状态和队列同步。

安装包版本为 0.2.10（versionCode 12），支持覆盖安装并保留现有数据。本次发布的 APK 已安装到 iQOO 16，设备回读文件与发布文件的 SHA-256 相同。

验证：31 项 JavaScript 测试和 11 组 Java 回归通过；确认藤井風《帰ろう》实际使用 QQ 音源播放，进度正常、无缓冲或错误。完整记录见 [验证文档](https://github.com/M1gr4ine/YesPlayMusic-Mobile/blob/v0.2.10/docs/VERIFICATION-v0.2.10.md)。

下载 `YesPlayMusic-Mobile-v0.2.10.apk`，使用随附的 `SHA256SUMS.txt` 校验。

基于 [qier222/YesPlayMusic](https://github.com/qier222/YesPlayMusic) 的非官方 Android 客户端，采用 MIT 许可证。项目归属与媒体内容说明见仓库 LICENSE、NOTICE.md。
