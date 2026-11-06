# Music2KTV 实施方案

## 0. 已确认需求与环境

| 项 | 结论 |
|---|---|
| 人声 | 原唱为主，加一个可开关的**实时简易消人声**（中置声道抵消） |
| 麦克风 | 外接设备可能存在，作为**实验性**功能（见 §6 可行性说明） |
| 点歌 | 遥控器点歌，加**手机扫码局域网点歌**（多用户排队、置顶、切歌） |
| 来源 | 本地/U 盘、WebDAV（覆盖 Alist、坚果云、群晖、NAS）、SMB |
| 歌词 | 内嵌标签（ID3 USLT/SYLT、FLAC LYRICS）、LRCLIB、网易云/QQ 非官方接口 |
| 系统 | minSdk 24，target/compile 35，横屏 1080p，D-pad 交互 |
| 设计稿 | 出 AI 概念图和 HTML 高保真线框图 |
| 环境 | 工作区为空。本机有 SDK 34–36、build-tools 36、Gradle 8.14 缓存、Homebrew openjdk@17（需显式设置 `JAVA_HOME`）。目前没有 TV 系统镜像，也没有已连接设备 |

## 1. 整体架构

```
┌──────────────────────── :app (Compose for TV) ────────────────────────┐
│ 首页 │ 点歌(首字母搜索/歌手/文件夹) │ 已点队列 │ KTV播放 │ 模板编辑 │ 数据源 │ 设置 │
│                 ViewModel + StateFlow，手写 AppContainer 做 DI         │
├──────────────────────────── :data (Android lib) ──────────────────────┤
│ SourceRepo   LibraryRepo(Room)   ScanWorker(WorkManager)   QueueRepo  │
│ Sources: Local/USB · WebDAV(OkHttp) · SMB(smbj) → 统一 SourceFs 接口   │
│ Player: MediaSessionService + ExoPlayer + 自定义 DataSource           │
│         AudioProcessor 链：VocalCut → Tee(FFT 频谱)                    │
│ Lyrics: Provider 链(Embedded→NetEase→QQ→LRCLIB) + Room 缓存 + 偏移     │
│ LanServer: NanoHTTPD + assets 手机网页 + Token                        │
├──────────────────────── :engine (纯 Kotlin/JVM) ──────────────────────┤
│ 标签解析(ID3v2.3/2.4、FLAC 元数据块)  LRC/增强LRC/YRC 解析  歌词匹配评分 │
│ 中置声道 DSP 算法  拼音首字母   ← 不依赖 Android，可直接跑 JUnit         │
└────────────────────────────────────────────────────────────────────────┘
```

只拆 3 个模块：`engine` 放纯逻辑，便于单测；`data` 放 Android 的 IO 和播放；`app` 放 UI。更细的拆分在当前阶段属于过度设计。

## 2. 关键库与 API

实施时锁定可解析的最新稳定版。

| 领域 | 选型 | 说明 |
|---|---|---|
| UI | Jetpack Compose + `androidx.tv:tv-material`、Navigation Compose | 用 TV 焦点体系，不用 Leanback |
| 播放 | Media3 ExoPlayer + `media3-session` | MediaSession 负责响应遥控器媒体键 |
| 解码 | `org.jellyfin.media3:media3-ffmpeg-decoder` | Android 8.1 以下没有系统 FLAC 解码器，ffmpeg 扩展同时补齐 ape/alac 等格式；只在缺少平台解码器时启用 |
| WebDAV | OkHttp + `media3-datasource-okhttp`，自写 PROPFIND（XmlPullParser） | 约 150 行，少一个 jitpack 依赖；Basic/Digest 认证；Range 拖动 |
| SMB | `com.hierynomus:smbj` | 支持 SMB2/3；为它写一个支持随机读的 `SmbDataSource` |
| 本地/U 盘 | StorageManager 卷监听 + MediaStore 扫描 + File 路径 | 很多 TV ROM 没有 SAF 选择器，不能依赖 `OPEN_DOCUMENT_TREE`；U 盘插拔监听 `ACTION_MEDIA_MOUNTED` |
| 数据库 | Room（KSP）、WorkManager | 曲库、歌词缓存、队列、模板、数据源 |
| 凭据 | Android Keystore AES-GCM 自封装 | security-crypto 已弃用，不使用 |
| 图片 | Coil 3 | 封面；模糊背景预计算一次，因为 RenderEffect 需要 API 31 |
| 搜索 | TinyPinyin | 遥控器输入中文很困难，KTV 标配首字母搜索 |
| 局域网 | NanoHTTPD 2.3.1 + ZXing core（二维码） | 兼容 API 24 且体积小；手机端用原生 HTML/JS（assets），状态用 2s 轮询或 SSE |
| 质量 | ktlint（Gradle 插件）、Android Lint、JUnit4 | — |
| ABI | `armeabi-v7a` + `arm64-v8a` | 国内盒子常见 32 位用户态 |

