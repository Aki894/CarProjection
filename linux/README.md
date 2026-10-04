# Linux CarLife MD：第一轮 USB 实验

`aoa_probe.py` 使用内核已有 configfs/FunctionFS，不依赖 Android 的 USB Accessory API。
实现 AOA 1.0 的 GET_PROTOCOL(51)、SEND_STRING(52)、START(53)，随后使用
18d1:2d00 重新枚举，并用 CarLife USB 外层封包回应 HU_PROTOCOL_VERSION / HU_INFO。
封包及 DeviceInfo 字段参照当前安卓端 Utils.java 和 proto；不复制 Android 消息处理线程。

**这是待真车验证的实验程序，不是已经可用的 CarPlay 盒子。** 不含视频初始化回复、
音视频、认证、心跳、触控回传或 CarPlay 接收端。到了 VIDEO_ENCODER_INIT 会记录
`milestone_video_init`，不宣称投屏成功；车机随后超时断开是本轮预期限制。
起始 18d1:4ee7 + vendor interface 仅为实验身份，不实现 ADB。
若车机只识别特定 Android 初始接口，可能根本不发送 AOA 请求，日志可用于区分此情况。

## 桌面或真车执行

先完成 `firmware/wukongpi-h3/BLUETOOTH.md` 的开机测试。USB 实验本身不依赖蓝牙。
**必须通过 Wi-Fi/网线 SSH 操作，不可依赖 USB 串口保持连接。**

```bash
cd ~/CarProjection
sudo python3 linux/aoa_probe.py --release-g-serial --duration 120 \
  | tee ~/carlife-aoa-probe.log
```

启动后，把板子的 USB OTG 数据口接车机 CarLife 数据口，进入车机 CarLife 页面。
如 `g_serial` 正被打开的 ttyGS0 占用，程序会拒绝卸载；请关闭该 USB 串口连接，
用网络 SSH 重试。程序只操作自己的 gadget，拒绝解绑其他 configfs gadget。
Ctrl+C、SIGTERM、异常及 120 秒到时都会尝试解绑/删除本程序 gadget，并恢复原先
加载的 g_serial；若恢复失败会明确记录 `cleanup_error`，可以 `sudo modprobe g_serial`。
不在启动时更改网络、蓝牙、持久 USB 设置或自动启用此实验。

依次寻找这些日志：

| 日志 | 说明 |
|---|---|
| gadget_bound | 临时 gadget 成功绑定 UDC |
| aoa_protocol / aoa_identity_string | 车机正在尝试 AOA |
| aoa_start / accessory_reenumeration | 请求切换，板子已重新绑定 accessory 身份 |
| functionfs_event type=2, mode=accessory | 新配置被主机启用 |
| carlife_rx / carlife_tx | 收到 CarLife 消息 / 完成初始回复写入 |
| milestone_video_init | 车机推进到视频参数协商，下一阶段开发入口 |

只有 gadget_bound 不代表 AOA 或 CarLife 成功；只有 accessory_reenumeration 也不代表
主机已重新枚举。需结合新模式的 ENABLE 和实际 CarLife 数据判断。
日志不记录 host 提供的 USB 身份字符串全文，也不保存媒体负载。
转发前须在这条实验链路上验证端点读写、重枚举和断开重连行为。

## 本地检查

```bash
python3 -m unittest discover -s linux -v
python3 linux/aoa_probe.py --help
```

测试覆盖 FS/HS 二进制描述符、分片/合并 USB 读、CarLife 初始回复和恶意长度拒绝。
它们不能代替真实 UDC、USB 主机和车机测试。接下来的里程碑是完整 CarLife 会话/
心跳和测试视频，然后接 CarPlay 接收端、音频和输入。

官方协议/内核依据：
- https://source.android.com/docs/core/interaction/accessories/aoa
- https://docs.kernel.org/usb/functionfs.html
- Linux 6.18 include/uapi/linux/usb/functionfs.h
