# 悟空派 H3 Zero 开发固件

首版用于验证 512MB 悟空派 H3 Zero 的 TF 启动、有线网络、XR819 Wi-Fi、USB Host 和 USB Device/UDC。按用户确认的 Orange Pi Zero 兼容性使用 `BOARD=orangepizero`；不是 Zero 2/3 的 H616/H618 镜像。

基于 Armbian + Debian 13 Trixie Minimal，无桌面，NetworkManager 管理网络，SSH 开启，默认 USB 串口保留。包含开发依赖及 `carlife-board-check`。**尚未包含 Linux CarLife/CarPlay 桥接程序，也不承诺真机启动已验证。**

## 构建

GitHub Actions 工作流 `.github/workflows/build-h3-firmware.yml` 固定 Armbian 框架提交；构建 manifest 记录框架/项目提交、生成镜像及内核包信息。上游 apt 和组件源可能更新，结果不宣称逐字节可重复。

本机复现需要可运行特权 Docker 的 Linux/WSL2 主机，以及约 50GB 可用空间：

```bash
git clone https://github.com/armbian/build.git armbian-build
git -C armbian-build checkout b44a5a2cfc5114477e20fc8f6aaa38058860ab9f
cp -a firmware/wukongpi-h3/userpatches/. armbian-build/userpatches/
printf 'Project=CarProjection\nPurpose=H3 hardware bring-up\n' > armbian-build/userpatches/overlay/carlife-firmware-info
cd armbian-build
./compile.sh docker carlife-h3 SHOW_LOG=yes SHARE_LOG=no
```

产物在 `output/images/`。首次完整构建可能需要较长时间。框架可复用上游组件缓存，缺失时自行编译。

## 烧录与首次启动

1. 下载 Actions 的 `WuKongPi-H3-development-image` 压缩包，解包后核对 `SHA256SUMS`。解压 `.img.xz` 或用支持 xz 的烧录工具，选择 **TF 卡**写入整个 `.img`，而不是复制文件到分区。TF 卡建议至少 8GB；写入会清空该卡。
2. 插卡、接网线到路由器，使用稳定 5V 电源供电。首次启动等文件系统扩容完成，在路由器 DHCP 列表找 `carlife-h3`。没有 HDMI 时仍可 SSH 或 3.3V TTL 串口调试；串口 115200 8N1。
3. `ssh root@板子的IP`，沿用 Armbian 首次登录 `root / 1234`，按提示立即改密码、创建普通用户。后续用普通用户登录。
4. `sudo carlife-board-check | tee board-check.txt`，反馈输出以确认实际内存、Wi-Fi 模式、蓝牙控制器和 UDC。先在桌面验证，后续再接车机。
5. 板载 Wi-Fi 连接可用 `sudo nmtui`。镜像不预存 SSID/密码，不默认启动 AP。XR819 仅 2.4GHz，不能提供 5GHz；是否支持所需 AP 模式由 `iw list` 和实测判断。未假定板载蓝牙可用。

## 与安卓原型的关系

当前已验证的安卓音视频/触控逻辑可作为移植参考，但 Java/Kotlin、Android Binder 和 USB Accessory API 不能原样运行在此 Linux 镜像。接下来先验证 UDC 与 AOA 请求/重枚举，再移植 CarLife 会话和媒体传输，最后接 Linux CarPlay 接收端。保留 `g_serial` 是首次调试用途；它占用 UDC，未来 AOA gadget 启动前需由专用程序受控释放，不能同时绑定两个 gadget。

此镜像只写 TF 卡；没有写入板载 SPI Flash 的步骤。
