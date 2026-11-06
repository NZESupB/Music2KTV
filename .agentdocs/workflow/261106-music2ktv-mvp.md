# 261106 Music2KTV MVP

## 目标
Android TV 应用：从本地/U 盘、WebDAV、SMB 导入 mp3/flac，KTV 模板化播放（逐字染色歌词、背景动画、简易消人声），遥控器 + 手机局域网点歌。完整方案见 `.pi/plan/` 下已批准计划。

## 已确认决策
- 人声：原唱 + 实时中置抵消（可开关）；不做 AI 分离、不做评分。
- 歌词来源：内嵌 USLT/SYLT/FLAC LYRICS、网易云/QQ 非官方接口、LRCLIB。
- minSdk 24；设计稿确认后再写 UI。
- compileSdk 由计划的 35 调整为 36（targetSdk 仍 35）：compose/activity/core 新版要求 ≥36，运行时行为不变。
- 拼音库由 TinyPinyin 改为 pinyin4j（前者不在 Maven Central）。

## 阶段与 TODO
- [x] Phase 0：AI 概念图（4/6 成功）、交互式线框图、.agentdocs、AGENTS.md
- [ ] **关卡：用户确认视觉方向**；待确认 ① 是否下载 Android TV 模拟器镜像 ② engine 单测是否保留（当前按保留处理）
- [x] Phase 1（部分）：Gradle 骨架、3 模块、ktlint/lint、占位 Activity、`assembleDebug` 通过
- [ ] Phase 1（剩余）：TV 主题、焦点规范组件、导航框架（UI，待关卡）
- [x] engine 纯逻辑（为 Phase 2–5 预先完成）：标签解析、LRC/增强 LRC/YRC/SYLT、匹配评分、VocalCut、频谱、拼音、模板模型；62 个单测（含 10 个损坏文件健壮性测试，经独立审查后修复 8 处边界缺陷）
- [ ] Phase 2：SourceFs（Local/USB、WebDAV PROPFIND、SMB）、ScanWorker、Room、点歌/数据源页
- [ ] Phase 3：MediaSessionService、DataSource 工厂、ffmpeg 回退（与 media3 版本对齐）、VocalCut/Tee AudioProcessor、队列
- [ ] Phase 4：歌词 Provider（网易云/QQ/LRCLIB 现场核验接口）、Room 缓存、偏移、换歌词
- [ ] Phase 5：KTV 渲染器（TextMeasurer + clipRect）、3 套模板、快捷面板、模板编辑器
- [ ] Phase 6：NanoHTTPD、手机网页、二维码、扫码添加数据源、模板导入导出
- [ ] Phase 7：麦克风设备检测与引导、实验性返听
- [ ] Phase 8：性能、release APK、文档归档

## 验证状态
- `./gradlew ktlintCheck lint testDebugUnitTest :engine:test assembleDebug`：通过（engine 62 测试 0 失败）。
- 未做：真机/模拟器运行（无 TV 镜像、无连接设备）。
