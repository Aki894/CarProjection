# RTL8761BTV 已焊接板子的开机初始化

目前真机已验证：UART 初始化产生 hci0、经典蓝牙/BLE 启用、可扫描附近设备。
还未验证重启自动初始化、配对、无线 CarPlay。板载 XR819 本身没有蓝牙。

## 现有板子安装

无需重新烧 TF 卡。保留当前已经成功的 UART2 overlay、接线和 Realtek 固件。
下面命令在悟空派的网络 SSH 中执行：

```bash
git clone https://github.com/Aki894/CarProjection.git ~/CarProjection
# 如果已经克隆，改为 git -C ~/CarProjection pull --ff-only
```

**先在之前手动运行 rtk_hciattach 的终端按 Ctrl+C**，再执行：

```bash
sudo bash ~/CarProjection/firmware/wukongpi-h3/setup-bluetooth.sh
sudo systemctl status carlife-bluetooth --no-pager
bluetoothctl list
sudo reboot
```

重连 SSH 后运行 `bluetoothctl list`，并在 `bluetoothctl` 中执行 `scan on`，
观察附近设备，再 `scan off`、`quit`。这是自动初始化的验收标准。

服务使用 `/usr/local/sbin/rtk_hciattach -n -s 115200 /dev/ttyS2 rtk_h5`，
异常退出后重启，120 秒内最多启动三次，避免接线/供电故障时无限重试。
启动包装器检查固件、串口和 H3 UART2 的 sysfs 地址，避免误占调试串口。
服务 active 仅表示 attach 进程存活；控制器和扫描结果才表示蓝牙功能就绪。
不修改 `/boot/armbianEnv.txt`、不杀掉用户手动启动的进程、不自动重启板子。

诊断：

```bash
sudo journalctl -u carlife-bluetooth -b --no-pager
sudo btmgmt info
```

关闭/回退：`sudo systemctl disable --now carlife-bluetooth.service`；
之后可恢复手动 attach。不要同时启动两个 attach 实例。

## 新板子缺少工具或固件时

仅构建用户态工具，不安装仓库内面向旧内核的外部驱动：

```bash
git clone https://github.com/radxa/rtkbt.git ~/rtkbt
git -C ~/rtkbt checkout 72ef9b75374fdde945e0a19f6aba68e13d4d426d
make -C ~/rtkbt/uart/rtk_hciattach -j2
sudo install -m 0755 ~/rtkbt/uart/rtk_hciattach/rtk_hciattach /usr/local/sbin/
sudo install -d /lib/firmware/rtlbt
sudo install -m 0644 ~/rtkbt/rtkbt-firmware/lib/firmware/rtlbt/rtl8761b_fw \
  ~/rtkbt/rtkbt-firmware/lib/firmware/rtlbt/rtl8761b_config /lib/firmware/rtlbt/
```

UART2 需预先在 `/boot/armbianEnv.txt` 已有 overlays 行追加 `uart2`，
并设置 `param_uart2_rtscts=1`、重启。硬件接线见候选 6161B-R 厂商资料，
无标识模块须先确认实际焊盘。固件镜像仅预装服务文件、默认禁用，
不会在没有外接模块的普通板子上自动启动。

资料：
- https://github.com/radxa/rtkbt
- https://www.fn-link.com/6161B-R-Bluetooth-Module-pd40361070.html
- https://github.com/armbian/sunxi-DT-overlays/blob/master/sun8i-h3/README.sun8i-h3-overlays
