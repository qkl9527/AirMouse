# AirMouse

AirMouse 由两个独立子项目组成：电脑端接收来自手机浏览器的鼠标/键盘事件，Android TV 端把电视作为控制目标。

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
