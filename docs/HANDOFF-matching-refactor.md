# 跨源歌曲匹配重构交接文档（网易云 ⇄ QQ 音乐）

- **状态**：**Step 4 已切换并完成审计回归，下一步清理旧特判代码**
- **工作分支**：`refactor/track-matcher`（基于 `origin/main`）
- **最近验证**：`run-tests.py` 全绿 —— **JS 36/36，Java 782 checks**；`scripts/generate_opencc_table.py --check` 通过；`git diff --check` 通过
- **协作分工**：Antigravity 完成前期实现与实录，Astra 完成只读复审后接手 Step 4 收口与 Step 5 清理

---

## 1. 背景与目标

把网易云 ⇄ QQ 音乐跨源匹配从 `36c86e2`–`36b96d1` 叠加的个案特判链（`bridgeAccepts`、`evidencedLeadAlbumBridge`、`crossScript`、`bilingualCredit`、`distinctiveAlbum`、`scriptOverlapWithKnown`、`artistQuery && rank==0`、`QQSource.hasCompetingCredit`、`NeteaseMatch` 打分与手工繁简表）重构为单一通用规则匹配器 `TrackMatcher` 与统一查询阶梯：

- **统一查询阶梯**：`title + 全部歌手` → `title + 主唱` → `title + 成员别名 (≤2)` → `title`（命中无歧义 Rule A 即短路；Rule B 须跑完全部阶梯仍唯一才放行；任一搜索响应失败/为空/格式异常则沿错误通道中止，不回落 Rule B）。
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
| Step 3 | `5e0898b`、`c36a7fc`、`8c9a4c8` | 新增 `TrackMatcher.java`（含 `Policy.PLAYBACK`/`WRITE`、`Rule A/B`、`queryLadder`、`MatchTrace` 集成）、`MatchText.analyzeTitle`/`sameAlbumTitle`/`isLiveAlbum`、`QQSource.extractMatchCandidates`、18 份脱敏 fixture（`tests/fixtures/match/*.json`，区分 `direct_replay` / `curated_real_excerpt` / `synthetic_variant`）、表驱动验证 runner `MatchFixtureTest.java`、规则命名单测 `TrackMatcherTest.java`，并修复 Astra 第 3 轮 5 P1 + 3 P2 审计发现。 |
| Step 4 | 本次提交 | 生产正反向路径切换至 `TrackMatcher`：`QQSource.searchCandidates`/`savedCandidate`（匹配路径展开 `grp[]`，`qqSearch` UI 不变，删 `SearchHit`/`rankCandidates`/`findCompetingCreditId`）、`MusicApi.matchTrack`/`matchNeteaseTrack`（`PlaybackService` 走 `PLAYBACK`，收藏/导航走 `WRITE` 并区分 `entryPoint`，删 `NeteaseMatch`）、`assets/app.js` 透传 `id` 与 `purpose`；修复 Astra 第 4 轮 7 P1 + 3 P2 审计发现（严格搜索响应解析、A6 分组冲突不被 A5 覆盖、未归属成员 alias 不泄漏、同 ID 跨轮归并保时长、旧缓存依赖 A3 转入全池复核、成员级未知占位词不提供 Rule A 身份证据、WRITE 短路前同档竞争者补查、多成员部分覆盖非主唱补查、`甲／乙`/`甲/乙` 边界一致、fixture 决定轮指定候选 gate 校验）。反向匹配在同一请求内按供应商歌手 ID 与规范名共享官方别名；优先补查同专辑候选，每次新增证据即重排，共享 4 次详情预算。 |

> 说明：`MatchTrace` 在 debuggable 构建自动开启并读取 `mobile_settings.matchDiagnostics`，`MainActivity` 设置页 UI 尚未接出显式开关。

---

## 3. 已拍板的 8 项规则口径（A1–A7 + Rule B 单 ID 唯一，后续不可自行推翻）

1. **A1（Step 2 OpenCC 字符边界）**：严格仅用 `TSCharacters.txt + JPShinjitaiCharacters.txt` 并保持 GB2312 防撞（保护 TS 目标，阻断 `沪/滤`、`芸/艺`、`予/豫`、`欠/缺`、`缶/罐` 等跨字错配），接受旧手工表中 6 组字符（`邉/边`、`嶋/岛`、`髙/高`、`讃/赞`、`窓/窗`、`浜/滨`）不再折叠。
2. **A2（Rule B 唯一性作用域与错误中止）**：`PLAYBACK` 与 `WRITE` 均须等全部查询阶梯跑完仍唯一才允许走 Rule B；任意阶段命中无歧义 Rule A 即可提前短路。任一阶梯搜索失败、响应码异常、缺少结果列表或返回 `null` 必须沿错误通道中止，不得当作零结果回落 Rule B。
3. **A3（Live 年份标注）**：如 `粤语残片 (2007 Live)` vs `粤语残片 (Live)`，仅一方标年份时，允许在「同一张非合辑现场专辑且同批无其他年份/场次竞争」时匹配；双方明示不同年份一律拒绝。
   - **严格口径说明**：当目标未标年份且同张现场专辑上存在两个不同目录 ID 的同歌名现场录音（即使两者标同一显式年份与相同时长），当前 `findLiveYearCompetition` 仍将第二条不同目录 ID 视为同专辑场次竞争并拒绝，不静默放宽；旧未验证缓存若依赖 A3 单边年份回落亦不直接取流，交由完整搜索池复核。
