# Android TV 超级飞鼠增强实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在保留普通 HTTP 触控遥控的基础上，为 AirMouse TV 增加按需 HTTPS 陀螺仪控制、场景按钮、多人活动、手机反馈设置和经过核实的移动端布局。

**Architecture:** 手机端继续使用电视托管的单页 HTML 和 WebSocket。控制页重组为遥控、场景、同乐、设置四个任务入口；电视端扩展经过校验的 WebSocket 协议、延迟启动的 TLS 服务、可启动应用查询、房间状态和指针样式。设置与快捷场景保存在手机浏览器本地。

**Tech Stack:** Kotlin、Android AccessibilityService、Android Socket/SSLServerSocket、嵌入式 HTML/CSS/JavaScript、WebSocket、浏览器 DeviceOrientation API 与 Fullscreen API。

**Spec:** `docs/superpowers/specs/2026-10-19-android-tv-supermouse-design.md`（“超级飞鼠交互扩展”章节）

## Global Constraints

- 普通手机遥控仍使用 HTTP 与 WebSocket；仅用户进入空中飞鼠时按需启动 HTTPS/WSS。
- 手机控制网页不默认请求传感器权限；离开空中模式后停止传感器监听。
- Android TV `minSdk` 保持 API 24；无障碍能力继续由用户在系统设置中显式启用。
- 多人活动最多使用现有的 4 个配对客户端，不新增账号、互联网中继或云端房间。
- UI 主要按钮可触达，375×812 窄屏首屏无需滚动；隐藏次要能力到对应任务入口。
- 不运行或增加自动化测试；按用户要求进行浏览器布局核实，并构建 Debug APK。不要创建 Git commit。

## Review Focus

- TLS 证书未受信、地址变化或传感器权限被拒绝时，HTTP 触控仍可用，空中模式给出明确恢复方式。
- 两个连接来源（HTTP 与 HTTPS）不重复计算同一手机活动人数，也不能绕过配对令牌或 Origin 校验。
- 房间内重复点击、同时抢答、主持人断线及活动中途加入时，电视侧只接受一位赢家并保持投票结果一致。
- 启动视频 App 后焦点/搜索框不受支持时，场景停止后续输入并告知用户，而非把搜索文本打入无关页面。
- 无 Fullscreen、Vibration 或 Web Audio 支持时，对应选项退化为可操作的普通页面/静默反馈。

---

### Task 1: 手机遥控首页与任务入口

**Files:**
- Modify: `android-tv/app/src/main/assets/remote.html`
- Modify: `android-tv/README.md`

**Interfaces:**
- 保留现有触控、单/双击、拖动、滚动、按键和文字消息格式。
- 页面状态以 `activePanel` 控制 `remote | scenes | play | settings`；只有当前面板可交互。
- 全屏按钮调用 `document.documentElement.requestFullscreen()` 与 `document.exitFullscreen()`，同时支持 `webkitRequestFullscreen`。

- [x] 将旧的长页面重新组织为顶部连接/全屏区、模式切换、大触控板、底部四个任务入口。
- [x] 把方向键、键盘输入、自定义按键移入遥控任务内的次级面板，首次只露出最常用控制。
- [x] 加入 Fullscreen 按钮、支持/不支持状态文案和安全区域适配。
- [x] 启动本地页面后在 375×812 视口检查首屏、场景/同乐/设置入口与场景表单焦点；修复了编辑场景时重复空状态占位。实际手机软键盘需真机复核。

### Task 2: 空中飞鼠与延迟 HTTPS

**Files:**
- Modify: `android-tv/app/build.gradle.kts`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlServer.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlMessage.kt`
- Modify: `android-tv/app/src/main/assets/remote.html`
- Modify: `android-tv/README.md`
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/LocalTlsIdentity.kt`

**Interfaces:**
- 新增 `RemoteAction.EnableAirMouse(clientId: String)`，由普通 HTTP WebSocket 发出；服务器惰性启动 TLS 监听器并仅回复请求客户端 `secure_ready`。
- HTTPS 控制页和 WSS 连接沿用同一配对令牌；HTTPS Origin 必须匹配当前电视地址和 TLS 端口。
- TLS 身份在电视 App 私有存储中稳定保存；证书 SAN 与当前局域网 IP 匹配，HTTP 端提供证书下载和两种移动系统的信任说明。
- 陀螺仪命令格式为 `{type:"move", dx, dy, source:"gyro"}`，仍受现有坐标范围校验。

- [x] 实现电视本地 TLS 身份创建/持久化和按需 HTTPS/WSS 监听；默认启动 TV 服务时不打开 TLS socket。
- [x] 提供证书取回、电视屏幕独立 SHA-256 指纹核对和“继续到安全控制页”的状态消息。
- [x] 手机端添加模式申请流程：请求 HTTPS、按 iOS Safari/Android Chrome 显示信任步骤、用户回到 HTTPS 后主动授予方向传感器权限。
- [x] 使用首次方向读数作为基线，加入死区、灵敏度、边界夹紧；离开空中模式或页面隐藏时停止读取。
- [ ] 真实手机上核实普通 HTTP、证书信任失败、权限拒绝、API 不支持及 HTTPS 正常等分支。

