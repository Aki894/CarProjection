# v0.3.2：TTS 兼容音频

针对 MEDIA channel 3 无声、TTS channel 4 已验证可播放的车机，新增独立的「TTS 兼容音频（16 kHz 单声道）」开关。默认关闭，开启后优先于常规 USB MEDIA 设置，并请求 `AUDIO_TRANSMISSION_MODE=0`。

## 使用与实车验证

1. 安装 GitHub Actions 的 `CarProjection-debug-apk`，可覆盖此前使用固定 debug 签名的版本。
2. 开启 TTS 兼容音频，拔插 USB 重连，授予录音与投屏权限。
3. 点「TTS 16 kHz 测试音」，确认车机能播放 2 秒测试音。测试会暂停实时捕获，结束后自动恢复。
4. 播放允许 Android PlaybackCapture 的音乐或视频，查看 `[TTS-AUDIO] PCM active` 和每 2 秒更新的 RMS、peak。
5. 连续播放 5～10 分钟，检查断音、爆音、延迟和车机 TTS 超时；这些现象仍需实车验证。
6. 开导航并播放 BGM，分别确认提示音和音乐是否进入捕获流；不要由测试音成功直接推断导航语音也能被捕获。
7. 关闭音频或从通知栏停止投屏，检查 `NAVI_TTS_END` 和车机音频焦点释放；再连接一次检查恢复。

## 实现

- AudioPlaybackCapture：48 kHz、双声道、PCM16；匹配 MEDIA/GAME/UNKNOWN usage。
- 立体声平均混音，63-tap、7 kHz 低通 FIR，再按 3:1 转成 16 kHz 单声道；滤波历史、采样相位和不完整输入帧跨读取块保留。
- 每包 640 字节，即 20 ms 的 16 kHz 单声道 PCM16，沿用已验证的 CarLife 音频封包格式。
- 每次捕获会话发一次 `NAVI_TTS_INIT`，持续发送 `NAVI_TTS_DATA`，停止或切换时发一次 `NAVI_TTS_END`。USB 已断开时仅清理本地状态。
- 捕获中的静音仍连续发送，保持同一 TTS 会话；暂停音乐不会自动结束 TTS，需关闭音频或停止投屏来释放该通道。
- 兼容模式禁用 MEDIA 测试按钮，并且不自动初始化或发送 MEDIA 数据。
- USB 音频待发送包数量限制为 8；拥堵时丢弃新数据包并计数，避免持续积累音频延迟。INIT/END 不受数据包上限限制。
- 日志的 `captured` 为输入 PCM 字节量，`queued` 为排入 USB 队列的输出 PCM 字节量，`sent` 为写入 USB 输出流成功的输出 PCM 字节量。`sent` 不能证明车机实际播放；`dropped` 为未能入队的数据包数。
- 录音启动失败、读取错误、设置切换、USB 断开、系统撤销 MediaProjection 或通知栏停止投屏均清理捕获资源。

## Android 捕获限制

这是一种音频输出兼容模式，不改变源应用的捕获权限。Android 普通应用只能捕获允许 `ALLOW_CAPTURE_BY_ALL` 且 usage 为 MEDIA/GAME/UNKNOWN 的播放音频。专用 navigation guidance usage、禁止捕获的播放器或其他用户配置文件中的应用可能返回静音。

官方说明：https://developer.android.com/media/platform/av-capture

## 自动验证

JDK 17 下运行 `bash tools/check-audio.sh`。检查输入块边界、reset、右声道混音、PCM 符号和满量程、静音、1 kHz 增益、12 kHz 抗混叠，以及相当于 10 分钟输入的输出采样计数。GitHub Actions 在构建 APK 前运行相同检查。这些检查不替代手机音频捕获和车机长时间播放测试。
