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

电视端工程位于 `android-tv/`。构建和首次授权步骤见 [Android TV 说明](android-tv/README.md)。

已提供 [Android APK 自动构建与发布流程](android-tv/RELEASING.md)：主分支 Android 相关提交生成 Debug 包，推送 `android-vX.Y.Z` 标签自动发布签名 APK 到 GitHub Releases。