### Task 3: 追剧场景

**Files:**
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlMessage.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlServer.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteAccessibilityService.kt`
- Modify: `android-tv/app/src/main/AndroidManifest.xml`
- Modify: `android-tv/app/src/main/assets/remote.html`

**Interfaces:**
- 请求 `{"type":"list_apps","requestId":"..."}` 返回同连接 `{"type":"apps","requestId":"...","items":[{"packageName":"...","label":"..."}]}`。
- 场景动作消息为 `launch_app(packageName)`、`text(value)`、`key(name)`；只允许已安装可启动 package，保留现有文本长度和快捷键校验。
- 场景对象保存在 `localStorage["airmouse-tv-scenes"]`，字段 `{id,name,packageName,query,pressEnter,playAfter}`。

- [x] 在 Android manifest 声明 launcher-app 查询范围，并由 TV 服务提供可启动 App 列表。
- [x] 新增已安装可启动 App 校验的应用启动动作；失败时通过当前客户端回传明确结果。
- [x] 手机端实现空状态、“新建追剧按钮”流程、已保存按钮和编辑/删除；启动后等待 900ms，并仅在目标 App 前台且搜索框可编辑时提交搜索词。
- [x] 对断连、App 不可启动和搜索焦点未就绪显示可理解提示；说明第三方 App 的搜索焦点和自动播放受其自身界面限制。

### Task 4: 多人抢答与选片投票

**Files:**
- Create: `android-tv/app/src/main/java/dev/airmouse/tv/PartyRoom.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlMessage.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlServer.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteAccessibilityService.kt`
- Modify: `android-tv/app/src/main/assets/remote.html`

**Interfaces:**
- 每个页面生成并保存 `clientId`；WebSocket 初连发送 `{"type":"join","clientId":"..."}`，服务端给该连接返回房间快照。
- 电视端 `PartyRoom` 提供 `startQuiz(hostId, question)`、`buzz(clientId)`、`startVote(hostId, options)`、`vote(clientId, optionId)`、`end(hostId)`；状态更新由服务端广播到全部客户端。
- 同一抢答轮最多一个 `winnerId`；投票每个 clientId 每轮只保留一票。

- [x] 添加线程安全的电视房间状态和消息广播；主持人 ID 绑定随机客户端证明，主持人断线时移交给在线玩家，重复/无效投票被拒绝。
- [x] 手机端实现发起问题、3 秒倒数、抢答按钮、候选片名投票和结果视图；连接恢复后读取服务端快照。
- [x] 电视端在指针覆盖层显示问题、获胜玩家和票数；活动结束清除提示且恢复普通遥控。
- [ ] 在最多 4 个真实手机连接的局域网中核实状态同步、赢家唯一、投票计数一致及主持人移交。

### Task 5: 光标皮肤、声音和震动

**Files:**
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/ControlMessage.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/RemoteAccessibilityService.kt`
- Modify: `android-tv/app/src/main/java/dev/airmouse/tv/PointerOverlay.kt`
- Modify: `android-tv/app/src/main/assets/remote.html`

**Interfaces:**
- 协议添加 `cursor_style`，允许 `mint | blue | amber` 三种服务端常量样式，不接受远端颜色字符串。
- 手机端保存 `airmouse-tv-feedback` 偏好 `{sound,vibration,cursorStyle}`；音效由用户手势解锁的 Web Audio 合成短提示，震动使用 `navigator.vibrate`。

- [x] 将电视指针颜色/轮廓变成三套命名预设，并在手机设置里提供清楚的光标预览。
- [x] 实现声音与震动独立开关；不支持震动时禁用/说明对应开关，其他功能保持可用。
- [ ] 真实手机上核实光标切换、偏好刷新保留、音效解锁与不同浏览器能力降级。

### Task 6: 文档、集成构建和布局复核

**Files:**
- Modify: `android-tv/README.md`
- Modify: `docs/superpowers/plans/2026-10-19-android-tv-supermouse-plus.md`

- [x] 补充 HTTP/HTTPS 按需启用、证书指纹信任核对、场景兼容范围、同乐人数和设置说明。
- [x] 构建 `android-tv` Debug APK 并修复 Kotlin/Gradle/资源构建错误。
- [x] 用移动浏览器检查 375×812 布局：遥控首页无滚动和横向溢出；场景、抢答、投票、设置入口可展开；实际手机软键盘仍需真机复核。
- [x] 检查最终变更和既有电脑端实现路径，不创建 Git commit；列明需要真实 Android TV 或手机传感器才能确认的设备行为。

> 设备端验收待办：真实手机的 CA 信任安装、陀螺仪/声音/震动、Android TV 无障碍输入焦点，以及多手机局域网同步尚未在实机核验。
