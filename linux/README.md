# Linux CarLife MD：会话移植与集中测试

`aoa_probe.py` 使用内核已有 configfs/FunctionFS，不依赖 Android 的 USB Accessory API。
可选阶段实现 AOA 1.0 的 GET_PROTOCOL(51)、SEND_STRING(52)、START(53)，随后使用
18d1:2d00 重新枚举，并用 CarLife USB 外层封包回应 HU_PROTOCOL_VERSION / HU_INFO。
封包及 DeviceInfo 字段参照当前安卓端 Utils.java 和 proto；不复制 Android 消息处理线程。

2026-10-05：新增 `carlife_md.py` 完整会话测试入口，共用同一 AOA/FunctionFS 底层。
它实现了安卓端实际使用的握手顺序、功能协商、视频尺寸/START、前台/屏幕/认证
结果回复、视频心跳、H.264 与 PCM16 发送、输入解析及 Remote Touch 手势映射。
`aoa_probe.py` 仍默认仅枚举；选择 `--phase session` 时也使用完整会话。

## 一次准备，集中验证

```bash
git -C ~/CarProjection pull --ff-only
sudo apt-get update
sudo apt-get install ffmpeg
cd ~/CarProjection
bash linux/run-h3-test.sh
```

不要用 sudo 运行整个 bash 入口；它内部只对 USB 和记录器提权，日志目录位于
调用用户的 `~/carlife-tests/时间戳/`。先拔 OTG 数据线，保持网络 SSH。
脚本先运行单元测试与真实 H.264 编解码/直通自检，确认独立现场记录器 ready 后
再启动 180 秒 CarLife 会话；看到 waiting_for_host 再接车机。每轮录像、声音和
输入都已准备好，不需要测试到某一步才补相应代码。

默认画面是按 HU 尺寸生成的动态测试图，10 fps、单线程 baseline H.264。
启动后播放约 3 秒 440 Hz 间歇测试音，48 kHz/单声道/PCM16LE，经 TTS 通道发送。
gain=0.03 是 PCM 振幅倍率，不等于车机听感音量百分比。测试音结束后视频/心跳
继续，避免整场实验持续响音。触控/按键产生 `input_event`，包含原始 PAD 事件和
与 Android 桥接一致的 knob/wheel/touch/media_key 映射；double tap 点击与
double tap 滑动的 wheel 映射可一起验证。当前没有 Linux CarPlay 消费者或桌面 UI，
输入记录不会凭空操控 iPhone，也不会在测试图上移动光标。

可一次准备几种组合，分别运行，每轮会自动建立新日志目录：

```bash
bash linux/run-h3-test.sh --audio off
bash linux/run-h3-test.sh --audio tts --sample-rate 16000
bash linux/run-h3-test.sh --audio media --sample-rate 48000 --channels 2
bash linux/run-h3-test.sh --fps 5 --gain 0.01
```

只有系统仍稳定且上一轮清理成功时再运行下一轮；曾有失联，诊断入口仍保留。
底层 UDC/内核/供电问题无法由握手代码绕过，发生失联先保存独立记录器日志。

## 2026-10-06 实车结果与修复

