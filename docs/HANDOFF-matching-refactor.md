# 跨源歌曲匹配重构交接文档（网易云 ⇄ QQ 音乐）

- **状态**：暂停于 **Step 3 已实现并全绿，待 Step 3 审计后进入 Step 4/5**
- **工作分支**：`refactor/track-matcher`（基于 `origin/main`）
- **最近验证**：`run-tests.py` 全绿 —— **JS 36/36，Java 614 checks**；`scripts/generate_opencc_table.py --check` 通过；`git diff --check` 通过
- **协作分工**：与 Astra（Codex）约定采用方式 A（主代理实现、测试与提交；Astra 只读深度审计，已完成 2 轮审计并全部修复）

---

## 1. 背景与目标

把网易云 ⇄ QQ 音乐跨源匹配从 `36c86e2`–`36b96d1` 叠加的个案特判链（`bridgeAccepts`、`evidencedLeadAlbumBridge`、`crossScript`、`bilingualCredit`、`distinctiveAlbum`、`scriptOverlapWithKnown`、`artistQuery && rank==0`、`QQSource.hasCompetingCredit`、`NeteaseMatch` 打分与手工繁简表）重构为单一通用规则匹配器 `TrackMatcher` 与统一查询阶梯：

- **统一查询阶梯**：`title + 全部歌手` → `title + 主唱` → `title + 成员别名 (≤2)` → `title`（命中 Rule A 即短路；Rule B 须跑完全部阶梯仍唯一才放行）。
- **硬性约束**：
  - `src/` 生产代码禁止出现任何具体歌手名、歌名、专辑名判断；词表与阈值集中在 `MatchRules`。
  - `minSdk 26`，禁止 `android.icu.text.Transliterator`；繁简表由 OpenCC `TSCharacters.txt + JPShinjitaiCharacters.txt`（Apache-2.0）生成并附脚本与溯源。
  - 五步独立提交并通过 `run-tests.py`；GitHub 仓库提交身份固定为 `M1gr4ine <134499596+M1gr4ine@users.noreply.github.com>`；严禁提交 `.agents/`。

---

## 2. 已完成提交与当前工作区

| 步骤 | 提交 | 内容摘要 |
|---|---|---|
| Step 1 | `a7b8bcc`、`f2f6f35` | 新增 `MatchTrace` 诊断与零开销门槛/脱敏边界、`QQSource` 与 `MusicApi.matchTrack` 全路径埋点（不改旧逻辑）、`searchRawList` 保留原始行号与被过滤行，完成《粤语残片》实测定位。 |
| Step 2 | `5a30066` | 新增 `scripts/generate_opencc_table.py`、`OpenCCFoldTable.java`（3,202 条幂等映射：2,965 TS + 237 JP，OpenCC commit `fae2a0a911c30764e669a7d8b0591cd8e852934a`，带 SHA-256 与 Apache-2.0 全文 `docs/licenses/OpenCC-Apache-2.0.txt`）、`MatchRules.java`、`MatchText.java`、`MatchTextTest.java`；替换旧手工 `FOLD_FROM`/`FOLD_TO`。 |
| Step 3 | 本分支最新提交 | 新增 `TrackMatcher.java`（含 `Policy.PLAYBACK`/`WRITE`、`Rule A/B`、`queryLadder`、`MatchTrace` 集成）、`MatchText.analyzeTitle`/`sameAlbumTitle`/`isLiveAlbum`、`QQSource.extractMatchCandidates`（支持 `grp[]` 展开的纯辅助方法，尚未切换生产路径）、16 份真实录制 fixture（`tests/fixtures/match/*.json`）、表驱动影子对比 runner `MatchFixtureTest.java`、规则命名合成单测 `TrackMatcherTest.java`，并将 `QQMatchTest.java` 与 `ArtistAliasesTest.java` 改写为合成数据规则单测。 |

> 说明：`MatchTrace` 在 debuggable 构建自动开启并读取 `mobile_settings.matchDiagnostics`，但 `MainActivity` 设置页 UI 尚未接出显式开关。

---

## 3. 用户已拍板的 7 项规则取舍（A1–A7，后续不可自行推翻）

