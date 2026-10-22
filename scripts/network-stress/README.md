# 服务端地形同步压力测试

在独立目录启动一个专用服务器与两个真实 Minecraft 客户端，使用正式网络编解码、映射转换、区段存储和调度器。客户端安装与工程相同的 Sodium，关闭 LoD 绘制以集中验证地形同步；不验证画面或 Iris。

需要本工程 Java 21/NeoForge 开发环境、Python 3，以及已有的测试世界。服务器仅绑定 `127.0.0.1`，以离线模式允许两个测试身份登录。脚本复制源世界，每次创建带时间戳的新目录，不修改源存档。会打开两个 640×360 的游戏窗口。

```powershell
.\gradlew.bat writeNetworkStressLaunch -I scripts/network-stress.gradle -PstressPort=54187 --offline --console=plain
python scripts/network-stress/run.py --fixture build/reports/lod-requests/server/smoke-world --port 54187
```

`--fixture` 指向包含 `level.dat` 的开发测试世界目录。第一次运行可去掉 `--offline` 下载所需资源。探针只由这个 init 脚本加入编译；运行结束后正常执行 `build`，会移除探针类，分发 JAR 不应包含 `me/cortex/voxy/audit` 或 `NetworkStressClient`。

验收内容：

- 每端发起 600 个远端缺失需求，超过 512 个在途窗口。首次读取可以返回空载荷，另一个读取者命中已有空气缓存时可以返回序列化空气；后一种情况逐体素验证全空气，要求收到 600 个唯一区段结果。
- 对 2,304 个远景区段先后写入石头、泥土、金块，并重复通知，触发合并和 2,048 项队列溢出。两端最终必须收到并应用全部金块区段，排空后重新读取本地引擎验证最终状态。
- Slow 客户端暂停 socket 读取 120 tick。测试服务器关闭网络压缩，让真实 Netty 不可写状态可以被触发；必须观测调度器因不可写而跳过发送。
- 逐 tick 验证总包数/字节预算和队列上限；要求后台补发扫描结束、修复标记清零、队列排空，且三进程正常退出。

原始日志、启动参数、源世界哈希、双方及服务器的通过标记、`result.json` 保存在 `build/reports/network-stress/run-*`。输出记录发送载荷字节、队列峰值、合并/溢出次数、不可写次数、扫描临时文件字节数，以及整个服务器 JVM 的已用堆采样。载荷字节含通道名及 16 字节封装预留，不含 TCP/IP；堆采样包含游戏与存储分配，不能解释为独立的网络队列内存。

服务器目前按同维度与最大支持半径 32,768 方块过滤；协议没有携带客户端实际 LoD 设置，不能按其更小的视距精确过滤。该测试是双客户端的有限突发负载，不代表大型公网服务器的长期容量。