4. **A4（仅客串重合）**：双方主唱不同、仅客串成员重合（含单人↔合作与合作↔合作）时，双向均拒绝且不回落 Rule B（全员集合一一对应仅顺序互换除外）。未知占位词成员（如 `Unknown`、`群星`）不提供 Rule A 身份证据，且保留原始主客位次。
5. **A5（同文字体系括号冲突）**：如 `Kanye West [alias: ye]` vs `Ye (Taylor Swift)`（覆盖全 Unicode 文字体系及成员级括号冲突），否决 Rule A，但若满足同非合辑专辑、全局唯一、无 Rule A 候选且未触发 A4/A6 否决，允许回落 Rule B。
6. **A6（无歌手交集时的单人↔合作走 Rule B）**：允许（须满足同非合辑专辑、全局唯一、无 Rule A 候选且无结构分组冲突；`A & B` vs `A / B`、`AC/DC` vs `AC / DC`、`AB / C` vs `BC / A`、`AB (A) / C (BC)` vs `A / BC` 等结构分组冲突双向否决 A 与 B）。
7. **A7（`Policy.WRITE` 主唱有官方证据的部分覆盖）**：全员不齐、仅单边子集覆盖时（如 `Ye (侃爷)` → `Kanye West / Rihanna`），限同一张非合辑专辑且实际命中的主唱↔主唱 token 须有官方 alias 或带供应商 ID 的规范本名证据；全员覆盖时仍可跨专辑。
8. **Rule B 严格单目录 ID 唯一**：正反向 `PLAYBACK` 与 `WRITE` 的 Rule B 均保持严格按单个目录 ID 唯一（如无 alias 时，QQ `FML` 展开 `grp[]` 后 Explicit `0005toRJ3QqtAx`（album 1491746）与 Clean `003rrnm81JqHS2`（album 3899274）分属 2 个不同目录 ID，无 alias 情况下严格拒绝走 Rule B；真实网易云歌曲 `409736441` 带有官方 `ye` alias，直接走 Rule A 命中）。

---

## 4. 《粤语残片》实测根因（按网易云歌曲 ID 分列）

QQ 搜索 `"粤语残片 陈奕迅"` 返回 `list[0]` 为 `000J9BOC3sAUqd`（*What's Going On...? (Remastered 2019)*，272s，`pay_play=1`），并在 **`list[0].grp[]`** 折叠了另 3 个录音室母带：`000SX6cK3Se2WV`（*What's Going On...?*，**276s**）、`000vPmze3aWPCm`（*Solidays (2 CD)*，272s）、`00299LKs1nNO45`（*What's Going On...? (2nd Edition)*，**276s**）。旧生产 `QQSource` 完全忽略 `grp[]`。

- **网易云 `1411495700`**（*Remastered 2019*，**280264ms**）：对 `list[0]`（272s）时长差 **8264ms**，被旧严格（>8000ms）与 bridge（>5000ms）双拒，0 次 `vkey` → `match_not_found`。`grp[0]`/`grp[2]` 均为 276s（差 **4264ms ≤ 5000ms**），Step 4 在匹配路径展开 `grp[]` 后即可命中（无需放宽 5s 时长门槛）。
- **网易云 `65269`**（`粤语残片 (2007 Live)`，*Eason Moving On Stage 1 (Live)*，**277223ms**）：实录查询中正确项 `003oAh0u3RTCv3`（`粤语残片 (Live)`，同现场专辑，差 223ms）均因旧 `mainTitle` 保留含版本标签括号（`"粤语残片2007live" != "粤语残片live"`）在 TITLE 门槛被拒，0 次 `vkey`。新规则 A3 无需展开 `grp[]` 即可准确命中。
- **网易云 `65778`（276306ms）/ `2106661548`（276146ms）/ `64959`（272413ms）**：旧逻辑首查即严格命中 `list[0]`（`000J9BOC3sAUqd`）并短路，仅 1 条候选；若该 `pay_play=1` MID 无权取流，不会回退到 `grp[]` 备选（真实 VIP 取流权限离线未测）。Step 4 展开 `grp[]` 后，对 `65778` 可将精确 306ms 差的 `000SX6cK3Se2WV` 排第 1，并提供 3 个录音室候选。