1. **A1（Step 2 OpenCC 字符边界）**：严格仅用 `TSCharacters.txt + JPShinjitaiCharacters.txt` 并保持 GB2312 防撞（保护 TS 目标，阻断 `沪/滤`、`芸/艺`、`予/豫`、`欠/缺`、`缶/罐` 等跨字错配），接受旧手工表中 6 组字符（`邉/边`、`嶋/岛`、`髙/高`、`讃/赞`、`窓/窗`、`浜/滨`）不再折叠。
2. **A2（Rule B 唯一性作用域）**：`PLAYBACK` 与 `WRITE` 均须等全部查询阶梯跑完仍唯一才允许走 Rule B；任意阶段命中 Rule A（`WRITE` 且无歧义）即可提前短路。
3. **A3（Live 年份标注）**：如 `粤语残片 (2007 Live)` vs `粤语残片 (Live)`，仅一方标年份时，允许在「同一张非合辑现场专辑且同批无其他年份/场次竞争」时匹配；双方明示不同年份一律拒绝。
4. **A4（仅客串重合）**：双方主唱不同、仅客串成员重合（含单人↔合作与合作↔合作）时，双向均拒绝且不回落 Rule B（全员集合一一对应仅顺序互换除外）。
5. **A5（同文字体系括号冲突）**：如 `Kanye West [alias: ye]` vs `Ye (Taylor Swift)`，否决 Rule A，但若满足同非合辑专辑、全局唯一、无 Rule A 候选等条件，允许回落 Rule B。
6. **A6（无歌手交集时的单人↔合作走 Rule B）**：允许（须满足同非合辑专辑、全局唯一、无 Rule A 候选且无结构分组冲突；`A & B` vs `A / B`、`AC/DC` vs `AC / DC`、`AB / C` vs `A / BC` 等结构分组冲突双向否决 A 与 B）。
7. **A7（`Policy.WRITE` 主唱有官方证据的部分覆盖）**：全员不齐、仅主唱有可靠 ID/官方 alias 证据时（如 `Ye (侃爷)` → `Kanye West / Rihanna`），限同一张非合辑专辑（且需单边子集覆盖，无矛盾合作者）；全员覆盖时仍可跨专辑。

---

## 4. 《粤语残片》实测根因（按网易云歌曲 ID 分列）

QQ 搜索 `"粤语残片 陈奕迅"` 返回 `list[0]` 为 `000J9BOC3sAUqd`（*What's Going On...? (Remastered 2019)*，272s，`pay_play=1`），并在 **`list[0].grp[]`** 折叠了另 3 个录音室母带：`000SX6cK3Se2WV`（*What's Going On...?*，**276s**）、`000vPmze3aWPCm`（*Solidays (2 CD)*，272s）、`00299LKs1nNO45`（*What's Going On...? (2nd Edition)*，**276s**）。当前生产 `QQSource` 完全忽略 `grp[]`。

- **网易云 `1411495700`**（*Remastered 2019*，**280264ms**）：对 `list[0]`（272s）时长差 **8264ms**，被旧严格（>8000ms）与 bridge（>5000ms）双拒，0 次 `vkey` → `match_not_found`。`grp[0]`/`grp[2]` 均为 276s（差 **4264ms ≤ 5000ms**），展开 `grp[]` 即可命中（无需放宽 5s 时长门槛）。
- **网易云 `65269`**（`粤语残片 (2007 Live)`，*Eason Moving On Stage 1 (Live)*，**277223ms**）：三条实录查询中正确项 `003oAh0u3RTCv3`（`粤语残片 (Live)`，同现场专辑，差 223ms）均因旧 `mainTitle` 保留含版本标签括号（`"粤语残片2007live" != "粤语残片live"`）在 TITLE 门槛被拒，0 次 `vkey`。新规则 A3 无需展开 `grp[]` 即可准确命中。
- **网易云 `65778`（276306ms）/ `2106661548`（276146ms）/ `64959`（272413ms）**：旧逻辑首查即严格命中 `list[0]`（`000J9BOC3sAUqd`）并短路，仅 1 条候选；若该 `pay_play=1` MID 无权取流，不会回退到 `grp[]` 备选（真实 VIP 取流权限离线未测）。展开 `grp[]` 后，对 `65778` 可将精确 306ms 差的 `000SX6cK3Se2WV` 排第 1，并提供 3 个录音室候选。

---

## 5. Step 3 影子对比差异清单（16 份真实录制 fixture）

