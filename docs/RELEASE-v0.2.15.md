# YesPlayMusic Mobile v0.2.15

这是相对于 **v0.2.14** 的小版本修复，解决真机实测中发现的三类跨源选歌问题（Android 端紧贴汉字的英文版本标记漏识别、带 `(from …)` 出处说明的标题漏匹，以及单边语种标注与专辑优先级问题）。

## 跨源歌曲匹配修复

- **修复 Android ICU 正则 `\b` 漏识别汉字紧贴的英文版本标记**：`QQMatch.LATIN_TAG` 改用 ASCII 边界 `(?<![a-z0-9])…(?![a-z0-9])`，避免 Android ICU 将汉字当作单词字符而漏识别 `陈君豪Remix`、`周杰伦live`、`DJ阿若mix` 等标记，确保手机与桌面 JVM 行为一致。
- **忽略不含版本标记的出处与合作标注**：`QQMatch.ignorableSegment` 新增 `from`、`feat.`、`ft.`、`featuring`、`with`、`prod.`、`prod by`、`theme from`、`original soundtrack`、`ost` 识别（放在版本标记检查之后，`(from X Live)`、`(feat. X Remix)` 仍会被保留并拦截）。修复如 DOUDOU《A Rusty Dream (from Cyberpunk: Edgerunners 2)》因 QQ 标题仅写 `A Rusty Dream` 而返回 `match_not_found` 的问题。
- **专辑优先排序与专辑门控语种例外**：`QQMatch.Song` 与 `MusicApi` 反向匹配均纳入 `album` 字段；同一时长档内按「专辑一致 > 标题完全一致 > 署名重合 > 平台原始顺序」稳定排序；仅一边带语种标注（如 `K歌之王 (粤语)`）只在专辑一致（`sameAlbum`）时放行。修复如陈奕迅《K歌之王》（《打得火热》）原先拒掉同专辑原版而误播钢琴版或 `陈君豪Remix` 的问题，同时继续拒绝《反正是我》国语版。

## 自动检查与真机实测

- 自动测试通过：**JavaScript 36 项、Java 387 项检查**（`run-tests.py`，新增 20 项回归断言，覆盖汉字紧贴英文标记、出处/合作标注、专辑门控语种例外与同档排序、以及真机录制的 QQ 搜索夹具）。
- 真机复测（iQOO 16，同源码 debug 包，清空缓存 MID，`auto` 与 `qq` 双模式）：
  - 《K歌之王》`67467`（陈奕迅，《打得火热》）→ 精准命中 QQ `003hjhJP0C1Gxh`（`K歌之王 (粤语)`，《打得火热》，222.4s），不再选到钢琴版或 `陈君豪Remix`；
  - 《A Rusty Dream (from Cyberpunk: Edgerunners 2)》`3422762511`（DOUDOU）→ 精准命中 QQ `002lAtXr0JYgEn`（`A Rusty Dream`，301.5s），正常起播；
  - 既有 10 首回归曲目（《粤语残片》重制版与旧版、《Psycho, Pt. 2》、《FML》、《Famous》、《CARNIVAL》、《Like Him》、《First Love》、《Lemon》、《最后派对》）均正常起播且进度推进，其中《Famous》因同专辑优先提升至 `The Life Of Pablo (Explicit)` 原专辑版（`002XelTe31rBEE`）；只读反向匹配结果均符合预期。
- 详见[v0.2.15 验证记录](https://github.com/M1gr4ine/YesPlayMusic-Mobile/blob/v0.2.15/docs/VERIFICATION-v0.2.15.md)。

## 下载与校验

- Android 版本：**0.2.15（versionCode 17）**，沿用与 `v0.2.9`–`v0.2.14` 相同的发布签名，支持直接覆盖安装。
- APK：**YesPlayMusic-Mobile-v0.2.15.apk**
- SHA-256：`4a227b797708e7e311fe9fb17d995face6e67b6533a6b63c9d0465040bda469e`
- 校验文件：**SHA256SUMS.txt**

本项目是基于 [qier222/YesPlayMusic](https://github.com/qier222/YesPlayMusic) 的非官方 Android 客户端，采用 MIT 许可证。项目归属与媒体内容说明见仓库 `LICENSE`、`NOTICE.md`。

[查看 v0.2.14 → v0.2.15 的全部代码变化](https://github.com/M1gr4ine/YesPlayMusic-Mobile/compare/v0.2.14...v0.2.15)