---

## 5. 18 份脱敏 Fixture 验收清单（`tests/fixtures/match/*.json`）

`MatchFixtureTest` 同时对 `TrackMatcher` 与生产 `QQSource.lookupAttempts` / `MusicApi.matchTrack` 双路径回放全部 18 份磁盘 fixture，双路径均断言目标 ID、命中规则（提取自生产 `MatchTrace` 决定轮）、`direct_replay` 零缺失查询，以及 7 份拒绝 fixture 在决定轮指定候选上的 `MatchTrace` 拒绝 gate（Top-3 排序在 `TrackMatcher` 全方向与 `QQSource` 正向回放中断言；`MusicApi.matchTrack` 单值返回首命中）。下表逐项核对磁盘 JSON 实际字段，并保留 Step 3 已审旧/新影子差异说明：

| Fixture（磁盘文件名） | 类型 (`fixtureKind`) | 方向 / Policy | 预期结果 (`expect` / `expectRule`) | 决定轮拒绝 Gate (`expectGate` / `expectGateCandidate`) | 验收与 Step 3 影子差异要点 |
|---|---|---|---|---|---|
| `band_acdc_preserved` | `direct_replay` | N→Q / PLAYBACK | `qq:001KtqEb0jd0hf` (`A`) | — | **较旧版修复**：`queryLadder` 保留结构化单乐队 `"AC/DC"`（旧版拆成 `"AC DC"` 召回失配） |
| `band_acdc_vs_split_rejected` | `synthetic_variant` | N→Q / PLAYBACK | `reject` (`NONE`) | `VETO` / `qq:001KtqEb0jd0hf` | A6 结构分组冲突双向否决 Rule A 与 Rule B |
| `cover_rejected` | `curated_real_excerpt` | N→Q / PLAYBACK | `reject` (`NONE`) | `ARTIST` / `qq:000VNlcM0udCXc` | 真实 1.3s 差翻唱在 ARTIST 门被拒且不走 Rule B |
| `duo_ampersand_preserved` | `direct_replay` | N→Q / PLAYBACK | `qq:000hzYbD0kNQrx` (`A`) | — | **较旧版更优**：首选相同（185s），展开 `grp[]` 备选 2–3 亦为 185s |
| `duo_ampersand_vs_slash_rejected` | `synthetic_variant` | N→Q / PLAYBACK | `reject` (`NONE`) | `VETO` / `qq:000hzYbD0kNQrx` | 单实体 `"Simon & Garfunkel"` 对拆分 `"Simon / Garfunkel"` 触发 A6 `VETO` |
| `famous_forward_playback` | `direct_replay` | N→Q / PLAYBACK | `qq:002XelTe31rBEE` (`A`) `top3=[002XelTe31rBEE, 000GOFgD48q1Sm, 003Z0JlC3c9Jwx]` | — | **较旧版排序更符合目标**：同专辑 Explicit/Clean（差 40ms）优先于单曲版（差 1040ms） |
| `famous_reverse_playback_enrichment` | `curated_real_excerpt` | Q→N / PLAYBACK | `409736434` (`A`) `top3=[409736434, 484755737, 408440465]` | — | 反向 PLAYBACK 经艺人详情补查官方 alias 命中同专辑版本 |
| `famous_reverse_write_full_credit` | `direct_replay` | Q→N / WRITE | `408440465` (`A`) | — | 全员覆盖 + 同专辑单曲优先（与旧版一致） |
| `famous_reverse_write_solo_lead` | `direct_replay` | Q→N / WRITE | `409736434` (`A`) | — | A7 主唱官方别名证据 + 同非合辑专辑 + 同身份取更近时长（与旧版一致） |
| `fml_forward_no_alias_rule_b` | `direct_replay` | N→Q / PLAYBACK | `noGrp: qq:0005toRJ3QqtAx` (`B`) / 生产 `+Grp: reject` (`NONE`) | — | 验证单目录 ID 唯一走 Rule B；生产展开 `grp[]` 出现 2 个不同 MID/专辑 ID 时严格拒绝 |
| `fml_forward_with_official_alias` | `direct_replay` | N→Q / PLAYBACK | `qq:0005toRJ3QqtAx` (`A`) `top3=[0005toRJ3QqtAx, 003rrnm81JqHS2]` | — | 真实网易云带官方 `ye` alias 时，展开 `grp[]` 仍走 Rule A 稳定命中 |
| `guest_only_overlap_rejected` | `synthetic_variant` | N→Q / PLAYBACK | `reject` (`NONE`) | `ARTIST` / `qq:002GuestOnlyRih` | A4 双向拒绝仅客串重合且不回落 Rule B |
| `version_live_instrumental_mandarin` | `synthetic_variant` | N→Q / PLAYBACK | `reject` (`NONE`) | `VERSION` / `qq:003aAPj81VWrbLman` | Live/伴奏/国语版/钢琴版在 VERSION 门全拒 |
| `version_variants_rejected` | `curated_real_excerpt` | N→Q / PLAYBACK | `reject` (`NONE`) | `VERSION` / `qq:001z7iVD0jLf4s` | 女版（0.4s 差）/DJ/片段/x 在 VERSION 门全拒 |
| `wrong_song_same_artist_duration` | `curated_real_excerpt` | N→Q / PLAYBACK | `reject` (`NONE`) | `TITLE` / `qq:004ff6bV3wHhjt` | 同歌手同 3.9s 时长不同歌名在 TITLE 门拦截 |
| `yueyu_canpian_live_65269` | `direct_replay` | N→Q / PLAYBACK | `qq:003oAh0u3RTCv3` (`A`) | — | **较旧版修复根因**：A3 单边 Live 年份 + 同现场专辑无竞争命中（`noGrp`/`+Grp` 均命中） |
| `yueyu_canpian_remaster_1411495700` | `direct_replay` | N→Q / PLAYBACK | `noGrp: reject` / 生产 `+Grp: qq:000SX6cK3Se2WV` (`A`) `top3=[000SX6cK3Se2WV, 00299LKs1nNO45]` | — | **较旧版修复根因**：2×2 证明靠 `grp[]` 展开召回 276s 母带（差 4264ms），不放宽 5s 门槛 |
| `yueyu_canpian_studio_65778` | `direct_replay` | N→Q / PLAYBACK | `qq:000SX6cK3Se2WV` (`A`) `top3=[000SX6cK3Se2WV, 000J9BOC3sAUqd, 00299LKs1nNO45]` | — | **较旧版更优**：展开 `grp[]` 后精确同专辑 306ms 差母带升至第 1，提供 3 条录音室候选 |

