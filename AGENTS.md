# Music2KTV 代理须知

开始任何任务前先读 `.agentdocs/index.md`，按索引阅读相关文档。

## 构建环境
- 必须设置：`export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- `local.properties` 中 `sdk.dir` 指向本机 Android SDK（不入库）。

## 提交前验证（新增或变更功能必须全部通过）
```bash
./gradlew ktlintCheck lint testDebugUnitTest :engine:test assembleDebug
```
- 格式问题先运行 `./gradlew ktlintFormat`。
- 纯逻辑放 `:engine` 并补 JUnit 单测；engine 单测作为回归测试保留。
- Android 侧（数据源、播放、UI）需要设备时，用 `adb shell input keyevent` 走 D-pad 流程验证；临时脚本与样例音频放会话 scratch 目录，验证后清理。