## 3. 模块实现思路

### 3.1 音乐导入与曲库
- **统一接口** `SourceFs`：`list(dir)`、`openDataSource(path)`、`readRange(path, off, len)`。三种来源都实现它，扫描、标签读取和播放共用同一套接口。
- **数据源配置**：在 TV 上可以填写，也可以**手机扫码填写**（局域网页面），避免用遥控器输入 URL 和密码。
- **扫描**：WorkManager 按来源递归扫描。WebDAV 逐级发 `Depth: 1`，因为很多服务禁用 `infinity`。增量判断依据 `path + size + mtime`。过滤 `.mp3`、`.flac`，扩展点保留 m4a、ape、wav。
- **元数据**：用自写的轻量解析器对**文件头做 Range 读取**。远程文件不需要整首下载，先读标签头拿到大小，再按需读取，APIC 大封面跳过或延迟读。
  - ID3v2.3/2.4：TIT2/TPE1/TALB/TLEN/USLT/SYLT/APIC，处理 synchsafe 和编码字节。
  - FLAC：STREAMINFO 时长、VORBIS_COMMENT（TITLE/ARTIST/LYRICS/UNSYNCEDLYRICS）、PICTURE。
  - 解析失败时回退到文件名规则 `歌手 - 歌名`。
- **Room 表**：`source`、`song`（含 `pinyinInitials`、`lyricsStatus`）、`lyrics`（provider、format、content、offsetMs）、`queue_item`（singer 昵称、来源 TV 或手机、排序）、`template`。

### 3.2 播放引擎
- 在 `MediaSessionService` 中持有 ExoPlayer。用 `DefaultDataSource` 加按 URI scheme 分发的 DataSource 工厂，支持 `smb://`、`webdav(s)://` 和本地文件。
- **消人声 `VocalCutAudioProcessor`**（Media3 `BaseAudioProcessor`）：
  - 在 2× 下采样频段中，对约 150Hz–6kHz 做 `S = (L−R)/2` 抵消。
  - 低频和高频保留 `(L+R)/2`，减轻纯 L−R 吃掉贝斯和底鼓的问题。
  - 支持 PCM16 和 float，单声道直通，切换时淡入淡出约 30ms 防爆音。
  - 核心滤波和混合算法放在 `engine`，可单测。
- **频谱**：用 `TeeAudioProcessor` 取 PCM，在 engine 中做 FFT 后输出 32 段能量，供背景动画使用。不需要 Visualizer 的录音权限。
- **队列**：QueueRepo 是唯一数据源。遥控器和手机都改它，播放器监听队列推进。

### 3.3 歌词获取与同步
- **Provider 链**（按优先级，可在设置中开关和排序）：
  1. 内嵌 SYLT（自带时间轴）或带时间戳的 USLT/LYRICS 文本
  2. 网易云：优先取 yrc（逐字），其次 lrc，附带 tlyric 翻译
  3. QQ 音乐：LRC（QRC 加密格式暂不做）
  4. LRCLIB `/api/get` → `/api/search`
  5. 内嵌纯文本（无时间轴）：只做静态滚动显示
