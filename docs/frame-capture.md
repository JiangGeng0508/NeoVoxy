# 帧采样与第一批进度

roadmap 第一批的采样、模型等待、工作调度和保存预算代码已完成，完整 GPU 自动回归通过。版本仍为 0.2.19；同场景性能基线与 Iris/Vista/SeedGen 完整游戏实机验收仍待完成，不能据自动测试推断 FPS 改进。

## 使用和口径

预热 30 秒后运行 `/voxy debug capture 60`，结束时写入游戏目录的 `voxy-frame-capture.txt`；再次运行命令可提前停止。

- `frame interval` 是相邻两次 `RenderFrameEvent.Pre` 的间隔，包含两帧之间的等待；首帧没有间隔样本。
- `frame render` 是同一游戏帧的 Pre 到 Post，包含游戏渲染。该事件范围不含随后交换缓冲、限帧等工作，不能代替完整帧间隔。
- CPU 阶段取 `TimeSampler` 的原始纳秒累计值。每个游戏帧分别累加主视口与所有副视口的调用，无工作时为零；一次主视口加两次 Vista 渲染仍只是一帧。阶段存在包含关系，不应把所有行相加。
- `samples` 列是该行的有效样本数。p50/p90/p95/p99 采用 nearest-rank 定义；F3 仍保留滚动显示，其值不参与报告百分位。
- GPU 以提交时的帧号、主／副视口分类和会话编号关联结果。只在末尾时间戳可用后读取，未就绪不等待、不记零。某类视口在一帧中的全部查询返回后，该帧才进入此类 GPU 百分位；报告同时给出有效视口数、完整帧数、未取得结果的视口数。
- GPU 查询最多保留 64 个在途视口批次，每批最多 64 个标记。容量不足或无效批次会丢弃整批测量并计数，CPU 帧样本仍保留。
- F3、capture 和 profile 的 GPU 请求互相独立；关闭 F3 或结束一种捕获不会关闭另一种捕获的查询。旧捕获延迟返回的结果不会写入新会话。
- `Upload stream forced glFinish calls` 是本次捕获期间上传流同步兜底的实际调用次数。`/voxy debug stats` 中对应计数为进程累计值，stats reset 不会清零它，以免破坏捕获差值。

40 ms 以上的帧间隔由独立 watchdog 采集渲染线程及 Voxy 工作线程栈，包含 `Dedicated Voxy Worker #...`，最多 40 份。栈采样仍有 safepoint 开销；GPU 查询和每 500 ms 的调试快照也属于测量开销。对照时应使用相同采样方式。

`/voxy debug profile` 的 GPU 汇总按主／副视口分别显示已完成查询的视口数、涉及的游戏帧数和平均 ms/viewport；它与 capture 的完整帧百分位是不同口径。

## 模型等待与工作调度

capture 与 profile 独立持有工作计时窗口。`queue/<service>` 为入队到开始执行，`work/<service>` 为一次服务调用，`model wait/mesh` 为登记模型依赖到全部依赖就绪。它们使用原始纳秒，显示 p50/p95/p99、计数、均值与最大值；每阶段百分位仅使用最近 8192 个已完成样本，计数、均值与最大值覆盖整个窗口。窗口开始前已入队的任务和结束时仍未完成的等待不计入；关闭一种采样不影响另一种。保存批次吸收的额外任务仍单独记录排队等待，整个批次只记一次工作调用。

每区段只保留一个建面任务，包括执行和等待状态；缺模型时一次登记本区段、替换数据及已读取邻接面的依赖，退出可运行队列并释放区段引用。完成通知在发布模型映射后发送，登记后重新检查就绪，覆盖提前完成和重复通知。恢复时重新取得区段；执行中的重复更新合并为一次后续工作。共享模型逐方块通知，流体内部模型 ID 使用独立依赖空间，失败烘焙发布空气回退后同样唤醒。关闭清空等待索引、移除监听器并等待运行上下文释放。

`RSSQ/WAIT/ALL/TFC` 分别为可运行、等待模型、全部任务（含正在执行）、缺模型中断次数。模型上传预算仅使用可运行数量；遍历器及未完成工作判断继续使用全部数量。

统一服务池按每个帮助工作线程的固定权重轮转：摄取 4、建面 4、保存 2、导入 1。空队列和受限服务立即让出剩余次数，积压不放大权重；第二批网络后台服务将使用权重 2。嵌套帮助执行先消费自己的轮次，避免干扰外层调度。

