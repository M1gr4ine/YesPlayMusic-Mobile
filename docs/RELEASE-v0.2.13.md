# YesPlayMusic Mobile v0.2.13

这是相对于 **v0.2.12** 的更新，重点重构跨源歌曲匹配规则、加固双账号回退播放与跨源歌词链路，并新增可切换的 Material 3 界面风格。

## 跨源歌曲匹配重构

- 跨源选歌改为沿用 UNM / Yokaze 规则并增加歌名与版本核对：搜索使用 `歌名 - 歌手1 / 歌手2` 格式，查看前 5 条可播放结果（具备 `mid` 与 `media_mid`）；无候选时去掉 `Explicit` 等良性标注、只保留第一位歌手重试一次。
- 新增共用选歌函数 `QQMatch.select`：主标题相同且版本标记一致（拒绝 Live、伴奏、Remix、Acapella、`HOOLIGANS VERSION`、粤语/国语/女版等异版）；按平台排序优先选择时长差 5 秒以内的结果，其次无时长结果，排除已知时长不符的结果（如 30 秒试听片段）。
- 播放侧不再因歌手改名或译名（如 `Ye (侃爷)`、`宇多田光`）直接否决候选，署名重合（含曲目自带别名与成员信息）只用于同档优先排序；QQ 曲目反向匹配到网易云用于收藏等写入场景时仍要求署名重合，避免误收藏翻唱版本。
- 精简旧的别名桥接、文字系统冲突、成员映射与多轮重搜逻辑（`ArtistAliases`、`QQMatch`、`QQSource`、`MusicApi` 及对应测试净减 1,293 行）。

## 双账号播放回退与播放加固

- 曲库身份贯穿队列与历史记录：网易云数字 ID 与 `qq:<MID>` 始终标识原曲，播放快照 `source` 只表示实际音源；借 QQ 播放后收藏仍写原网易云 ID。
- `auto` 模式按原曲库优先、另一平台回退执行，手动来源偏好只走指定平台；单曲解码失败时续试同源候选（网易云非 MP3 可降级重试）并保留当前进度；切歌后按 `playbackGeneration` 丢弃过期解析结果与播放器回调。
- 所有播放流地址统一经 `StreamHosts` 校验，拦截非受信任域名。

## 跨源歌词加载

- 歌词按曲目身份先查原曲库，再查已核实的跨源目标；音源准备期间晚到的 `lyricsTargets` 可自动补查。
- 已有原文歌词时保持显示，待带翻译的新时间轴兼容后再替换；修复 A → B → A 切歌迟到响应串词问题并增加有界重试，同步覆盖系统媒体歌词。

## 可切换 Material 3 界面

- 设置中新增界面风格切换（`classic` 经典风格 / `material3` Material 3 风格），与主题（跟随系统 / 浅色 / 深色）分别持久化；两套风格共用页面结构与操作逻辑，适配播放/暂停状态切换、选中态与减少动态效果。

## 自动检查与真机实测

- 自动测试通过：**JavaScript 36 项、Java 351 项检查**（`run-tests.py`）。
- 已在真机上安装同源码构建的 debug 包并实测 8 首代表曲目的跨源播放与进度推进：`FML`（命中 `FML (Explicit)` 而非 `Flashing Lights`）、`Famous`（排除 30 秒试听）、`CARNIVAL`（排除 Acapella / `HOOLIGANS VERSION`）、`Like Him`、`First Love`（`宇多田ヒカル` → `宇多田光`）、`Lemon`、`最后派对` 与 `粤语残片`（粤语原版），均正常起播、进度推进且 MediaSession 与歌词状态正常。
- 只读抽查 QQ → 网易云反向匹配：`Famous`、`Lemon`、`First Love` 均准确命中；网易云搜索结果全为翻唱的 `晴天` 及无关歌手负例均返回 `matched: false`。
- 详见[v0.2.13 验证记录](https://github.com/M1gr4ine/YesPlayMusic-Mobile/blob/v0.2.13/docs/VERIFICATION-v0.2.13.md)。

## 下载与校验

- Android 版本：**0.2.13（versionCode 15）**，沿用与 `v0.2.9`–`v0.2.12` 相同的发布签名，支持直接覆盖安装。
- APK：**YesPlayMusic-Mobile-v0.2.13.apk**
- SHA-256：`a873d5463d5d0c5286234660c6ddc438cc06a852be65326e711f6ebe7ae64d80`
- 校验文件：**SHA256SUMS.txt**

本项目是基于 [qier222/YesPlayMusic](https://github.com/qier222/YesPlayMusic) 的非官方 Android 客户端，采用 MIT 许可证。项目归属与媒体内容说明见仓库 `LICENSE`、`NOTICE.md`。

[查看 v0.2.12 → v0.2.13 的全部代码变化](https://github.com/M1gr4ine/YesPlayMusic-Mobile/compare/v0.2.12...v0.2.13)
