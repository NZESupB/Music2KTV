# Music2KTV 代理文档索引

## 架构文档
`architecture.md` - 模块划分、依赖版本约束、各子系统设计（数据源/播放/歌词/模板/局域网），修改任何代码前必读
`ui-design.md` - TV 视觉规范、焦点与遥控器交互、各页面布局与设计稿位置，修改界面时必读

## 当前任务文档
`workflow/261106-music2ktv-mvp.md` - MVP 分阶段实施计划与进度（Phase 0–8）

## 全局重要记忆
- 构建需显式 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`（系统无默认 JDK）。
- compileSdk 固定 36、AGP 8.13.x：androidx 最新版（core 1.19+、activity 1.13+、compose-bom 2026.06+）要求 compileSdk 37 + AGP 9.1，升级前必须整体评估，不要单独升某个库。
- 纯逻辑（解析、匹配、DSP、模板模型）一律放 `:engine`（纯 JVM，可直接 JUnit），不要放进 Android 模块。
- 标签解析统一走 `TagReader` + `RandomAccessSource`；远程源只需实现 `RandomAccessSource`，禁止为某个来源单独写解析逻辑。
- 歌词统一解析为 `Lyrics`（`LrcParser`），候选匹配统一用 `LyricMatcher`，UI 染色进度统一用 `KaraokeProgress`。
- 拼音使用 pinyin4j（TinyPinyin 不在 Maven Central，已弃用该方案）。
