# Linux CarLife 手机端（MD）项目核查

核查日期：2026-10-05。检索 web 与 GitHub 仓库，读取候选源代码。
结论只针对已找到的公开代码，不声称所有闭源或未被索引项目都不存在。

| 候选 | 角色和可复用范围 | 能否直接替换 H3 上的 MD |
|---|---|---|
| Doris-duo/CarLife-MD-Lib | README 声明 MD；C++ 协议库、Linux Makefile、protobuf | 不能认定是可用 AOA MD 成品 |
| ApolloAuto/apollo-DuerOS CarLife-Vehicle-Lib | 官方 CarLife 车机侧跨平台协议库，MD-Lib 的上游 | 角色是 HU，需要改角色和 USB 设备端 |
| aa112901/CarProjection 与本 fork | 已在用户 Lexus 验证的 Android MD、AOA、H.264/PCM/输入 | 需替换 Android 平台接口；协议行为可直接对照 |

## MD-Lib 为什么没有直接接入

读取 Doris-duo/CarLife-MD-Lib 的 main，固定提交
`b3cc871f7c5c4b41947a3c512caee76624eaffd9`（2021-11-11）。
许可文件为 Apache-2.0。它的 README 明确称 MD，不能只因为名字或 README 就
认定 Linux AOA 设备端、角色转换和可运行应用均已完成。

实际代码的 `LibSource/modules/CConnectionSetupModule.cpp` 调用
`execSocketForward()` 和各通道 `create*Socket()`；
`utility/CConnectManager.cpp` 使用 ADB 转发命令与 TCP 客户端连接。
没有发现 FunctionFS/configfs、UDC、AOA 51/52/53 设备端实现。
`include/CCmdChannelModule.h` 仍有接收 ProtocolVersionMatchStatus/MDInfo 的 HU
方向注册接口，`CarLifeLibTest.cpp` 也仍发 HU 视频初始化/START 并接收视频心跳。
因此，README 的 MD 声明与完整可用的 USB MD 证据之间有差距，需要审计和移植，
不能以“只编译库就能接原车”作为开发计划。

`LibSource/Makefile` 有 Linux/QNX 构建基础，但仍引用 protobuf-2.5.0 时代接口和
生成文件，并链接 `/usr/local` 下的依赖。新 Debian 上需处理构建适配；即使构建
通过，也没有补齐我们需要的 USB gadget。库没有在本次硬件上运行验证。

GitHub 的 CarLife MD 仓库检索结果还包含多个同名 fork；当前没有找到证据足够的
“Linux MD + AOA gadget + 可直接运行媒体应用”项目。Linux CarLife HU、普通
Android Auto HU、CarPlay 接收项目和无线 CarLife 商业 SDK 不能混为同一角色。

## 本次采用的实现

继续用已验证可触发 AOA accessory ENABLE 的 Linux FunctionFS 传输层，根据
本仓库的 Java 行为和 proto 字段重写一个无 Android 依赖的会话。没有导入这个旧
C++ 库，也没有把网络客户端代码误当成 USB 设备端。这样明天集中验证的重点是
真实 UDC 与 HU 兼容性，不再人为缺少握手后面的媒体/心跳代码。

这不是为 Linux 安装官方 CarLife 手机 App，也不提供官方导航、电话或无线 MD
配对业务；它是用于 CarLife2CarPlay 的精简 MD 会话及媒体/输入接口。

源码入口：
- https://github.com/Doris-duo/CarLife-MD-Lib
- https://github.com/Doris-duo/CarLife-MD-Lib/blob/main/LibSource/modules/CConnectionSetupModule.cpp
- https://github.com/Doris-duo/CarLife-MD-Lib/blob/main/LibSource/include/CCmdChannelModule.h
- https://github.com/ApolloAuto/apollo-DuerOS/tree/master/CarLife-Vehicle-Lib
- https://github.com/aa112901/CarProjection
