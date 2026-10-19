# Android TV 超级飞鼠设计

## 意图

把 AirMouse 从“手机控制电脑”扩展为“手机控制 Android TV”。电视是输入接收端，手机浏览器承担触摸板、遥控快捷键、键盘及整段文字输入。

## 目标

- 用户可在 Android TV 上安装并运行配套 App。
- TV App 在局域网提供手机控制页和 WebSocket 指令通道，使用二维码携带本次启动的配对令牌。
- 手机控制页支持相对指针移动、单击、双击、拖拽、滚动、常用快捷键、按键输入及整段文字发送。
- TV 端使用 AccessibilityService 派发触屏手势，绘制指针覆盖层；对可访问节点优先使用节点操作。
- 通过 AccessibilityService 支持 Back、Home、方向/确认导航；方向键在系统提供对应 Global Action 时直接触发，否则尝试基于无障碍节点焦点移动。
- 输入法作为整段文本输入的主要路径，在输入框获得焦点时调用 InputConnection.commitText；AccessibilityNodeInfo.ACTION_SET_TEXT 作为可用节点的备用路径。
- 无障碍服务首次配置由用户在电视系统设置中显式启用；App 提供清晰的引导与状态。
- 保持现有桌面服务工作方式；电视模式是独立 Android 工程，不把 Android 输入逻辑混进桌面 pyautogui/pynput 服务。

## 非目标与限制

- 普通 Android App 无法保证向所有 TV App 派发真正的全局原生鼠标 HID 事件。第一版通过无障碍手势和屏幕指针实现鼠标式交互；只支持 D-pad 的界面可能需要切换 D-pad 模式。
- 不做蓝牙 HID、ADB、root 或系统签名输入注入。
- App 不承诺电视应用都开放可访问的输入节点；目标字段不支持无障碍设置文字时，用户启用 AirMouse 输入法后由 InputConnection 发送。
- 首版仅面向 Android TV / Google TV Android 7.0（API 24）及以上设备，实际兼容范围仍需真机验证。
- 控制和配对信息只在局域网传输；随机配对令牌用于限制同网段未配对设备访问。它不提供互联网远程控制或 TLS。

## 架构

- 仓库分为并列的 `desktop/` 与 `android-tv/` 子项目。现有 Python 电脑端源码、网页资源、配置、锁文件、测试和打包脚本迁入 `desktop/`；Android 电视端是独立 Kotlin/Gradle 工程。
- 根目录提供两端总览；电脑端安装、运行和打包命令从 `desktop/` 子项目运行。
- TV App 实现电视 Activity、局域网 HTTP/WebSocket 服务、AccessibilityService 和 InputMethodService。
- TV App 通过二维码把 `http://<tv-ip>:<port>/?token=<random-token>` 呈现给手机；网页与 WebSocket 使用同一主机、同一端口。
- 手机页是独立于现有 Socket.IO 的轻量 WebSocket 页面，消息采用 JSON：`move`、`click`、`drag_start`、`drag_end`、`scroll`、`key`、`text`、`status`。
- AccessibilityService 维护指针坐标和覆盖层，将点击/拖拽/滚动转成 GestureDescription；Home/Back/D-pad/确认优先使用 Android Global Action，低版本使用可访问节点焦点候选作为方向导航回退。
- InputMethodService 在当前输入连接存在时提交整段文字和回车；输入法未激活时，接收端查找当前输入焦点节点并尝试 ACTION_SET_TEXT。
- 新服务采用独立的控制处理器和状态对象，便于将桌面与 TV 输入实现分开维护。
- 移动电脑端文件时同步修正脚本/文档的相对路径，资源仍以 `desktop/` 为根；电脑端事件处理逻辑保持原样。

## 本次实现的默认决策

- 手机无需安装原生 App；电视 App 自行提供本地网页。
- 默认 HTTP + WebSocket，局域网内用随机令牌配对；每次 App 服务重新启动时生成新令牌。
- 无障碍服务是电视全局控制所需组件；App 使用 Android 系统设置页引导用户启用，而不是静默启用。
- 首版优先保证触控板指针、点击/双击/拖动、滚动、Back/Home/D-pad、键盘快捷输入和整段文字发送。
- Android 方向键 Global Action 仅在平台提供时调用；旧系统使用 accessibility node 导航并在不可用时给出明确反馈。
- 不改变现有 Python 桌面控制页面和事件处理；只调整其所在目录及配套入口路径。

## 验收标准

- Android Studio 可同步并构建 Debug APK。
- TV App 显示局域网地址、配对二维码、连接状态和无障碍/输入法配置入口。
- 扫码后手机页可以连接，并发送协议中列出的控制操作。
- 未提供正确令牌的 WebSocket 请求被拒绝。
- AccessibilityService 收到指针/点击/双击/拖拽/滚动/系统快捷键后调用对应 Android API；服务关闭或电视应用不支持相应输入时，界面显示未启用/不支持状态。
- 输入法通过 InputConnection 提交手机端整段文字和回车；当前焦点暴露 ACTION_SET_TEXT 时可作为备用输入路径。
- README 提供安装、首次授权、连接和兼容限制说明。
