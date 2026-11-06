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
- 仓库为 `github.com/NZESupB/Music2KTV`（public），CI 见 `.github/workflows/android-build.yml`，push/PR 到 `main` 触发。
- release 签名只在检测到 `ANDROID_KEYSTORE_PATH` 环境变量时才生效（见 `app/build.gradle.kts`）；无该变量时 release 构建为未签名但不报错，本地开发不必准备 keystore。
- 签名四要素存于 GitHub Secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。keystore 为 PKCS12 格式，其 key 密码与 store 密码在 PKCS12 下强制相同，两项 Secret 取值一致属预期行为，不是配置错误。
- 本地 keystore 位于 `~/.android-keys/music2ktv-release.jks`（仓库外）。**该文件丢失将无法对已安装应用做覆盖升级，必须异地备份**；Secrets 只可写不可读回。
