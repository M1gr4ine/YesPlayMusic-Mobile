# YesPlayMusic Mobile 工程指南

## 代码地图

Android 原生播放服务 + WebView 页面。页面入口 `assets/index.html`，直接加载同目录的 CSS 和 JS；原生代码在 `src/dev/yesplaymusic/probe/`。

| 改动 | 入口与职责 |
| --- | --- |
| 页面、导航、设置 | `assets/app.js` 管页面状态和交互。`MainActivity.NativeBridge` 以 `window.Native` 接收请求和播放命令，异步结果经 `window.YPM.resolve` 返回；桥接字段两端一起改。 |
| 播放与账号 | `PlaybackService` 管队列、MediaPlayer、音频焦点、MediaSession 和通知，页面读它的快照。`MusicApi` 对接网易云，`QQSource` 对接 QQ；`LoginActivity`、`AuthStore` 管登录和会话。 |
| 曲目与歌词 | `Track` 定义页面、队列和服务共用的曲目字段；`ArtistAliases`、`QQMatch` 做跨曲库匹配。`assets/lyrics.js` 解析页面歌词，`LrcLines` 解析系统媒体歌词，`assets/standby.js` 管横屏待机。 |

## 构建与测试

在仓库根目录运行，不需要 Gradle。依赖 Python 3、JDK、Android SDK（Platform + Build Tools），页面测试另需 Node.js 18+。SDK 由 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME` 定位，JDK 由 `JAVA_HOME` 或 `PATH` 定位；Windows 可用 `py -3` 代替 `python3`。

```sh
python3 build-apk.py --debug                      # 输出 dist/YesPlayMusic-Mobile-debug.apk
python3 run-tests.py --js-only                    # 只跑 Node 测试
python3 run-tests.py --json-jar /path/to/json.jar # Node + JVM 全量
```

JVM 测试用 `android.jar` 编译，但其中的 `org.json` 只是桩类，需要另给真实的 `org.json` jar（`--json-jar` 或 `YPM_JSON_JAR`），脚本会把它排在 `android.jar` 前面。SDK 布局不标准时传 `--android-jar`，构建还可传 `--build-tools`。发布签名见[构建说明](docs/BUILDING.md)。

## 曲库身份、匹配和播放

- **曲库身份贯穿队列。** 网易云数字 ID 和 `qq:<MID>` 标识原曲，播放快照的 `source` 只表示实际音源。网易云歌曲借 QQ 播放后，收藏仍写原网易云 ID；QQ 曲目收藏到网易云前，先取得可信的网易云匹配。改字段时一并检查 `normalizeTrack`、`Track.fromJson`、序列化和旧队列恢复。
- **跨源匹配沿用 UNM 的选歌规则（原版 YesPlayMusic 和 Yokaze 都用这套）。** 用 `歌名 - 歌手1 / 歌手2` 搜索，只看可播放的前几条结果，按时长分三档选：先取时长差 5 秒以内的；其次取同一母带的时长漂移（两边都满 60 秒，差值不超过 15 秒且不超过原曲时长的 10%，覆盖重制版尾音裁剪）；再次取没有时长的；时长已知但对不上的不选，30 秒试听因此进不来。比 UNM 多的唯一一道检查是歌名和版本：主标题相同，live、伴奏、remix、语种等版本标记一致（英文版本标记用 ASCII 边界 `(?<![a-z0-9])…(?![a-z0-9])` 而非 `\b`，因为 Android 的 ICU 正则把汉字算作单词字符，会漏识别 `陈君豪Remix` 这类紧贴汉字的标记）；括号内按 `|` 逐段处理，Explicit 等良性标注、出处/合作说明（`from …`、`feat.`、`ft.`、`with`、`prod.`、`theme from`、`original soundtrack`、`ost`，前提是不含版本标记）、中文译名和重复主标题结尾的段落忽略，版本标记段保留；仅一边带语种标注、另一边不带时，只在专辑名一致（`sameAlbum`）的情况下放行。同一时长档内按「专辑一致 > 标题完全一致 > 署名重合（含曲目自带别名）> QQ 原始顺序」稳定排序；播放时不因署名对不上而判定匹配失败（QQ 常把歌手标成改名或中文译名，如 Ye (侃爷)、宇多田光）。QQ 曲目反向匹配到网易云会写入用户账号（收藏等），所以这里还要求署名有重合。QQ 的 `qqMid` 标识歌曲，`mediaMid` 用来拼音频文件名（如 `M800<mediaMid>.mp3`）。回归用例见 `QQSourceTest`：《FML》不能被同歌手、时长相近的《Flashing Lights》顶替；《Famous》排除 30 秒试听片段；Acapella 之类的版本不能顶替原版；《粤语残片》2019 重制版接受 QQ 短 8 秒的同一母带；《Psycho, Pt. 2》匹配 QQ 标题 `Psycho,Pt. 2 (Pt. 2|Explicit)`；《K歌之王》（《打得火热》）拒绝 `陈君豪Remix` 和国语版，优先选同专辑的 `K歌之王 (粤语)` 而非钢琴版；《A Rusty Dream (from Cyberpunk: Edgerunners 2)》匹配 QQ 标题 `A Rusty Dream`。
- **解析失败和播放器打开失败是两条路径。** 双账号各用本平台会话。`auto` 先试原曲库再试另一平台，手选来源只走指定平台。拿到地址后仍可能解码失败：`PlaybackService.handlePlayerError` 续试同源候选，网易云非 MP3 可再降级一次，并保留当时进度。流地址由 `StreamHosts` 校验；切歌后，旧的解析结果和播放器回调按 `playbackGeneration` 丢弃。
- **歌词跟随曲目身份。** 先查原曲库，再查已核实的跨源目标；`lyrics` 收网易云数字 ID，`qqLyrics` 收 QQ 歌曲 MID。音源准备期间晚到的 `lyricsTargets` 可以补查；已有原文时继续显示，带翻译的新时间轴兼容后再替换。改动后覆盖 A→B→A 迟到响应、同曲重试和系统媒体歌词，页面用例在 `tests/js/lyrics-fetch.test.js`。

## 界面与歌单

- `uiStyle`（`classic` 默认 / `material3`）和 `theme`（跟随系统 / 浅色 / 深色）分别持久化。两种风格共用页面结构和操作，M3 覆盖写在 `assets/material3.css` 的 `:root[data-ui="material3"]` 下。改播放按钮或设置控件后，在浅色、深色下来回切换风格，看播放/暂停图标、选中态和减少动态效果。
- 网易云歌单编辑由页面和原生各自核对当前账号是否为创建者，排除订阅歌单和“我喜欢”。重排需要完整曲目列表（上限 500 首），提交前再核对服务端 ID 集合。写入后回读：名称、简介逐字段比对，移除比对剩余 ID 集合，排序逐项比对顺序。部分成功或回读超时，就说明已确认的部分、保留未完成的草稿，重试前先查远端状态。

## 验收

- **单元测试**测可观察行为：切歌后的迟到响应、同名误配、草稿与服务端结果不一致等。`tests/js/support/app-harness.js` 直接载入 `app.js` 的生产函数，JVM 用例由 `AllTests` 汇总。布局、手势、动画和软键盘遮挡交给浏览器或真机。
- **浏览器**加载真实的 `assets/index.html`，用合成曲目和歌单驱动桥接，适合看迷你播放器展开、窄横屏待机、空闲态和编辑草稿。
- **真机**看软键盘、方向锁定、后台播放和系统媒体控制。播放验收同时看实际音源、MediaSession 状态和进度是否推进。装包时备份旧 APK、同签名覆盖安装，再回读设备 `base.apk` 比对 SHA-256。
- 横屏或电量测试前记下旋转模式、角度和充电状态，结束后恢复；用过电池模拟就执行 `adb shell dumpsys battery reset` 并确认硬件更新恢复。真机模拟 0% 曾直接关机，零电量显示改用合成数据验证。
- 真实歌单写入只用经用户同意的临时私有歌单，先记下初始状态，写完从官方客户端重新打开核对名称、简介、曲序和移除结果，最后删掉临时歌单并确认原有歌单未变。历史操作见[横屏与歌单验证记录](docs/VERIFICATION-landscape-playlist-editor.md)。
- 汇报时写明实际跑了哪几层、哪个版本、哪些没覆盖。

## 公开仓库边界

公共流程只依赖仓库内脚本和可配置的工具路径；`.local/` 放本机工具和私有证据，已被忽略。公开文档、测试夹具和提交只用合成账号、相对路径和脱敏结果。签名密钥、Cookie、带授权参数的播放地址、设备标识、原始日志和备份都留在本地。
