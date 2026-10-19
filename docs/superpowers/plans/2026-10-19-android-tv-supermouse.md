# Android TV 超级飞鼠 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持电脑模式不变的前提下，新增 Android TV 配套 App，让手机浏览器作为 TV 触摸板、遥控器与键盘。

**Architecture:** 新增独立 Kotlin Android TV 工程。TV App 提供带随机配对令牌的本地网页和 WebSocket；无障碍服务负责系统手势、指针覆盖、节点导航和可用的全局操作；输入法负责当前输入框的整段文字。

**Tech Stack:** Kotlin、Android Gradle Plugin、Android AccessibilityService、InputMethodService、WebSocket、ZXing QR encoder、嵌入网页。

**Spec:** `docs/superpowers/specs/2026-10-19-android-tv-supermouse-design.md`

## Global Constraints

- Min SDK 为 Android 7.0 / API 24。
- 电视侧权限必须由用户在 Android 系统设置里显式启用。
- 手机与电视通过同一局域网通信，使用随机令牌配对。
- Python 桌面版输入处理和现有页面不改变。
- 按用户要求不添加或运行自动化测试，不创建 Git commit；使用 Gradle 构建与静态代码检查验证。

## Review Focus

- 局域网内未配对的客户端必须无法打开 WebSocket 控制通道。
- 无障碍服务未启用或掉线时，手机页面必须能显示连接状态并给出可操作提示。
- Android API 低于 33 时 D-pad 全局动作不可用，必须走节点导航回退并安全报告失败。
- 输入焦点不存在或输入法未绑定时不能崩溃、误把普通文本当作快捷键。
- 手指快速移动、点击与滚动时，指针坐标需保持屏幕边界内，且不能卡住拖动状态。

---

### Task 0: 将现有电脑端整理为 desktop 子项目

**Files:**
- Move existing desktop sources/assets/configuration/scripts into `desktop/`
- Create: root `README.md`
- Modify: `desktop/README.md`, `desktop/AirMouse.spec`, `desktop/build_exe.bat`, `desktop/AirMouseserver.bat`, `.gitignore`

**Interfaces:**
- Run the desktop app from `desktop/` with `uv run python server.py`.
- Existing Windows scripts resolve code and resources relative to `desktop/`.
- Root README links to both subprojects and their setup instructions.

- [ ] Move Python modules, templates/static assets, tests, data, certificates, packaging files, screenshots, and legacy snapshots into `desktop/` while preserving file contents.
- [ ] Rename `requirments.txt` to `requirements.txt` and update Windows scripts.
- [ ] Update desktop setup/run instructions and add root project overview.
- [ ] Inspect imports/resource paths and verify Python syntax plus PyInstaller configuration references remain relative to `desktop/`.

### Task 1: Android TV 工程外壳和电视首页