保存一次仅处理一个世界的一个批次，达到 64 区段、4 MiB 或 4 ms 后归还工作线程；在序列化单个区段之后检查，不能打断一次慢序列化或数据库提交。切换世界也结束本次调用。关闭先停止服务并等待正在执行的调用，再完整排空剩余队列。提交失败重新标记 dirty 并释放保存引用，允许后续重试。

## 对照包

仅含原始采样修正的 0.2.19 对照包位于 `build/reports/first-batch/baseline/voxy-0.2.19-sampling-baseline.jar`。它由 `ab974fd` 的源码加本次继续前已有的采样修改单独编译，替换分发包中的全部项目类；资源及嵌套依赖与优化包一致。源码快照、逐文件 SHA-256、编译入口与日志，以及 JAR 的 SHA-256 在同目录。该对照包保留原调度、模型重试和保存逻辑，不含新增工作等待统计。

对照包仅完成独立编译与内容检查，尚未重新启动游戏或测量场景。测试时分别安装对照包和 `build/libs/voxy-0.2.19-beta+neoforge.jar`，按 roadmap 使用相同世界副本、路线、分辨率和采样方式，各预热 30 秒、捕获 60 秒、重复三次。帧时间与 GPU 口径相同，新增工作等待行只在优化包可用。

## 自动验证

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat build --offline --console=plain
.\gradlew.bat build --offline --console=plain -PverifyGpu=true
```

新增 CPU 回归覆盖多视口、无 LoD 帧、首帧与重复事件、原始时间与滚动值分离、延迟 GPU 的帧对应、完整视口组统计、过期会话、查询容量和释放。GPU 测试 `GPUTimingGpuTest` 使用真实时间戳，验证 F3 关闭期间 profile 继续采样，以及池内、在途和未提交查询的释放。

2026-10-31 显式 GPU 构建中，162 项 CPU 测试通过；8 个 GPU 测试类均因 `GpuTestContext` 报 `OpenGL 4.6 is required` 在上下文初始化失败，未执行渲染验证。没有降低原有 GPU 要求或把显式失败改成跳过。本地证据保存在 `build/reports/gpu-capture-attempt.json` 和 `build/reports/gpu-capture-attempt/`。

同日默认标准 `build --offline --console=plain` 通过：194 项中 162 通过、0 失败、32 项 GPU 测试按默认配置跳过；包含 11 项新增 CPU 回归。Mixin 检查通过（93 个注册、103 项目标检查、281 项成员检查）；语言键检查通过（中英各 232 键）。分发包仍为 `build/libs/voxy-0.2.19-beta+neoforge.jar`。

详细标准构建结果见 `build/reports/tests/testJunit/index.html`、`build/reports/mixins/check.json` 和 `build/reports/lang-keys/check.json`。完整场景基线、Iris/Vista 集成及帧时间改进结论仍待按 roadmap 的世界副本、路线、分辨率与三次重复方案验证。

2026-10-31 继续第一批后，标准 `build --offline --console=plain` 通过：225 项中 193 通过、0 失败、32 项 GPU 默认跳过。新增 31 项 CPU 验证，覆盖固定权重公平性、限流恢复、并发计数与嵌套帮助、保存数量／字节／时间边界、跨世界分批、失败重试与关闭排空；模型通知竞争、共享／流体依赖、等待期间更新、引用释放和关闭；独立工作采样窗口及有界百分位。SeedGen 字节码测试核对实际使用的任务类、处理签名、队列 poll、两处结果发布和优先级注入点。Mixin 检查为 93 个注册、103 项目标和 281 项成员检查；中英各 232 键通过。日志和本次证据在 `build/reports/first-batch/`。

同日晚间 `build --offline --console=plain -PverifyGpu=true` 实际执行全部 225 项并通过，GPU 32 项无失败或跳过。环境为 NVIDIA RTX 4060 Laptop、驱动 591.74、OpenGL/GLSL 4.6/4.60、shader int64 可用、Java 21.0.10、Windows 11。单独 GPU 时间戳检查也实际通过。`gpu-build.log`、`gpu-summary.json`、`gpu-results/` 与 `gpu-run.json` 保留完整运行证据；此前失败属于当时上下文环境，未因此降低 GPU 要求。GPU 自动回归不覆盖真实光影包、Vista/SeedGen 完整游戏组合或场景吞吐。
