# YesPlayMusic Mobile v0.2.14

这是相对于 **v0.2.13** 的小版本修复，解决真机实测中发现的两类跨源选歌漏匹问题，并补齐 Material 3 界面实机截图与文档说明。

## 跨源歌曲匹配修复

- **同一母带时长漂移容差**：`QQMatch.select` 在 5 秒精准档之后、无时长后备档之前新增「同母带漂移档」——当双端时长均满 60 秒、且差值不超过 `min(15 秒, 原曲时长的 10%)` 时接受候选（同档内仍优先署名重合）；30 秒试听片段和异曲仍被拦截。修复如陈奕迅《粤语残片》（2019 重制版，网易云 280.3 秒 vs QQ 同专辑母带 272 秒）因尾音裁剪差 8.3 秒被误拦的问题。
- **括号内 `|` 分段副标题处理**：`QQMatch.mainTitle` 改为按 `|` 逐段处理括号标注，逐段忽略良性标注（`Explicit`、`Clean`、`Album Version` 等）、非「版」结尾的中文译名，以及重复主标题末尾的严格更短后缀，同时保留有效版本或副标题分段。修复如 Russ《Psycho, Pt. 2》因 QQ 返回标题 `Psycho,Pt. 2 (Pt. 2|Explicit)` 导致主标题判定不一致而漏匹的问题。

## 文档与截图更新

- 更新 `README.md`，新增 iQOO 实机截取的 5 张 **Material 3 风格（0.2.14）**页面截图（听听、我的、界面风格设置、播放器封面、全屏双语歌词），并保留原有经典风格截图对照。
- 同步更新 `AGENTS.md` 中的选歌规则与回归用例说明。

## 自动检查与真机实测

- 自动测试通过：**JavaScript 36 项、Java 367 项检查**（`run-tests.py`，新增 16 项时长漂移边界、括号 `|` 分段与端到端夹具断言）。
- 真机复测（iQOO 16，同源码 debug 包，清空缓存 MID 后从网易云原曲起播）：
  - 《粤语残片》`1411495700`（280.3s）→ QQ `000J9BOC3sAUqd`（同专辑 2019 重制版，272s，320k，进度正常推进）；
  - 《Psycho, Pt. 2》`440208643`（162.0s）→ QQ `000WdUWo1p4MAt`（`Psycho,Pt. 2 (Pt. 2|Explicit)`，161s，320k，进度正常推进）；
  - 既有 8 首回归曲目（`FML`、`Famous`、`CARNIVAL`、`Like Him`、`First Love`、`Lemon`、`最后派对`、旧版《粤语残片》）与只读反向匹配结果均保持通过。
- 详见[v0.2.14 验证记录](https://github.com/M1gr4ine/YesPlayMusic-Mobile/blob/v0.2.14/docs/VERIFICATION-v0.2.14.md)。

## 下载与校验

- Android 版本：**0.2.14（versionCode 16）**，沿用与 `v0.2.9`–`v0.2.13` 相同的发布签名，支持直接覆盖安装。
- APK：**YesPlayMusic-Mobile-v0.2.14.apk**
- SHA-256：`1178ddc39bedecb573d5896e09d2a5763d82985dd742ef7cd61dea3a0bc83a3d`
- 校验文件：**SHA256SUMS.txt**

本项目是基于 [qier222/YesPlayMusic](https://github.com/qier222/YesPlayMusic) 的非官方 Android 客户端，采用 MIT 许可证。项目归属与媒体内容说明见仓库 `LICENSE`、`NOTICE.md`。

[查看 v0.2.13 → v0.2.14 的全部代码变化](https://github.com/M1gr4ine/YesPlayMusic-Mobile/compare/v0.2.13...v0.2.14)
