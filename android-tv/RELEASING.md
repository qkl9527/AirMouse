# Android APK 打包与自动发布

## 1. 当前如何本地打包

Android 工程位于 `android-tv/`，使用仓库自带的 Gradle Wrapper 8.13、Android Gradle Plugin 8.13.2、Kotlin 2.2.20、JDK 17、Android SDK Platform 35。最低安装版本为 Android 7.0 / API 24。

安装 Android Studio 的 SDK 和 JDK 17 后，在仓库根目录执行：

```bash
cd android-tv
./gradlew :app:assembleDebug
```

如果没有设置 Android SDK 路径，在 `android-tv/local.properties` 填写自己的 SDK 位置，例如 macOS：

```properties
sdk.dir=/Users/你的用户名/Library/Android/sdk
```

也可以通过 `ANDROID_HOME` 指定 SDK。`local.properties` 只在本机使用，已加入 Git 忽略规则。

产物与安装命令：

```text
android-tv/app/build/outputs/apk/debug/app-debug.apk
```

```bash
# 当前位于 android-tv 目录。
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug 包由 Android 工具自动使用本机的调试密钥签名，适合开发调试。正式发布使用下面配置的固定密钥。

## 2. 自动构建和发布的触发规则

Workflow：仓库根目录 `.github/workflows/android-apk.yml`，Actions 页面显示为 **Android APK**。

| 操作 | 结果 | 下载位置 |
| --- | --- | --- |
| 向 `main` / `master` 推送包含 Android 工程或此 Workflow 修改的提交 | 构建 Debug APK | Actions → 对应运行 → Artifacts |
| 提交修改上述文件的 Pull Request | 构建 Debug APK，不读取发布签名 | Actions → 对应运行 → Artifacts |
| Actions → Android APK → Run workflow | 手动构建所选分支的 Debug APK | Actions → 对应运行 → Artifacts |
| 推送 `android-vX.Y.Z` 标签 | 构建固定签名 Release APK，自动创建 Release 并上传 APK 与 SHA-256 文件 | 仓库 Releases |

Actions Artifacts 保留 30 天，下载后先解压 ZIP 获取 APK；Releases 中可以直接下载 APK。只修改 `desktop/` 不触发 Android 分支构建。标签推送不受路径过滤限制。

`workflow_dispatch` 手动运行始终生成 Debug 包，正式发布通过推送标签触发。Workflow 首次进入默认分支后，Actions 页面才提供手动运行入口。如果 fork 仓库尚未开启 Actions，需要先在 Actions 页面启用。

## 3. 首次配置正式签名（仅需一次）

### 生成并备份密钥

如果已经有此应用的正式签名密钥，请继续使用原密钥。没有时，在仓库之外生成：

```bash
cd android-tv
mkdir -p ./.airmouse-signing
keytool -genkeypair \
  -keystore ./.airmouse-signing/airmouse-release.jks \
  -storetype JKS \
  -alias airmouse \
  -keyalg RSA -keysize 3072 -validity 10000
