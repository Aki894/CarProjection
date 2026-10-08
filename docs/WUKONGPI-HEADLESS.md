# WuKong Pi 后台 CarLife

配合 Aki894/DiPlay integration/wukongpi-headless 的 board APK 使用。更新、Web 控制、权限助手、备份与回退说明：
https://github.com/Aki894/DiPlay/blob/integration/wukongpi-headless/docs/WUKONGPI-HEADLESS.md

本构建只启动 BoardSessionService；MainActivity、无障碍和录屏服务不注册。服务直接管理 CarLife USB Accessory 描述符与 MsgProcess 生命周期，保留 H.264/PCM/native HID、RemoteTouch、TTS 和现有心跳。未使用的内嵌 APK 资源已移除，wukongpi 启用 R8/资源裁剪和 ARM32 限定。

控制 Binder 与媒体桥使用相同签名权限，控制调用进一步检查 DiPlay 包与签名。使用现有固定测试签名，两份 APK 应成对安装，保留应用数据。普通 debug 构建仍保留原来的 UI 入口。

CI 检查协议回归、wukongpi/debug 编译、Lint、最终 APK 组件/权限/ARM32/签名。硬件启动、断连恢复、无线、显示输出关闭和真实车机验收随后进行。