首次 Lexus 日志已收到 51/52/53，并在 18d1:2d00 模式 ENABLE；因此已验证
原车 AOA 握手路径。之后 HU 再次 GET_PROTOCOL，旧代码因只处理 initial 模式而
STALL；现在两种模式均响应版本查询和字符串请求，重复 START 完成 ACK，
已在 accessory 模式时保持当前连接，不重复解绑。
Android 参考实现见 [f_accessory.c](https://android.googlesource.com/kernel/common/+/0e3db17d01c9/drivers/usb/gadget/function/f_accessory.c)。

本次在第一条消息处出现 `CarLife payload length mismatch`，还没有视频 INIT/START。
当前日志不足以确定是报文布局、重发还是 USB 收包问题，不能视为音视频不兼容。
新版 `carlife_framing_error` 仅记录外层 8 字节头、内层 8/12 字节头及长度，
不输出媒体负载；遇到错误仍退出，不猜测同步位置或丢弃数据继续握手。
`cleanup_stage: udc_unbound` 与 `cleanup_complete` 分别确认解绑返回与整轮清理结果。

更新后仍运行 `bash linux/run-h3-test.sh`。请保留该轮 `session.jsonl`、
`system.jsonl` 和 `recorder.stdout`；若会话输出停在 unbind_udc，这三份文件可区分
输出截断、进程等待和系统失联。独立记录器默认运行 240 秒，待其结束再收集。

## 自定义视频与音频源

```bash
bash linux/run-h3-test.sh --video-file /path/demo.mp4
bash linux/run-h3-test.sh --video-file /path/matching.h264 --copy-video --fps 10
bash linux/run-h3-test.sh --pcm-file /path/audio.pcm --audio-seconds 30
```

普通 video-file 会缩放/补边到 HU 尺寸并编码；copy-video 则只整理 Annex B/AUD 后
直通，输入必须是 H.264，尺寸和容器帧率匹配协商参数，否则明确拒绝。
裸 Annex B 没有容器时间戳，播放帧率按 --fps 显式声明；不依赖 ffprobe 猜测的 tbr。copy 源不
重编码，但仍使用 FFmpeg 处理容器/位流。PCM 文件须与选定采样率/声道一致且为
PCM16LE，测试源最多 16 MiB，循环播放至 audio-seconds 到时；不能把 WAV 头当 PCM。
这是已有媒体源接线入口，不包含系统声音捕获、CarPlay 接收或 iPhone 连接管理。

## 当前边界

**这是待真车验证的 Linux MD 移植，不是已经可用的 CarPlay 盒子。** Android
MediaProjection、Accessibility、Binder 和 UI 不能原样运行于无桌面的 H3；这里用
Linux 媒体源和输入事件出口替代平台接口。CarPlay 接收端仍未接入，通话/麦克风/
导航应用业务没有实现。认证仅保持安卓端 STATISTIC_INFO 后 result=true 的行为，
不是完整密码学认证；若 HU 要求 CONTENT_ENCRYPTION，停止发送未加密媒体并记录。
MODULE_CONTROL 与 RSA 公钥回复作诊断记录，与当前 Android 的处理范围一致。
起始 18d1:4ee7 + vendor interface 仅为实验身份，不实现 ADB。
若车机只识别特定 Android 初始接口，可能根本不发送 AOA 请求，日志可用于区分此情况。

## 桌面或真车执行

**2026-10-05 更新：出现过 SSH 失联/疑似整板失去响应，默认改为只枚举。**
先按 [H3 故障排查](H3_DIAGNOSTICS.md) 保存现场并分阶段测试。
FunctionFS 同步 bulk read/write 即使使用 O_NONBLOCK 也可能等待 USB 完成，
旧版在主循环直接调用它们，会停住 ep0 处理与软件期限。新版 session 阶段
使用两个有界队列和独立收发线程；主循环每 5 秒输出 `probe_alive`。
它只能隔离用户态等待，无法修复内核/UDC 锁死。清理先解绑 UDC，再等待线程，
确认退出后才关闭端点；解绑出错或线程未结束时拒绝继续恢复其他 USB 驱动。

先完成 `firmware/wukongpi-h3/BLUETOOTH.md` 的开机测试。USB 实验本身不依赖蓝牙。
**必须通过 Wi-Fi/网线 SSH 操作，不可依赖 USB 串口保持连接。**

```bash
cd ~/CarProjection
sudo -v
sudo python3 -u linux/aoa_probe.py --release-g-serial --phase bind --duration 30 2>&1 \
  | tee ~/carlife-aoa-probe.log
```

阶段：`bind` 只测试初始 USB 枚举（默认，不应期待 CarLife 连接成功）；
`aoa` 加上 AOA 切换但不读写 bulk；`session` 才进行实验 CarLife 收发。
只有前阶段系统稳定且清理成功，才进入后阶段。三者均绑定 FunctionFS/UDC，
任何阶段都不能保证避开底层内核/硬件异常。
启动后，把板子的 USB OTG 数据口接车机 CarLife 数据口，进入车机 CarLife 页面。
普通手机不会自动作为 AOA 主机发起本实验握手。桌面连接电脑可以先测试 USB 枚举，
安装 CarLife 车机端、已验证有线连接的手机可以作为 HU 测试对端；选择有线模式，
并确保该手机承担 USB Host 角色。它与普通手机连接的情况不同。
电脑需另有 AOA 主机程序才会发送协议请求。板子此时承担原先 Android 手机的 USB
Device/MD 角色；车机承担 USB Host/HU 角色。
即使没有插 USB，程序也应立即打印 `probe_start`、启动阶段和 `gadget_bound`。
如果日志为空，先确认 sudo 验证完成，并使用上面的 `-u` 和 `2>&1` 捕获启动错误。
启动阶段有 30 秒计时器，运行有 duration+5 秒后备计时器，清理有 20 秒计时器；
可被信号中断的阻塞会报告最后阶段并尝试清理。
内核不可中断等待不能靠 Python 计时器强制终止，应结合进程状态/内核日志诊断。
如 `g_serial` 正被打开的 ttyGS0 占用，程序会拒绝卸载；请关闭该 USB 串口连接，
用网络 SSH 重试。程序只操作自己的 gadget，拒绝解绑其他 configfs gadget。
新版会暂停此前正在运行的 `serial-getty@ttyGS0.service`，退出时尝试恢复它；
其他占用 ttyGS0 的进程会列出 PID/进程名，不自动杀掉。若 ttyGS0 是内核 console，
拒绝在线切换，需要先更改启动配置。通过网络 SSH 测试，先拔掉 OTG 数据线。
`modprobe -r g_serial` 仍可能因内核/UDC 阻塞：命令等待 15 秒，SIGKILL 后最多再
等 2 秒。处于 D 状态的内核等待不能用信号强行解除；程序会报告子进程 PID。
请另开网络 SSH，运行 `ps -C modprobe,python3,agetty -o pid,ppid,stat,wchan:32,comm`，
再读取相应 `/proc/PID/stack` 和近期内核日志。存在未结束的卸载进程时，不重复启动。
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
| carlife_rx / carlife_tx_queued / carlife_tx | 完整接收 / 回复排队 / 实际写入完成 |
| session_handshake stage=video_init_done | 已按 HU 尺寸回复初始化 |
| session_started / session_stats | 开始媒体、发送帧数/音频包数/队列统计 |
| input_event | 触控板、按键、触摸与映射后的原生输入事件 |

事件日志同时显示名称：type=5 是 suspend，type=3 是 disable，不能仅凭它们断定
物理线缆脱落。`bulk_first_rx` 表示收到了原始字节，`carlife_rx` 表示解析出完整帧。
`usb_session_state` 和 `probe_timeout` 汇总接收字节、完整帧及尚未解析字节数；
`probe_alive` 即使接收线程未完成读也应继续输出；`bulk_workers_start` 是开始 bulk 的边界。
若 accessory ENABLE 后没有完整帧，保留结束日志及手机车机端提示，先区分
主机未发数据、端点未交付数据和分片尚未完整，再继续定位；不记录负载内容。

只有 gadget_bound 不代表 AOA 或 CarLife 成功；只有 accessory_reenumeration 也不代表
主机已重新枚举。需结合新模式的 ENABLE 和实际 CarLife 数据判断。
日志不记录 host 提供的 USB 身份字符串全文，也不保存媒体负载。
转发前须在这条实验链路上验证端点读写、重枚举和断开重连行为。

## 本地检查

```bash
python3 -m unittest discover -s linux -v
python3 linux/aoa_probe.py --help
python3 linux/media_selftest.py
```

测试覆盖 FS/HS 二进制描述符、分片/合并 USB 读、CarLife 初始回复、恶意长度拒绝、
仅枚举阶段的隔离、阻塞读不阻塞主循环及分段写入。
它们不能代替真实 UDC、USB 主机和车机测试。完整模拟 HU 会话、23 项单元测试及
真实 H.264 编码/直通/解码已在开发环境执行；H3 性能与 Lexus 兼容性仍待实测。
研究结果见 [Linux MD 项目核查](LINUX_MD_RESEARCH.md)。

官方协议/内核依据：
- https://source.android.com/docs/core/interaction/accessories/aoa
- https://docs.kernel.org/usb/functionfs.html
- Linux 6.18 include/uapi/linux/usb/functionfs.h