```

按提示设置密钥库密码、证书信息和密钥密码；密钥密码可以与密钥库密码相同。请备份 `.jks`、别名和密码，后续覆盖升级必须使用相同的签名密钥。应用签名与空中飞鼠的局域网 HTTPS 证书是两套独立机制。

### 配置 GitHub Secrets

打开仓库 **Settings → Secrets and variables → Actions → New repository secret**，添加以下四项：

| Secret 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `.jks` 文件的 Base64 编码内容 |
| `ANDROID_KEYSTORE_PASSWORD` | 生成密钥时设置的密钥库密码 |
| `ANDROID_KEY_ALIAS` | 示例为 `airmouse`，使用原密钥时填写其真实别名 |
| `ANDROID_KEY_PASSWORD` | 对应别名的密钥密码 |

macOS 可用下面的命令将密钥编码后复制到剪贴板，再粘贴到 `ANDROID_KEYSTORE_BASE64`：

```bash
base64 -i ~/.airmouse-signing/airmouse-release.jks | tr -d '\n' | pbcopy
```

Base64 只是编码，仍属于私钥内容，不要提交或放进日志。仓库已经忽略 `*.jks`、`*.keystore` 和 `keystore.properties`。

Workflow 使用 GitHub 自动提供的 `GITHUB_TOKEN` 创建 Release，不需要另建 PAT。只有发布 Job 申请 `contents: write`；构建 Job 使用只读权限。仓库或组织策略必须允许 Actions 创建 Release，以及使用 Workflow 中的 Actions。缺少签名 Secrets 时正式构建会明确失败。

## 4. 怎样提交才能自动打包发布

先把代码和 Workflow 提交、推送到发布分支。下面以 `main` 为例；如果实际使用 `master`，替换分支名：

```bash
# 在仓库根目录，先检查并按需暂存准备发布的文件。
git status
git add .github/workflows/android-apk.yml android-tv .gitignore README.md
git commit -m "ci: add Android APK build and release"
git push origin main
```

这时会自动构建 Debug 包。要正式发布，在要发布的提交上创建并推送新版本标签：

```bash
git tag -a android-v1.0.1 -m "AirMouse TV 1.0.1"
git push origin android-v1.0.1
```

随后打开 GitHub → Actions → Android APK 查看进度。成功后在 **Releases → AirMouse TV 1.0.1** 下载：

```text
AirMouse-TV-1.0.1-release.apk
AirMouse-TV-1.0.1-release.apk.sha256
```

以后每次发布，只需提交、推送代码，再推送一个递增的新标签，例如 `android-v1.0.2`。只运行 `git commit` 或未推送的本地标签不会触发 GitHub Actions。不要把已经发布的标签移到其他提交，也不要复用旧标签发布不同代码。

### 版本号规则

- 标签必须是 `android-vX.Y.Z`，例如 `android-v1.0.1`。
- 每段为 `0..999`，不允许前导零、`-beta` 等后缀，不接受 `android-v0.0.0`。
- APK `versionName` 自动取 `X.Y.Z`。
- APK `versionCode` 自动计算为 `X × 1000000 + Y × 1000 + Z`，例如 `1.0.1 → 1000001`、`1.1.0 → 1001000`。发布时按版本大小递增，才能覆盖升级。
- 本地 Debug 默认仍为 `versionName=1.0.0`、`versionCode=1`。

### 现有安装包的升级

如果电视上安装的是之前本地构建的 Debug 包，它的签名通常与新 Release 密钥不同，不能直接 `adb install -r` 覆盖。需要先卸载旧包，再安装正式包；**卸载会清除应用设置和保存在电视上的 HTTPS 证书身份，之后需要重新授权、配对，并按需重新信任证书**。确认迁移后再卸载。以后正式包使用相同签名和递增版本号即可覆盖升级。

GitHub Debug 构建运行在临时机器上，不保证各次 Debug 包与本机或历史 CI Debug 包的签名一致；长期安装和升级请使用 Release 包。

## 5. 本地构建正式包

本地同样使用固定密钥，通过环境变量传给 Gradle。下面的密码变量应填写自己设置的值，不要提交到脚本或仓库：

```bash
cd android-tv
export ANDROID_KEYSTORE_PATH="$HOME/.airmouse-signing/airmouse-release.jks"
export ANDROID_KEYSTORE_PASSWORD='你的密钥库密码'
export ANDROID_KEY_ALIAS='airmouse'
export ANDROID_KEY_PASSWORD='你的密钥密码'
./gradlew :app:assembleRelease \
  -PairmouseVersionName=1.0.1 \
  -PairmouseVersionCode=1000001
```

产物：`android-tv/app/build/outputs/apk/release/app-release.apk`。直接执行 `assembleRelease` 但未提供完整签名环境变量时，会提示缺少签名参数。

## 官方参考

- [Android 命令行构建](https://developer.android.com/build/building-cmdline)
- [Android 应用签名与升级](https://developer.android.com/studio/publish/app-signing)
- [GitHub Actions 触发条件与权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)
- [GitHub CLI 创建 Release](https://cli.github.com/manual/gh_release_create)
