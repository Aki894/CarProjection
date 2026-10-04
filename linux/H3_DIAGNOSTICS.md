# H3 USB/AOA 故障审查与分阶段测试

2026-10-05。依据当前仓库、Linux v6.18 FunctionFS 源码与实机日志。
没有远程接入板子；代码测试不能证明整板失去响应的底层原因已经消除。

## 已确认与尚未确认

| 层次 | 当前结论 | 下一步证据 |
|---|---|---|
| g_serial | 暂停 ttyGS0 getty 后已有成功卸载日志 | 各次清理是否成功恢复 |
| 初始枚举 | 有一次主机成功发送 51/52/53，另一次只有 BIND | ENABLE 前后系统是否仍运行 |
| AOA | 已有 accessory ENABLE，手机 HU 支持这条 AOA 路径 | 原车结果及重枚举边界日志 |
| bulk IO | 旧主循环误依赖 O_NONBLOCK；同步调用仍可等待完成 | 新版主循环心跳与实际接收字节 |
| CarLife | 尚无完整接收帧证据；只有两类初始回复实现 | session 阶段的 carlife_rx |
| 网络 | 之前已有 Ethernet DMA reset 失败及 XR819 missed interrupt | 本次内核日志、IRQ 和网络计数 |
| 整板状态 | SSH 失联和网口灯常亮不能证明 CPU 锁死 | 独立记录器、UART 控制台与外部 ping |
| 供电/硬件 | 未测电压、未核对实际 PCB 的 USB 供电路径 | 固定可靠供电、单变量线缆/主机对照 |

Wi-Fi 是 SDIO，蓝牙是 UART；本脚本没有修改网络、蓝牙、GPIO、CPU 频率、
USB dr_mode 或设备树。但 USB 驱动异常、IRQ 风暴或供电异常仍可能间接影响整板。
Orange Pi Zero 设备树是目前实验基础，不能仅凭兼容宣称认定 PCB 完全一致；
不在没有证据时盲目切换 DTB、内核或删除驱动。

## 代码修正

- bulk 同步收发移至两个工作线程；队列各最多 16 项，读缓冲 16 KiB。
  主循环继续处理 ep0、期限与每 5 秒心跳。没有 socket 式 readiness 假设。
- 默认 phase=bind；AOA 与 bulk 需分别显式选择。收到已启用 accessory 的
  DISABLE/UNBIND 后结束本轮，不在旧队列上自动重连。
- suspend 时暂停提交新 bulk IO；resume 后恢复。正在等待的请求仍由内核处理。
- AOA 字符串请求上限按协议约束为 256 字节；不保存字符串/媒体内容。
- 重枚举、清理前记录动作。清理顺序为停止新 IO、解绑、等待工作线程、关闭
  端点、卸载 FunctionFS、删除自身配置、恢复原先 USB 串口/getty。
- 启动、运行、清理均加后备计时器；命令等待有界。信号不能解除内核 D 状态，
  无法保证 sysfs 写入/端点关闭在有缺陷的驱动上一定结束。
- `--log-file` 直接追加并 fsync JSON 日志；独立记录器记录内核和每 2 秒系统计数。
  日志只含诊断信息，不含 Wi-Fi 密码或媒体负载。

## 每次实验前启动独立记录器

当前镜像显式设置 journald Storage=volatile。重启后不能指望 `journalctl -b -1`
找回现场；这次无需重刷固件或改日志服务。下列记录器只读，不切换 USB/网络：

```bash
git -C ~/CarProjection pull --ff-only
cd ~/CarProjection
sudo -v
carlife_stamp=$(date +%Y%m%d-%H%M%S)
sudo nohup python3 -u linux/h3_diagnostics.py --duration 180 \
  --output "$HOME/h3-system-$carlife_stamp.jsonl" \
  > "$HOME/h3-recorder-$carlife_stamp.stdout" 2>&1 < /dev/null &
```

