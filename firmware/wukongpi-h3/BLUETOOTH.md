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
sudo poweroff
```

关机后断开所有可能供电的 USB/外部电源，再重新上电（冷启动）。
重连 SSH 后运行 `timeout 8s bluetoothctl list`，并在 `bluetoothctl` 中执行 `scan on`，
观察附近设备，再 `scan off`、`quit`。这是冷启动自动初始化的验收标准。
普通 reboot 不一定会让持续供电的蓝牙模块复位。

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

## H5 同步超时与 bluetoothctl 等待

连续 `OP_H5_SYNC Transmission timeout` 和 `Retransmission exhausts` 表示
初始化工具没有收到 UART H5 同步回复；串口文件存在不代表控制器已经注册。
此前手动扫描成功、热重启后失败时，模块保留运行状态/非初始波特率是待验证的原因，
也应排查电源和流控。先做完整断电测试，不循环重启服务或猜测波特率。

旧版在 Type=simple 主进程内才加载 hci_uart，与 BlueZ 启动存在时序问题。
BlueZ 单元有 `/sys/class/bluetooth` 存在条件，驱动未加载时可能跳过启动。
修订版增加同步 ExecStartPre 加载和 Wants=bluetooth.service，安装时清除
start-limit 失败状态。该修改修复加载顺序，不保证未复位的模块恢复初始状态。

如果冷启动成功、热重启仍失败，下一步将模块 BT_DIS_N 接可控 GPIO，按实际接线
实现初始化前复位。不要在不知道 38 脚是否直连 3.3V 时直接把 GPIO 拉低。
没有硬件复位线路前，自动重启仅作有限重试，不能保证恢复已运行的控制器。
回退到手动 attach 同样可能需要先断电复位。

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
