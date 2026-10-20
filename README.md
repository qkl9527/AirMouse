# AirMouse

AirMouse 由两个独立子项目组成：电脑端接收来自手机浏览器的鼠标/键盘事件，Android TV 端把电视作为控制目标。

## 项目来源与许可

本项目 fork 自 [cornradio/AirMouse](https://github.com/cornradio/AirMouse)。上游原项目是面向 Windows 电脑端的手机鼠标/键盘控制方案；本仓库在原电脑版基础上适配支持 macOS，并优化了整体布局和交互，同时新增 Android TV 电视控制服务和手机网页遥控功能。

上游仓库未声明开源许可证。本仓库根目录的 [MIT 许可](LICENSE)仅适用于本 fork 维护者和贡献者原创新增、且有权授权的代码与文档；不涵盖从上游继承的内容，除非相应权利人另行授权。

## 子项目

- [电脑端（Python）](desktop/README.md)：通过手机浏览器控制 Windows/macOS/Linux 电脑。
- [Android TV 端（Kotlin）](android-tv/README.md)：在电视安装接收 App，再用手机浏览器控制电视。
- [超级飞鼠设计说明](docs/superpowers/specs/2026-10-19-android-tv-supermouse-design.md)

## 启动电脑端

```bash
cd desktop
uv sync
uv run python server.py
```

启动后手机与电脑连接同一个局域网，在手机浏览器访问电脑端显示的地址。

## Android TV 端

把手机变成电视的触摸板和键盘。适用于 Android TV / Google TV（Android 7.0 及以上），手机通过浏览器操作，无需安装手机端 App。

**下载 APK：** [最新正式版下载页](https://github.com/qkl9527/AirMouse/releases/latest)（在 Assets 中选择 `.apk` 文件） · [v1.0.0 APK 直接下载](https://github.com/qkl9527/AirMouse/releases/download/airmouse-tv-v1.0.0/AirMouse-TV-1.0.0-release.apk)

安装到电视并打开 AirMouse 后，先完成以下设置：

1. **开启无障碍服务。** 点击 App 中的「开启 AirMouse-电视控制服务」，在电视系统的「无障碍／辅助功能」中找到 **AirMouse-电视控制服务** 并开启，用于触摸板、点击和遥控操作。
2. **启用并选中 AirMouse 输入法。** 点击「设置 AirMouse 输入法」，在系统的「键盘／输入法」中启用 **AirMouse-手机键盘输入**，再将它选为**当前输入法**。仅启用还不够；选中后，手机才能通过 AirMouse 输入法向电视输入文字、移动文字光标和删除字符。
3. **手机扫码连接。** 确保手机与电视连接同一局域网，回到电视上的 AirMouse 首页，用手机扫码打开控制网页，即可使用触摸板或键盘。输入文字前，先在电视上选中搜索框等输入位置。

不同电视的系统菜单名称可能不同；若 App 按钮无法打开对应设置，可从电视系统设置中手动进入。详细说明见 [Android TV 使用说明](android-tv/README.md)。

已提供 [Android APK 自动构建与发布流程](android-tv/RELEASING.md)：仅推送 `airmouse-tv-vX.Y.Z` 标签时构建签名 APK 并发布到 GitHub Releases，普通分支提交和 Pull Request 不触发构建。
