# v0.2.13 发布验证

日期：2026-11-28（Asia/Shanghai）。

- 功能源码提交：`4c16741f9588f001b06300202d453faaa331b8dc`；发布提交仅更新版本号、说明文档、校验文件与发布安装包。
- Android 版本为 `0.2.13`，`versionCode` 为 `15`；包名 `dev.yesplaymusic.probe`，非 debuggable。
- 发布包 `releases/YesPlayMusic-Mobile-v0.2.13.apk` 为 333,151 字节，包内全部页面资源与 `classes.dex`（共 10 项）与完成真机实测的同源码 debug 包逐字节一致；v2/v3 签名校验通过，证书 SHA-256 为 `50afa37c0a03640052904db80b28594ab70c677b510367d131513c1eb1aa2759`，与 `v0.2.9`–`v0.2.12` 公开版本一致，支持直接覆盖安装。
- 全量自动检查通过：Node.js **36 项通过**，JVM **351 项检查通过**（`run-tests.py`）；构建仅保留原有 Java 8 / deprecation 编译警告。
- 真机跨源匹配实测在同源码构建的 debug 包（`70f69cf11c53fb4ee8bbf286d93f8d0552206fde0939e8e2032d76dc821b8e97`，安装后回读设备 `base.apk` 校验 SHA-256 一致）上完成：
  - 8 首代表曲目（`FML`、`Famous`、`CARNIVAL`、`Like Him`、`First Love`、`Lemon`、`最后派对`、`粤语残片`）在清空缓存 `qqMid` 后均命中准确录音版本（320k），播放进度实际推进，`MediaSession` 处于活跃状态且歌词加载正常，未见 `AndroidRuntime` 崩溃或 `YPMMobileUI` 报错。
  - 只读反向匹配（`matchTrack`）：`Famous`、`Lemon`、`First Love` 命中对应网易云曲目；网易云搜索结果全为翻唱的 `晴天` 及无关歌手负例返回 `matched: false`。
  - 实测结束后恢复 `sourcePreference=auto` 并暂停播放。
- 未覆盖项：本次未将正式签名 release 包覆盖安装到当前使用 debug 签名的测试机（避免再次清空设备登录态），也未在本次发布中重测后台切桌面、横屏待机/电量模拟、软键盘和真实账号歌单/收藏写入。

SHA-256：

```text
a873d5463d5d0c5286234660c6ddc438cc06a852be65326e711f6ebe7ae64d80  YesPlayMusic-Mobile-v0.2.13.apk
```

本地备份与验证记录留在忽略目录 `.local/matching-refactor-20261128/`；公开安装包与校验文件见 GitHub Release 及 `releases/` 目录。
