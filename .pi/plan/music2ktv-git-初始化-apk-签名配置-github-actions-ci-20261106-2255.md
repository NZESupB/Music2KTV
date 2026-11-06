## 目标

为 Music2KTV Android TV 项目建立版本控制基线、APK 签名能力和自动构建流水线，使每次 push/PR 都能产出可安装的已签名 APK。

## 现状核实（已确认的事实）

| 项 | 状态 |
|---|---|
| Git 仓库 | **未初始化**（`fatal: not a git repository`） |
| 现有 `.gitignore` | 10 行，仅覆盖 `.gradle/ .kotlin/ build/ local.properties .idea/ *.iml .DS_Store captures/ .externalNativeBuild/ .cxx/` |
| `gh` CLI | 已登录 `NZESupB`，scope 含 `repo`、`workflow`，足以建库 + 写 Secrets |
| 构建体系 | Gradle 8.14 wrapper / AGP 8.13.2 / Kotlin 2.3.20 / JDK 17 / compileSdk 36 / minSdk 24 |
| 签名配置 | [app/build.gradle.kts](/Users/nzesupb/VSCode/AndroidTV/Music2KTV/app/build.gradle.kts) **无 `signingConfigs`**，release 完全未签名 → 必须改动 |
| keystore | 项目内**不存在**任何 `.jks`/`.keystore` |
| `local.properties` | 已 gitignore（含本机 SDK 绝对路径，不可入库） |
| 已有产物 | 仅 `app/build/outputs/apk/debug/app-debug.apk` |
| GitHub 远端 | **无 remote**，`NZESupB` 账号下无同名仓库 |

## 需要你确认的三件事

1. **远端仓库**：新建 `NZESupB/Music2KTV`（建议 **private**），还是推送到已有仓库？请给出 `owner/repo`。
2. **keystore**：你已有 `.jks` 文件，还是需要我用 `keytool` 生成一个？
3. **密码传递方式**：我计划在本地用**交互式提示**（不回显、不进命令行参数、不落盘）收集密码并直接灌入 Secrets。是否同意？

未确认前我不会执行任何写操作。以下为确认后的完整实施方案。

---

## 第 1 步：初始化 Git 仓库

### 作用
建立版本控制基线，并把不应入库的构建产物、本机配置、签名材料全部排除，避免把 42MB 的 `app/build` 和 keystore 提交上去。

### 命令

```bash
cd /Users/nzesupb/VSCode/AndroidTV/Music2KTV

# 初始化（默认分支用 main，与 GitHub 现状一致）
git init -b main

# 仓库级身份（已核实 global 为 NZESupB / szboyxjw@gmail.com，此处仅为确定性，可跳过）
git config user.name  "NZESupB"
git config user.email "szboyxjw@gmail.com"
```

### 完整的 `.gitignore`

现有 10 行基础上补齐 Android 项目遗漏项。**保留原有条目不动**，按语义分组重写为：

```gitignore
# Gradle / 构建产物
.gradle/
build/
!gradle/wrapper/gradle-wrapper.jar
.kotlin/
captures/
.externalNativeBuild/
.cxx/

# 本机配置（含 SDK 绝对路径，禁止入库）
local.properties

# IDE
.idea/
*.iml
*.hprof

# 签名材料与凭据 —— 绝不可入库
*.jks
*.keystore
*.p12
keystore.properties
signing.properties

# APK / AAB 产物
*.apk
*.aab
*.ap_
output-metadata.json

# 操作系统
.DS_Store
Thumbs.db

# 代理/会话临时文件
.agents/skills/.skill-learning/queue/
.agents/skills/.skill-learning/*.json
.agents/skills/.skill-learning/enqueue.log
```

> 说明：`.agents/skills/.skill-learning/` 下的 `usage.json`、`enqueue.log`、`queue/*.json` 是本机技能学习工具的运行时日志（实测 6 个文件），不属于项目内容，排除以免污染仓库。`!gradle/wrapper/gradle-wrapper.jar` 用于抵消 `*.jks` 之外可能的误伤，同时确保 wrapper jar 一定入库（CI 依赖它）。

### 首次提交

```bash
git add -A
git status   # 人工核对：确认无 *.jks、local.properties、app/build/ 被暂存
git commit -m "chore: 初始化仓库并补充 Android 项目 .gitignore"
```

### 验证
- `git log --oneline` 有 1 条提交
- `git ls-files | grep -E "local.properties|\.jks|build/"` **无输出**
- `git ls-files | wc -l` 约 50+ 个源文件

---

## 第 2 步：配置 APK 签名（GitHub Secrets）