| Fixture | 方向 / Policy | Legacy 结果 | TrackMatcher 结果 | 差异 | 判定理由 |
|---|---|---|---|---|---|
| `band_acdc_preserved` | N→Q / PLAYBACK | `reject` | `qq:001KtqEb0jd0hf` (A) `top3=[001KtqEb0jd0hf, 002dYf5A1YlIGc, 003QiMEv4Vh7Tq]` | **DIFF** | **新逻辑正确**。旧 `QQMatch.keyword` 把结构化单乐队 `"AC/DC"` 拆成 `"Back In Black AC DC"` 导致召回失配；`TrackMatcher.queryLadder` 保留结构化成员名 `"AC/DC"`。 |
| `band_acdc_vs_split_rejected` | N→Q / PLAYBACK | `reject` | `reject` | SAME | 结构分组冲突同时否决 Rule A 与 Rule B。 |
| `cover_rejected` | N→Q / PLAYBACK | `reject` | `reject` | SAME | 真实《晴天》翻唱列表（含 1s 时长差）被 A/B 同时挡住。 |
| `duo_ampersand_preserved` | N→Q / PLAYBACK | `qq:000hzYbD0kNQrx` `top3=[000hzYbD0kNQrx, 001Eo9232Hwd7o, 002PwbRl0bNuSQ]` | `qq:000hzYbD0kNQrx` (A) `top3=[000hzYbD0kNQrx, 001wfuQz2Nljjm, 0002jAzb1XINCr]` | **DIFF (仅备选第2-3名)** | **新逻辑更优**。首选相同（精确同专辑 185s），展开 `grp[]` 后备选 2–3 名为时长差更近的 185s 版本（旧备选为 187s/189s）。 |
| `duo_ampersand_vs_slash_rejected` | N→Q / PLAYBACK | `reject` | `reject` | SAME | 单实体 `"Simon & Garfunkel"` 与拆分 `"Simon / Garfunkel"` 触发分组冲突否决。 |
| `famous_forward_playback` | N→Q / PLAYBACK | `qq:003Z0JlC3c9Jwx` `top3=[003Z0JlC3c9Jwx, 002XelTe31rBEE]` | `qq:002XelTe31rBEE` (A) `top3=[002XelTe31rBEE, 000GOFgD48q1Sm, 003Z0JlC3c9Jwx]` | **DIFF (排序)** | **符合目标排序规则**。三者均通过 Rule A；新规则按「同专辑优先 → 时长差升序」，将同专辑 *The Life Of Pablo* 的 Explicit/Clean（差 40ms）排前二，单曲版 `Ye (侃爷) / Rihanna`（差 1040ms）排第三，30s 试听与 Tribute 均被拒。 |
| `famous_reverse_write_full_credit` | Q→N / WRITE | `408440465` | `408440465` (A) | SAME | 全员覆盖 + 同专辑单曲优先。 |
| `famous_reverse_write_solo_lead` | Q→N / WRITE | `409736434` | `409736434` (A) | SAME | 主唱官方别名证据 + 同非合辑专辑（A7）+ 同身份取更近时长。 |
| `fml_forward_no_alias_rule_b` | N→Q / PLAYBACK | `qq:0005toRJ3QqtAx` | `noGrp: qq:0005toRJ3QqtAx` (B) / **`+Grp: reject`** | **SAME (noGrp) / 待确认 (+Grp)** | **见下方第 6 节待确认发现**。 |
| `guest_only_overlap_rejected` | N→Q / PLAYBACK | `reject` | `reject` | SAME | A4 双向拒绝仅客串重合，且不回落 Rule B。 |
| `version_live_instrumental_mandarin` | N→Q / PLAYBACK | `reject` | `reject` | SAME | Live/伴奏/国语版/钢琴版全拒绝。 |
| `version_variants_rejected` | N→Q / PLAYBACK | `reject` | `reject` | SAME | 女版（0.4s 时长差）/DJ/片段/x 全拒绝。 |
| `wrong_song_same_artist_duration` | N→Q / PLAYBACK | `reject` | `reject` | SAME | 同歌手同 3.9s 时长不同歌名（富士山下 vs 爱情转移）被标题门槛拦截。 |
| `yueyu_canpian_live_65269` | N→Q / PLAYBACK | `reject` (`+Grp` 亦 `reject`) | `qq:003oAh0u3RTCv3` (A)（`noGrp`/`+Grp` 均命中） | **DIFF** | **新逻辑修复根因**。A3 单边 Live 年份 + 同现场专辑无竞争命中，2025 巡演与翻唱保持拒绝。 |
| `yueyu_canpian_remaster_1411495700` | N→Q / PLAYBACK | `reject`（`+Grp`: `qq:000SX6cK3Se2WV`） | `noGrp: reject` / `+Grp: qq:000SX6cK3Se2WV` (A) `top3=[000SX6cK3Se2WV, 00299LKs1nNO45]` | **DIFF** | **2×2 证明召回修复**。不放宽 5s 门槛，靠 `grp[]` 展开召回 276s 录音室母带（差 4264ms）。 |
| `yueyu_canpian_studio_65778` | N→Q / PLAYBACK | `qq:000J9BOC3sAUqd` `top3=[000J9BOC3sAUqd]` | `qq:000SX6cK3Se2WV` (A) `top3=[000SX6cK3Se2WV, 000J9BOC3sAUqd, 00299LKs1nNO45]` | **DIFF** | **新逻辑更优**。展开 `grp[]` 后精确同专辑 306ms 差母带升至第 1，并补齐 3 条录音室候选备胎。 |

---

## 6. 待确认的边界发现（进入 Step 4 前请先与用户对齐）

