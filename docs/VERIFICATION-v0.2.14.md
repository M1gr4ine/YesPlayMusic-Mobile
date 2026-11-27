# v0.2.14 发布验证

日期：2026-11-28（Asia/Shanghai）。

- 功能源码提交：`4b47b71912aa4be10d33bcac852cacb2042277af`；发布提交仅更新版本号、实机截图、说明文档、校验文件与发布安装包。
- Android 版本为 `0.2.14`，`versionCode` 为 `16`；包名 `dev.yesplaymusic.probe`，非 debuggable。
- 发布包 `releases/YesPlayMusic-Mobile-v0.2.14.apk` 为 333,151 字节，包内全部页面资源与 `classes.dex`（共 10 项）与完成真机复测的同源码 debug 包（`dc20dc8d45f8821fbbb0cf1949805f94620ad621f3d030764e082cf8a6b32da2`）逐字节一致；v2/v3 签名校验通过，证书 SHA-256 为 `50afa37c0a03640052904db80b28594ab70c677b510367d131513c1eb1aa2759`，与 `v0.2.9`–`v0.2.13` 公开版本一致，支持直接覆盖安装。
- 全量自动检查通过：Node.js **36 项通过**，JVM **367 项检查通过**（`run-tests.py`）；构建仅保留原有 Java 8 / deprecation 编译警告。
- 真机实测在同源码构建的 debug 包（安装后回读设备 `base.apk` 校验 SHA-256 为 `dc20dc8d45f8821fbbb0cf1949805f94620ad621f3d030764e082cf8a6b32da2` 一致）上完成：
  - 新增修复曲目：清缓存 MID 后从网易云原曲发起播放，《粤语残片》（`1411495700`，280.3s）命中 QQ `000J9BOC3sAUqd`（同专辑 2019 重制版，272s，320k，进度 0 → 1929 ms）；《Psycho, Pt. 2》（`440208643`，162.0s）命中 QQ `000WdUWo1p4MAt`（`Psycho,Pt. 2 (Pt. 2|Explicit)`，161s，320k，进度 0 → 1883 ms）。
  - 既有回归曲目：`FML`、`Famous`、`CARNIVAL`、`Like Him`、`First Love`、`Lemon`、`最后派对`、旧版《粤语残片》均命中与上一轮一致的 QQ 录音，进度推进正常，无 `resolve-failed`、`AndroidRuntime` 或 `YPMMobileUI` 报错。
  - 只读反向匹配（`matchTrack`）：`Famous`、`Lemon`、`First Love` 命中；`晴天`（网易云搜索全为翻唱）及无关歌手负例均返回 `matched: false`。
  - 在同一真机上以 Material 3 风格实拍 5 张界面截图（听听、我的、界面风格设置、播放器封面、全屏双语歌词）收入 `docs/screenshots/m3-*.png` 并更新至 `README.md`。
- 未覆盖项：本次未将正式签名 release 包覆盖安装到当前使用 debug 签名的测试机（避免清空测试机登录态），未在真实账号上执行漂移档反向匹配的收藏写入验证，也未重测后台切桌面、锁屏、横屏待机与软键盘交互。

SHA-256：

```text
1178ddc39bedecb573d5896e09d2a5763d82985dd742ef7cd61dea3a0bc83a3d  YesPlayMusic-Mobile-v0.2.14.apk
```

本地备份与验证记录留在忽略目录 `.local/matching-refactor-20261128/`；公开安装包与校验文件见 GitHub Release 及 `releases/` 目录。