### 作用
把签名四要素存入 GitHub 加密 Secrets，CI 在 runner 上临时还原 keystore 完成签名。**本地与仓库中都不留任何明文密钥。**

### 2a. 准备 keystore（仅当尚无 `.jks` 时）

> 强烈建议 keystore 存放于**项目目录之外**（如 `~/.android-keys/music2ktv.jks`），避免任何误提交风险。

```bash
mkdir -p ~/.android-keys
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
$JAVA_HOME/bin/keytool -genkeypair \
  -keystore ~/.android-keys/music2ktv-release.jks \
  -alias music2ktv \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` 会交互式询问 store 密码、key 密码和证书主体信息（CN/OU/O/L/ST/C），全程不回显、不进 shell 历史。

### 2b. 写入 Secrets 的命令

```bash
cd /Users/nzesupb/VSCode/AndroidTV/Music2KTV
REPO="NZESupB/Music2KTV"   # ← 按实际仓库替换

# 1) keystore 内容以 base64 单行写入（macOS 用 -i ''）
base64 -i ~/.android-keys/music2ktv-release.jks | tr -d '\n' | \
  gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"

# 2) 以下三项用交互式 stdin，密码不回显、不留痕
gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO" < /dev/stdin
gh secret set ANDROID_KEY_ALIAS          --repo "$REPO" < /dev/stdin
gh secret set ANDROID_KEY_PASSWORD       --repo "$REPO" < /dev/stdin
```

> 执行时由你在终端直接键入值。**不要**写成 `gh secret set X --body "明文"`——那会进入 shell 历史。

### 2c. 验证

```bash
gh secret list --repo "$REPO"
```

预期看到 4 条：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。

---

## 第 3 步：Gradle 签名配置

### 作用
让 `release` 构建在**有环境变量时签名、无环境变量时跳过**。这样本地 `assembleDebug` 与 CI `assembleRelease` 都能正常工作，且不会因为没有 keystore 而让本地构建失败。

### 修改 [app/build.gradle.kts](/Users/nzesupb/VSCode/AndroidTV/Music2KTV/app/build.gradle.kts)

在 `android { }` 块内、`defaultConfig` 之前插入 `signingConfigs`，并让 `release` 引用它：

```kotlin
android {
    namespace = "com.music2ktv"
    compileSdk = 36

    signingConfigs {
        // 仅在 CI（或显式提供环境变量）时生效，本地无 keystore 不报错
        val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    defaultConfig { /* 保持不变 */ }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 有签名配置才绑定，否则保持未签名（本地 CI 之外的场景）
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    // 其余保持不变
}
```

> **设计说明**：用 `ANDROID_KEYSTORE_PATH`（文件路径）而非 base64 直接传，是因为 Kotlin DSL 里解 base64 需要额外 IO，交给 workflow 的 shell 步骤还原更直观、更易排错，也让本地复现 CI 只需导出同样的环境变量。

### 验证（本地，模拟 CI）

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_KEYSTORE_PATH=$HOME/.android-keys/music2ktv-release.jks \
ANDROID_KEYSTORE_PASSWORD=*** ANDROID_KEY_ALIAS=music2ktv ANDROID_KEY_PASSWORD=*** \
  ./gradlew :app:assembleRelease

# 确认签名生效
$JAVA_HOME/bin/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## 第 4 步：GitHub Actions Workflow

### 作用
在 push 与 PR 上自动跑质量门禁 + 构建签名 APK，产出 Artifact 供下载安装。

### 新建 `.github/workflows/android-build.yml`

```yaml
name: Android Build

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    name: Build Signed APK
    runs-on: ubuntu-latest
    timeout-minutes: 40

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Restore keystore
        # PR 来自 fork 时 Secrets 不可用，此步自动跳过
        if: ${{ secrets.ANDROID_KEYSTORE_BASE64 != '' }}
        env:
          ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
        run: |
          set -euo pipefail
          KEYSTORE_PATH="$RUNNER_TEMP/release.jks"
          printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$KEYSTORE_PATH"
          # 校验确实是 JKS
          keytool -list -keystore "$KEYSTORE_PATH" \
            -storepass "${{ secrets.ANDROID_KEYSTORE_PASSWORD }}" > /dev/null
          echo "ANDROID_KEYSTORE_PATH=$KEYSTORE_PATH" >> "$GITHUB_ENV"

      - name: Quality gates
        run: ./gradlew ktlintCheck lint testDebugUnitTest :engine:test --stacktrace

      - name: Build release APK
        env:
          ANDROID_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          ANDROID_KEY_ALIAS:         ${{ secrets.ANDROID_KEY_ALIAS }}
          ANDROID_KEY_PASSWORD:      ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: ./gradlew :app:assembleRelease --stacktrace

      - name: Verify APK signature
        run: |
          set -euo pipefail
          APK=$(find app/build/outputs/apk/release -name '*.apk' | head -1)
          echo "APK: $APK"
          "$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -1)/apksigner" \
            verify --print-certs "$APK"

      - name: Upload signed APK
        uses: actions/upload-artifact@v4
        with:
          name: music2ktv-release-${{ github.sha }}
          path: app/build/outputs/apk/release/*.apk
          if-no-files-found: error
          retention-days: 30

      - name: Upload test reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports-${{ github.sha }}
          path: |
            **/build/reports/
            **/build/test-results/
          retention-days: 7
```