在真实录制的 `fml_forward_no_alias_rule_b.json` 中，QQ 将 `FML (Explicit)`（`0005toRJ3QqtAx`，`Ye (侃爷)`，`The Life Of Pablo (Explicit)`，236s）与 `FML` Clean 版（`003rrnm81JqHS2`，同一歌手 `Ye (侃爷)`，同一专辑 `The Life Of Pablo`，236s）折叠在同一个 `grp[]` 中：

- 若 Step 4 匹配路径展开 `grp[]`，且 **Rule B 唯一性严格按歌曲 ID 计数**：展开后会出现 2 个通过硬门槛 (1)(2)(3) 的 MID，导致无 alias 时无法走 Rule B（`NOT_UNIQUE`）。
- 同样情况在网易云也存在（如 `ne_famous_ye.json` 同时返回同歌手同专辑的两个发行 ID `409736434` 与 `484755737`）。
- **待用户拍板**：Rule B 的「唯一候选」是保持**按单个目录 ID 严格唯一**（当前实现，未擅自放宽），还是改为**按「歌手身份 + 专辑」去重后唯一**（允许同一歌手同一专辑的 Explicit/Clean 或再版同时存在，但不同歌手或不同专辑仍拒绝）？

---

## 7. 后续推进步骤（接手即做）

1. **Step 3 审查与收口**：
   - 将本分支 Step 3 变更交给 Astra（只读审计），重点核查 `TrackMatcher` 对 A1–A7 的实现边界、分组冲突、A3 竞争判定及 `MatchFixtureTest`。
   - 向用户汇报 Step 3 影子差异表，并确认第 6 节的 Rule B 唯一性粒度问题。
2. **Step 4（独立提交）：正反向切换至 `TrackMatcher`**：
   - `QQSource`：`searchCandidates` 改用 `TrackMatcher.queryLadder` + `QQSource.extractMatchCandidates(..., expandGrp = true, ...)` + `TrackMatcher.rankDetailed`（中间阶梯 `allowRuleB = false`，全部跑完再 `allowRuleB = true`；保留阶段 1 失败后仅查一次官方 `artistMembers`/alias 的行为；仅匹配路径展开 `grp[]`，不改 `qqSearch` UI 列表）。
   - `MusicApi.matchTrack` / `matchNeteaseTrack`：改用 `TrackMatcher.queryLadder` + `TrackMatcher.rankDetailed`；明确分流：`PlaybackService.java:715` 走 `Policy.PLAYBACK`，`assets/app.js:2808` 收藏/写歌单走 `Policy.WRITE`，`assets/app.js:3145` 导航查曲保持严格查询。
   - 同步更新 `QQSourceTest` 与 `MusicApiTest` 中依赖旧特判（如 `hasCompetingCredit` 否决 Rule A、旧 5-member `CARNIVAL` 拒收、旧 `Famous` 单曲排在同专辑之前）的断言，并改写为规则命名测试。
   - 交 Astra 审计，跑通 `run-tests.py` 后独立提交 Step 4。
3. **Step 5（独立提交）：清理旧特判代码与最终验收**：
   - 删除 `ArtistAliases.bridgeAccepts`、`ArtistAliases.evidencedLeadAlbumBridge`、`ArtistAliases.score`、`crossScript`、`bilingualCredit`、`distinctiveAlbum`、`scriptOverlapWithKnown`、`QQSource.hasCompetingCredit`、`QQMatch.score`/`scoreParts`、`MusicApi.NeteaseMatch` 打分与多余旧单测。
   - 全局 grep 确认 `src/` 无任何具体歌手/歌名/专辑名判断。
   - 运行 `run-tests.py` + `generate_opencc_table.py --check` + `git diff --check`，交 Astra 终审后提交 Step 5。

---

## 8. 本地测试与工具命令

```powershell
# 全量 JS + Java 测试（本机需指定 GraalVM JDK 21、Anaconda Python、android.jar 与 org.json jar）
$env:JAVA_HOME = "C:\Program Files\Java\graalvm-jdk-21.0.9+7.1"
& "E:\Anaconda3\python.exe" run-tests.py --android-jar <path-to-android.jar> --json-jar <path-to-json-20240303.jar>

# 验证 OpenCC 生成表可复现（无需改代码）
& "E:\Anaconda3\python.exe" scripts/generate_opencc_table.py --opencc-dir <opencc-dict-dir> --check

# 打印完整 16-fixture 影子对比报告（在 AllTests JVM 参数加上 -Dypm.shadow.report=true）
java -Dypm.shadow.report=true -cp <json.jar>;<classes>;<android.jar> dev.yesplaymusic.probe.AllTests

# Git 提交前核对个人 GitHub 身份（切勿使用公司邮箱，切勿暂存 .agents/）
git config --get user.name   # 必须为 M1gr4ine
git config --get user.email  # 必须为 134499596+M1gr4ine@users.noreply.github.com
```