**Files:**
- Create: `android-tv/settings.gradle.kts`
- Create: `android-tv/build.gradle.kts`
- Create: `android-tv/gradle.properties`
- Create: `android-tv/app/build.gradle.kts`
- Create: `android-tv/app/src/main/AndroidManifest.xml`
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/MainActivity.kt`
- Create: `android-tv/app/src/main/res/values/strings.xml`
- Create: `android-tv/app/src/main/res/values/styles.xml`
- Create: `android-tv/app/src/main/res/drawable/` application icon resources

**Interfaces:**
- `MainActivity` displays service state, local URL, QR image, and links to Accessibility/Input Method settings.
- Service/controller objects publish the latest URL and connection/accessibility state to the Activity.

- [ ] Create an Android TV app module with `minSdk = 24` and the permissions/features required for network serving and leanback discovery.
- [ ] Implement a D-pad navigable pairing screen with localized Chinese setup text and status.
- [ ] Generate a session token at service start and render the phone URL as a QR image.
- [ ] Verify Gradle project sync and assemble Debug APK.

### Task 2: TV local page and pairing WebSocket protocol

**Files:**
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/ControlServer.kt`
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/ControlMessage.kt`
- Create: `android-tv/app/src/main/assets/remote.html`
- Modify: Android manifest for the bound AccessibilityService server lifetime.

**Interfaces:**
- `ControlServer.start(context, token, listener)` publishes an HTTP URL and accepts WebSocket sessions.
- `ControlMessage` validates `{type, ...}` messages and converts them to typed `RemoteAction` values.
- `ControlServer.Listener.onAction(action)` dispatches validated commands; `onClientCountChanged(count)` updates UI state.

- [ ] Serve the controller HTML and WebSocket endpoint on a configurable local TCP port.
- [ ] Require the random token for both page access and WebSocket upgrade; reject invalid/missing tokens.
- [ ] Implement bounded JSON parsing and typed messages for move/click/double-click/drag/scroll/key/text.
- [ ] Implement a responsive phone controller with touchpad, direction/OK/back/home controls, keyboard, text composer, and connection indicator.
- [ ] Verify Gradle Debug APK build and inspect embedded asset paths.

### Task 3: Accessibility touch, cursor, navigation and shortcut actions

**Files:**
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteAccessibilityService.kt`
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/PointerOverlay.kt`
- Create: `android-tv/app/src/main/res/xml/accessibility_service.xml`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/MainActivity.kt`
- Modify: `android-tv/app/src/main/AndroidManifest.xml`

**Interfaces:**
- `RemoteAccessibilityService.dispatch(action)` consumes typed `RemoteAction` values.
- `RemoteAccessibilityService` exposes enabled/connected state to `MainActivity` and `ControlServer`.
- Pointer overlay owns screen-bound clamping and visual invalidation; service owns Android gesture dispatch.

- [ ] Add a user-enabled AccessibilityService with gesture capability and interactive window/node access.
- [ ] Maintain clamped pointer coordinates and draw a visible pointer using an accessibility overlay.
- [ ] Translate click/double-click, relative drag and scroll actions into gesture dispatches; release any active drag state on disconnect.
- [ ] Implement Back/Home and D-pad/OK actions, preferring system Global Actions and using a spatial focus-node search for older Android versions.
- [ ] Implement TV volume shortcuts using AudioManager and report unsupported actions instead of silently swallowing them.
- [ ] Verify Gradle Debug APK build and inspect service/manifest declarations.

### Task 4: Remote input method and text delivery

**Files:**
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteInputMethodService.kt`
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteInputBridge.kt`
- Create: `android-tv/app/src/main/res/xml/method.xml`
- Modify: `android-tv/app/src/main/AndroidManifest.xml`
- Modify: `MainActivity.kt` and `ControlServer.kt` for input method state and text actions.

**Interfaces:**
- `RemoteInputBridge.commitText(text)` submits text to the bound `InputConnection` and returns success/failure.
- `RemoteInputBridge.sendEnter()` requests the editor action or submits newline to the bound connection.
- Control server falls back to accessibility `ACTION_SET_TEXT` if no active InputConnection exists.

- [ ] Implement an InputMethodService that keeps the current InputConnection available to the receiver and shows a minimal “use phone to type” surface on TV.
- [ ] Add a TV settings shortcut to enable/select AirMouse IME and show its enabled/selected status.
- [ ] Send full text through `commitText`; implement clear/backspace/enter behavior safely.
- [ ] Add ACTION_SET_TEXT fallback for editable accessibility-focused nodes.
- [ ] Verify Gradle Debug APK build and inspect IME manifest metadata.

### Task 5: Documentation and final verification

**Files:**
- Modify: root `README.md`
- Modify: `desktop/README.md`
- Create: `android-tv/README.md`

- [ ] Document installation, TV permissions, pairing, supported actions, Android API limitations, and the true-mouse compatibility caveat.
- [ ] Build the final Debug APK from the complete project.
- [ ] Review all changed files and confirm the desktop mode remains untouched.
- [ ] Report device-level behaviors that require a real Android TV for confirmation.