### 关键设计点说明

| 设计 | 原因 |
|---|---|
| `if: secrets.X != ''` | fork PR 拿不到 Secrets，硬跑会失败；跳过则产出未签名 APK 仍可验证构建 |
| `keytool -list` 预校验 | base64 还原出错时在早阶段暴露，不用等到 `assembleRelease` 才报签名错误 |
| `setup-gradle@v4` | 官方缓存 action，比手写 `actions/cache` 更贴合 Gradle 8.14 + configuration-cache |
| `verify APK signature` | 主动校验签名，避免"构建绿了但产物没签名"的假成功 |
| `if-no-files-found: error` | 产物缺失即失败，防止空 Artifact |
| Quality gates 与 build 分离 | 失败时能一眼看出是测试挂了还是构建挂了 |

> **注**：workflow 中缺少 `:engine:test` 之外的 `assembleDebug`，因为 AGENTS.md 的门禁命令是为本地开发设的；CI 产出目标是 release APK，跑 debug 构建纯属浪费。若你希望 CI 完全对齐 AGENTS.md 门禁，我可以把 `assembleDebug` 加回质量门禁步骤。

---

## 第 5 步：推送与验证

```bash
# 创建远端并推送（private 仓库，含签名流程建议先私有）
gh repo create NZESupB/Music2KTV --private --source=. --remote=origin --push

# 观察 workflow
gh run watch
gh run list --limit 3
```

### 端到端验证清单

- [ ] `gh secret list` 显示 4 条 Secret
- [ ] Actions 页面出现 "Android Build" 运行且**成功**
- [ ] "Verify APK signature" 步骤输出证书指纹（含 `CN=...`）
- [ ] Artifact `music2ktv-release-<sha>` 可下载，解压得到 `app-release.apk`
- [ ] `adb install -r app-release.apk` 或 `keytool`/`apksigner verify` 本地复验通过
- [ ] 故意推一个 fail 的 PR，确认 Quality gates 能红

---

## 涉及文件汇总

| 文件 | 操作 |
|---|---|
| `.gitignore` | 修改（10 行 → 分组重写，补签名/产物/插件日志规则） |
| `app/build.gradle.kts` | 修改（新增 `signingConfigs`，`release` 绑定配置） |
| `.github/workflows/android-build.yml` | 新建 |
| `~/.android-keys/music2ktv-release.jks` | 新建（仓库**之外**） |
| Git 仓库 | `git init` + 首次提交 |

**不修改**：`local.properties`、`gradle.properties`、`settings.gradle.kts`、`data/`、`engine/`、`.agentdocs/` 内容。

---

## 风险与注意事项

1. **keystore 丢失 = 无法覆盖升级**。生成的 `.jks` 必须另外备份（密码管理器）。Secrets 只有写入无法读回，务必本地留档。
2. **`applicationId` 现为 `com.music2ktv`**，与 `namespace` 相同，不影响签名，无需改动。
3. **`isMinifyEnabled = true` 但 `proguard-rules.pro` 仅有注释**。当前代码量小（app 仅 1 个 Activity）能通过，但后续接入 Room/Media3 时 release 混淆大概率出错。这超出本次范围，我仅在此提示。
4. **`ndk { abiFilters }` 在无 native 代码的模块下**目前无实际作用，也不影响签名，保持原样。
5. 本次仅涉及 CI 与构建配置，**不改动任何业务代码**，因此无需更新 `.agentdocs/` 的任务文档；完成后我仅会在 `index.md` 的"全局重要记忆"补一条签名 Secrets 约定的记录。

---

## 执行顺序

确认 3 个问题后，我按 1 → 2 → 3 → 4 → 5 顺序执行，每一步跑完对应验证再进入下一步。若某步失败（如 `gh repo create` 权限不足、keystore 校验失败），我会停下报告具体错误，不跳过继续。
