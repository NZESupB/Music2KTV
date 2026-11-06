# 架构与技术约束

## 模块
| 模块 | 类型 | 职责 |
|---|---|---|
| `:engine` | Kotlin/JVM | 标签解析（`tags`）、歌词解析/匹配/染色进度（`lyrics`）、消人声与频谱 DSP（`dsp`）、拼音（`text`）、KTV 模板模型与 JSON（`template`）、随机读抽象（`io`） |
| `:data` | Android library，api 依赖 engine | 数据源（Local/USB、WebDAV、SMB）、Room、WorkManager 扫描、Media3 播放服务与 AudioProcessor、歌词 Provider、局域网 HTTP 服务（Phase 2+ 实现） |
| `:app` | Android application | Compose for TV UI、导航、手写 AppContainer DI |

包名根：`com.music2ktv`；engine 为 `com.music2ktv.engine.*`。

## 构建与版本
- Gradle 8.14（wrapper）、AGP 8.13.2、Kotlin 2.3.20、KSP 2.3.13、JDK 17。
- minSdk 24 / targetSdk 35 / compileSdk 36；ABI 仅 `armeabi-v7a`、`arm64-v8a`。
- 版本集中在 `gradle/libs.versions.toml`。androidx 版本被 compileSdk 36 + AGP 8.13 限制（见 index 记忆）。
- 质量门禁：`./gradlew ktlintCheck lint testDebugUnitTest :engine:test assembleDebug`。

## engine 关键 API
- `io.RandomAccessSource`：`length` + `read(position, buf, off, len)`；`CachedSource` 按 64KB 块缓存，远程源的零散小读会合并。
- `tags.TagReader.read(source, fileName)`：按魔数识别 FLAC（含前置 ID3）/MP3；ID3v2.2–2.4（synchsafe、v2.3 整体反同步、v2.4 帧级反同步/数据长度指示）、USLT、SYLT（毫秒时间戳）、APIC 仅记偏移；FLAC STREAMINFO/VORBIS_COMMENT/PICTURE。legacy 编码按「ASCII → 严格 UTF-8 → GB18030」识别。MP3 时长：TLEN → Xing/Info/VBRI → CBR 估算。缺失标题/歌手回退文件名 `歌手 - 歌名`。
- `TagReader.readCover(source, CoverRef)`：按需读取封面。
- 健壮性约定：解析器对截断/恶意文件不抛异常、只返回部分结果；所有长度字段先校验非负且不超过剩余字节；文本帧上限 4MiB、整标签反同步上限 32MiB；`CachedSource.readUpTo` 按实际读到的数据增长，不按声明长度预分配。新增解析逻辑须在 `RobustnessTest` 补用例。
- `lyrics.LrcParser.parse(text)`：标准 LRC（多时间戳、`[offset:]` 正值提前、1–3 位小数）、增强 LRC `<mm:ss.xx>` 逐字、网易云 YRC；无时间戳 → `synced=false`。`fromTimedSegments` 处理 SYLT（片段以换行开头为逐字模式，否则一片段一行）。行结束时间 = 下一行开始，最长 `MAX_LINE_MS`。
- `Lyrics.indexAt(pos)` 二分查当前行；`withTranslation()` 按时间（±1s）合并翻译。
- `KaraokeProgress.sungChars(line, pos)`：逐字精确；无逐字时按行时长线性估算。UI 用它乘字符宽度做 clipRect 染色。
- `lyrics.LyricMatcher`：NFKC + 去括号/feat/版本尾缀；标题 0.5、歌手 0.3、时长 0.2（≤3s 满分，≥15s 0 分），缺失维度按比例重分配；时长差 >20s 减半；版本标记（Live/伴奏…）不一致 ×0.9；拼音比对兼容繁简。阈值 0.6，`rank()` 用于「换一个歌词」。
- `dsp.VocalCutDsp`：`L/R -= BP150–6000Hz((L+R)/2) × mix`，30ms 线性淡入淡出；关闭且 mix=0 时逐位直通；单声道直通。`processPcm16` 供 PCM16 链路。
- `dsp.SpectrumAnalyzer`：汉宁窗 FFT，60–16kHz 对数 32 段，dB 归一化 0..1，快升慢降。
- `template.KtvTemplate` + `TemplateCodec`（kotlinx.serialization，`ignoreUnknownKeys`，带校验与可读错误）+ `BuiltinTemplates`（经典 KTV / 滚动歌词 / 霓虹频谱）。

## 子系统设计（待实施部分以计划为准）
- 数据源：统一 `SourceFs`（list / openDataSource / RandomAccessSource）。WebDAV 用 OkHttp 自写 PROPFIND（逐级 Depth:1）；SMB 用 smbj；本地/U 盘用 StorageManager + 文件路径（不依赖 SAF）。凭据用 Keystore AES-GCM。
- 播放：Media3 `MediaSessionService` + ExoPlayer；AudioProcessor 链 VocalCut → Tee(SpectrumAnalyzer)。ffmpeg 解码扩展需与 media3 版本对齐（jellyfin 1.9.0+1 对应 media3 1.9.x，Phase 3 决定是否下调 media3）。
- 歌词 Provider 链：内嵌 SYLT/带时间 USLT → 网易云(yrc/lrc/tlyric) → QQ(lrc) → LRCLIB → 内嵌纯文本；结果与偏移按歌曲缓存于 Room。非官方接口失败只记日志并降级到下一来源。
- 局域网：NanoHTTPD + assets 网页 + 随机 token；所有点歌/切歌写同一 QueueRepo。
- 麦克风：HDMI/ARC 不回传话筒信号；仅 USB/蓝牙输入可采集，返听为默认关闭的实验功能。