> 说明：A3 同专辑年份竞争、A5 同文字体系括号冲突回落 Rule B、A6 结构分组重切分与跨轮归并等合成边界场景由 `TrackMatcherTest`、`QQSourceTest` 与 `MusicApiTest` 直接覆盖。

---

## 6. 后续推进步骤

1. **Step 5（独立提交）：清理旧特判代码与最终验收**：
   - 清理 `ArtistAliases` 中旧匹配/打分/bridge/查询方法（`score`、`evidencedLeadAlbumBridge`、`bridgeAccepts`、`crossScript`、`bilingualCredit`、`distinctiveAlbum`、`scriptOverlapWithKnown`、`searchQueries`、`ResolvedCredit` 等），仅保留结构化成员/别名提取与序列化工具。
   - 清理 `QQMatch` 中旧 `score`/`scoreParts`/`sameArtistParts` 及不再需要的透传包装（保留或直接迁移至 `MatchText`/`MatchRules`），清理 `MatchTrace` 中无引用旧枚举（`Mode.LEGACY`、`Rule.LEGACY_*`、`Gate.COMPETING_CREDIT`），更新相关测试（含 `MatchTextTest`、`QQMatchTest`、`ArtistAliasesTest`）。
   - 全局 grep 确认 `src/` 零具体歌手/歌名/专辑名；跑全量 `run-tests.py`、`scripts/generate_opencc_table.py --check` 与 `git diff --check`，交 Astra 终审后独立提交 Step 5。

---

## 7. 本地测试与工具命令

```powershell
# 全量 JS + Java 测试（含 18 份 fixture 的 TrackMatcher + 生产双路径回放）
$env:JAVA_HOME = "C:\Program Files\Java\graalvm-jdk-21.0.9+7.1"
& "E:\Anaconda3\python.exe" run-tests.py --android-jar <path-to-android.jar> --json-jar <path-to-json-20240303.jar>

# 验证 OpenCC 生成表可复现（无需改代码）
& "E:\Anaconda3\python.exe" scripts/generate_opencc_table.py --opencc-dir <opencc-dict-dir> --check

# Git 提交前核对个人 GitHub 身份（切勿使用公司邮箱，切勿暂存 .agents/）
git config --get user.name   # 必须为 M1gr4ine
git config --get user.email  # 必须为 134499596+M1gr4ine@users.noreply.github.com
```
