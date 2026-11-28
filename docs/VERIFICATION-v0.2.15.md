# v0.2.15 发布验证

日期：2026-11-28（Asia/Shanghai）。

- 功能源码提交：`4945dc7114c955a997598a5f20bfa3d3f6e45eff`；发布提交仅更新版本号、`README.md`、发布/验证说明、校验文件与发布安装包。
- Android 版本为 `0.2.15`，`versionCode` 为 `17`；包名 `dev.yesplaymusic.probe`，非 debuggable。
- 发布包 `releases/YesPlayMusic-Mobile-v0.2.15.apk` 为 333,151 字节，包内全部页面资源与 `classes.dex`（共 10 项）与完成真机实测的同源码 debug 包逐字节一致；v2/v3 签名校验通过，证书 SHA-256 为 `50afa37c0a03640052904db80b28594ab70c677b510367d131513c1eb1aa2759`，与 `v0.2.9`–`v0.2.14` 公开版本一致，支持直接覆盖安装。
- 全量自动检查通过：Node.js **36 项通过**，JVM **387 项检查通过**（`run-tests.py`）；构建仅保留原有 Java 8 / deprecation 编译警告。
- 真机跨源匹配实测在同源码构建的 debug 包（`329775213c18a398951b5383bf9ee8de4d46f1acfcc6606e6f54cd3461072d3b`，同签名 `adb install -r` 覆盖安装并回读设备 `base.apk` 核对 SHA-256 一致）上完成：
  - 新增修复曲目（清缓存 MID，`auto` 与 `qq` 双模式）：
    - 《K歌之王》`67467`（陈奕迅，《打得火热》）→ 命中 QQ `003hjhJP0C1Gxh`（`K歌之王 (粤语)`，《打得火热》，222.4s，未选钢琴版与 `陈君豪Remix`）。
    - 《A Rusty Dream (from Cyberpunk: Edgerunners 2)》`3422762511`（DOUDOU）→ 命中 QQ `002lAtXr0JYgEn`（`A Rusty Dream`，301.5s）。
  - 完整 10 首回归集（《粤语残片》重制版与旧版、《Psycho, Pt. 2》、《FML》、《Famous》、《CARNIVAL》、《Like Him》、《First Love》、《Lemon》、《最后派对》）均正常起播且进度推进，未见 `AndroidRuntime` 崩溃或 `YPMMobileUI` 报错。
  - 只读反向匹配（`matchTrack`）：`Famous`、`Lemon`、`First Love` 命中；`晴天` 及无关歌手负例返回 `matched: false`。
- 未覆盖项：本次未将正式签名 release 包覆盖安装到当前使用 debug 签名的测试机（避免清空设备登录态），也未在本次发布中重测真实账号收藏/歌单写入、后台切桌面、锁屏控制、横屏待机与软键盘交互。

SHA-256：

```text
4a227b797708e7e311fe9fb17d995face6e67b6533a6b63c9d0465040bda469e  YesPlayMusic-Mobile-v0.2.15.apk
```

本地备份与验证记录留在忽略目录 `.local/matching-refactor-20261128/`；公开安装包与校验文件见 GitHub Release 及 `releases/` 目录。