确认对应 stdout 文件出现 `Recorder ready` 再启动探测。记录器每次要求新输出文件，
最多 180 秒（可配置 1..600）或约 16 MiB。两份日志 fsync 会增加少量 SD 写入，
属于诊断模式；突然掉电或内核锁死仍可能丢失最后一部分内容。

若 Wi-Fi SSH 失联，独立记录器仍持续且计数正常，是网络问题的证据；记录一起停止
仍不能单独证明 kernel panic。尽可能接之前的 USB-TTL 捕获 UART 控制台并在电脑
保存输出，从启动到故障；先确认 Linux console 对应正确 UART。仅靠 U-Boot 输出
不够。不要依赖被实验替换的 ttyGS0，也不要把 UART 电源脚作为第二路供电。

## 明天原车分阶段测试

先拔 OTG 数据线，通过网络操作。保留原先蓝牙/Wi-Fi 配置；使用板子已验证的
可靠供电与数据线，不临时切断 VBUS 或自制改线。运行时记录“未插线/插线/点击车机”
三个时间点。第一阶段即使不插车机也异常，先处理板子，不继续协议测试。

```bash
sudo python3 -u linux/aoa_probe.py --release-g-serial --phase bind --duration 30 \
  --log-file "$HOME/h3-bind-$carlife_stamp.jsonl"
```

看到 waiting_for_host 后插车机。这一阶段不响应 AOA 协议协商，车机不能进入
CarLife 属于预期；目标是验证系统稳定、主机 ENABLE 及清理完成。
确保上一轮进程退出、无 cleanup_error 后，重新启动记录器（新时间戳/文件）再测：

```bash
sudo python3 -u linux/aoa_probe.py --release-g-serial --phase aoa --duration 60 \
  --log-file "$HOME/h3-aoa-$carlife_stamp.jsonl"
```

目标是 aoa_start、accessory ENABLE、持续心跳。此阶段不提交 bulk 数据，不能投屏。
若这阶段仍失联，优先看 UDC 切换/内核/供电，不归因于 CarLife 收发。
两阶段都稳定且清理正常后才做 session：

```bash
sudo python3 -u linux/aoa_probe.py --release-g-serial --phase session --duration 60 \
  --log-file "$HOME/h3-session-$carlife_stamp.jsonl"
```

目标是 bulk_first_rx、carlife_rx、carlife_tx 或 milestone_video_init。视频、媒体、
认证、心跳和输入仍未实现，原车页面仍可能超时断开；不要期待本轮已可用 CarPlay。
原车与手机 HU 的结果分别记录，以区分主机差异。任何阶段整板失联就停在该阶段，
恢复后收集日志，不连续重复触发。

若网络仍可用但脚本不退出：

```bash
ps -eLo pid,tid,ppid,stat,wchan:32,comm | rg 'python3|modprobe|carlife|agetty'
sudo journalctl -k -n 100 --no-pager
```

读取对应 PID/TID 的 `/proc/PID/task/TID/stack`，观察等待点；D 状态不能保证可杀。
不要在旧任务/旧 gadget 尚存时开启另一轮。

## 排查次序

1. BIND 前后失联：USB 绑定/PHY、驱动、网络与供电，不是 bulk 主循环错误。
2. AOA START 后失联：重枚举路径、UDC 解绑/重绑；无 bulk 阶段也可复现。
3. 仅 session 异常：查看工作线程等待点、RX/TX、MUSB 请求与内核堆栈。
4. 仅 SSH 失联但 UART/记录器正常：XR819、NetworkManager、IRQ/网络计数。
5. UART 有 oops/lockup/RCU stall：先按具体内核栈处理，必要时再对照内核版本。

网口 LED 状态不是诊断结论，先前 Ethernet DMA 错误也不能用 Wi-Fi 软件优化解决。
这轮审查不改已工作的 Android 音视频桥接、不重刷固件，也不启用自动重启掩盖故障。

依据：
- https://github.com/torvalds/linux/blob/v6.18/drivers/usb/gadget/function/f_fs.c
- https://docs.kernel.org/usb/functionfs.html
- https://source.android.com/docs/core/interaction/accessories/aoa