- **匹配评分**：标题和歌手规范化（全/半角、去掉括号里的 Live/伴奏/feat、繁简统一），再按标题相似度、歌手相似度和**时长差 ≤3s** 计算加权分，低于阈值视为未命中。
- **非官方接口风险**：网易云/QQ 接口单独封装，失败只记日志，并跳到下一个来源；设置中默认开启，可关闭。实施时现场核验接口的当前可用性。
- **缓存与纠错**：结果写入 Room，曲目相同时不再请求。播放时可在快捷面板中执行：
  - 「换一个歌词」：轮换候选
  - 「歌词偏移 ±100ms」：按歌曲持久化
- **同步渲染**：用 `withFrameNanos` 每帧插值播放位置（上次 `currentPosition` 加单调时钟增量），避免 TV 端 position 更新粒度粗导致卡顿。
  - 有逐字时间时，按字精确染色。
  - 只有行级时间时，按字符宽度在本行时长内**线性估算**染色。

### 3.4 KTV 模板与播放界面
- **模板 = JSON 数据**（`KtvTemplate`）：
  - 背景：渐变动画、粒子、模糊封面、自定义图片
  - 歌词布局：经典双行交替或居中多行滚动
  - 字体字号、未唱色和已唱色、描边、发光
  - 频谱条、歌曲信息位置、下一首提示
- **内置 3 套模板**：经典 KTV、滚动歌词、霓虹频谱。
- **模板编辑器**：左侧属性列表（遥控器上下选择、左右改值），右侧实时预览。模板可以复制和重置，通过局域网页面导入导出 JSON。
- **染色实现**：每行用 `TextMeasurer` 测量，绘制两层文字。底层是未唱色，上层用 `clipRect` 按进度裁切成已唱色。可选第二行显示翻译。
- **遥控器键位**（播放页）：

  | 键 | 功能 |
  |---|---|
  | OK | 呼出或隐藏控制条 |
  | 左/右 | ±5s |
  | Menu 或长按 OK | 快捷面板（原唱/消人声、模板、歌词偏移、换歌词） |
  | 媒体下一首 / 频道+ | 切歌 |
  | 返回 | 确认退出 |

- **大屏规范**：1920×1080 设计基准，四周 48dp 安全区，焦点项放大并带描边高亮，单屏不超过 2 层焦点区域。

经典模板示意（正式设计稿在 Phase 0 产出）：
```
┌──────────────────────────────────────────────────────────────┐
│ ♪ 后来 - 刘若英            [消人声 ON]      下一首: 晴天 (小明) │
│                                                                │
│              (动态背景：模糊封面 + 粒子 / 频谱)                  │
│                                                                │
│   ████后来█我总算学会了   ← 已唱部分高亮渐变染色                 │
│                      如何去爱                                  │
│ ▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬ 02:13 / 05:41      │
│ [扫码点歌 QR]                                                  │
└──────────────────────────────────────────────────────────────┘
```

### 3.5 局域网点歌
- 前台 Service 运行 NanoHTTPD，使用固定端口加冲突回退。IP 取 Wi-Fi 或以太网的 LinkProperties。二维码 URL 带随机 token，防止局域网内任意访问。
- **REST 接口**：
  - `GET /api/songs?q=`（支持拼音首字母）
  - `GET/POST /api/queue`、`POST /api/queue/{id}/top`、`DELETE /api/queue/{id}`
  - `POST /api/control/{next|pause|vocal}`
  - `POST /api/sources`（扫码添加数据源）
  - `GET/PUT /api/templates`
- **手机页面**：搜索、点歌、已点列表、昵称（存 localStorage），面向移动端做适配。

### 3.6 外接麦克风（实验性，最后阶段）
- **可行性结论**：HDMI（含 ARC/eARC）的音频方向是电视→功放，**接在音响上的话筒信号不会回传给 Android**，App 采集不到。这种接法由音箱硬件混音，延迟最低，本身就是最佳方案，App 不需要处理。
- App 能采集的只有 USB 麦克风/USB 声卡、蓝牙麦克风（走 SCO，8/16kHz、延迟高，不推荐）。电视盒子软件返听的延迟通常在 50–200ms 以上，唱歌会明显错位。
- **本阶段只做**：通过 `AudioManager.getDevices(GET_DEVICES_INPUTS)` 检测设备，显示接线指引（推荐「话筒→K 歌音箱」）。返听加混响作为可选实验开关（Oboe 低延迟），默认关闭。清单中声明 `microphone required=false`，运行时申请 RECORD_AUDIO。

## 4. 实施阶段与 TODO

- **Phase 0：设计稿与文档**
  - 用 imagegen 生成 6 张 16:9 概念图：首页、点歌、经典 KTV、霓虹 KTV、模板编辑器、手机点歌页。
  - 在可视化目录输出 HTML 高保真线框图（1920×1080 等比缩放，标注焦点流）。
  - 初始化 `.agentdocs/index.md`、`architecture.md`、`ui-design.md` 和任务文档 `workflow/YYMMDD-music2ktv-mvp.md`。在根目录 `AGENTS.md` 写入构建和测试要求。
  - **出完设计稿后暂停，等你确认视觉方向，再开始 UI 编码**。
- **Phase 1：工程骨架**。Gradle（version catalog、JDK17）、3 个模块、TV 主题与焦点规范、导航框架、ktlint/lint，`assembleDebug` 通过。
- **Phase 2：数据源与曲库**。三种 SourceFs、PROPFIND、smbj、U 盘监听、ScanWorker、标签解析、Room、拼音搜索，以及点歌页和数据源页。
- **Phase 3：播放引擎**。MediaSessionService、DataSource 工厂、ffmpeg 回退解码、VocalCut、频谱 Tee、队列。
- **Phase 4：歌词**。LRC/增强 LRC/YRC 解析、4 个 Provider、匹配评分、缓存、偏移和换歌词。
- **Phase 5：KTV 播放页与模板**。3 套内置模板、渲染器、快捷面板、模板编辑器。
- **Phase 6：局域网点歌**。HTTP 服务、手机网页、二维码、扫码添加数据源、模板导入导出。
- **Phase 7：外接麦克风（实验）**。设备检测、引导页、可选返听。
- **Phase 8：联调与发布**。低端设备性能（掉帧、内存）、release APK（v7a/arm64）、文档归档。

## 5. 验证方案
- **每阶段运行**：`./gradlew ktlintCheck lint testDebugUnitTest assembleDebug`。
- **engine 单元测试**：
  - ID3v2.3/2.4（synchsafe、UTF-16、SYLT）、FLAC Vorbis 注释
  - LRC 边界：多时间戳、`[offset:]`、空行、毫秒 2/3 位；增强 LRC、YRC
  - 匹配评分、消人声 DSP 数值、拼音首字母
- **集成验证**：
  - TV 模拟器：需要下载 `system-images;android-34;android-tv;arm64-v8a`，属于修改环境，执行前会再请你确认。不下载的话，就用现有 android-35 镜像，按 1080p 横屏验证，或连接真机。
  - 用 `adb shell input keyevent` 走 D-pad 全流程。
  - WebDAV：本地起临时服务（`rclone serve webdav` 或 `wsgidav`，用完清理）。
  - SMB：macOS 文件共享，**需要你配合开启，或提供 NAS**。
  - 测试音频：如果本机有 ffmpeg 就生成 mp3/flac 样例，否则请你提供。
- 临时脚本和样例在验证后清理。engine 单元测试默认作为回归测试保留（如果按全局规则需要移除，请告知）。

## 6. 暂不纳入（低成本，可后续加）
- 升降调：Media3 `PlaybackParameters(pitch)` 几乎零成本
- 同目录同名 `.lrc`、手动导入歌词
- QQ QRC 逐字歌词
- 端侧 AI 人声分离
- 演唱评分：普通音频没有旋律参考，只能做粗略评分
