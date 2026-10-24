# NeoVoxy × PingWheel 联动方案与开发提示词

调研日期：2026-10-23。本文保留原始实现计划；第一阶段实现与实际验证范围见
[PingWheel 兼容](pingwheel.md)及[隔离验收](../scripts/pingwheel-smoke/README.md)。

## 推荐范围

第一阶段：让玩家用 PingWheel 标记原版区块已卸载、但 NeoVoxy 已有本地 LoD 数据的远处地形。继续使用 PingWheel 的按键、频道、网络包、标记样式、声音和过期机制。

默认保留原版方块、实体及 PingWheel 已有 Sable 船体选点；仅在原有选点未命中时查询 NeoVoxy。首版采用异步 CPU 分层体素射线，覆盖普通地形、负坐标、多维度和数据生命周期。粗 LoD 只能提供近似地形位置，不能承诺方块模型表面级精度。

第二阶段：按实际需求增加 GPU 可见表面选点、世界曲率下的选点及标记投影一致性。远处动态实体、运动船体、永久路标、独立地图同步不包含在首版中。

## 已核实的依据

- 本仓库：Minecraft `1.21.1`、NeoForge `21.1.244`、Java `21`，mod ID 为 `voxy`。
- PingWheel 官方 [Modrinth 项目](https://modrinth.com/mod/ping-wheel)提供 NeoForge 1.21.1 的 `1.12.2` 构建；本次以该构建为兼容基线。
- 官方源码地址：[LukenSkyne/Minecraft-Ping-Wheel](https://github.com/LukenSkyne/Minecraft-Ping-Wheel)。GitHub API 本次触发限流，因此以下入口通过官方发布 JAR 的 `javap` 字节码核实，未把其他版本源码当作该构建依据。
- 发布文件：[Ping-Wheel-1.12.2-neoforge-1.21.1.jar](https://cdn.modrinth.com/data/QQXAdCzh/versions/Zrh2Fmn9/Ping-Wheel-1.12.2-neoforge-1.21.1.jar)，Modrinth version ID：`Zrh2Fmn9`。
- JAR SHA-256：`fcff3528dfc644dd510e702c51af477c11ddbee07895b9ba906e3f0dbc9c4077`。
- `nx.pingwheel.common.core.PingController.performPingAction(float)` 调用 `Raycast.traceDirectional(Vec3, float, double, boolean)`；空结果或 `MISS` 时，仅在 `ModContext.HasDistantHorizons` 为真时调用 `traceDistantAsync`。
- 普通射线距离为 `min(ClientConfig.getRaycastDistance(), ClientConfig.getPingDistance())`。默认值分别为 `1024` 和 `2048`；`PingManager.acceptPingPacket` 在 `pingDistance < 2048` 时才进行接收距离过滤。不能把两个设置以及 `2048` 的语义混为一谈。
- `PingLocationC2SPacket` 已携带频道、`Vec3` 坐标、可空实体 UUID、sequence 和 dimension；地形选点可以使用已有协议。
- `ClientVoxyMixinPlugin` 已实现可选 mod 的客户端 Mixin 注册；`client/compat/xaero/XaeroGate.java` 可参考版本和端侧隔离方式。
- `WorldEngine`、`WorldSection`、`Mapper` 提供 LoD 数据与映射能力；`client/compat/xaero/SurfaceExtractor.java` 展示了缓存读取、`loadSectionLocal`、已知区域判定和引用释放方式。当前工作区的部分能力属于未提交改动，实现时必须以当时工作区为准。
- `acquireIfCachedLocal` 不能仅凭名称视为绝对无阻塞：LRU 晋升竞争可能进入加载路径。普通 `acquireIfExists` 也不能未经检查就用作“只查本地”入口。
- 渲染侧有 `Viewport`、普通/Iris 管线、LoD 深度及 `RenderProperties`，但没有在本次检索中发现可直接复用的通用 LoD 选点 API。深度缓冲可访问不等于已具备可靠 GPU 选点。

## 实施分解

| 步骤 | 交付 | 要点 |
| --- | --- | --- |
| 1 | 可选依赖与兼容入口 | 锁定构建；仅客户端启用；未安装/不支持的版本正常启动 |
| 2 | 本地 LoD 射线服务 | 分层遍历、命中精度、未知数据、预算和引用生命周期 |
| 3 | PingWheel MISS 分支桥接 | 异步上下文快照；原有包；DH 回退；每次操作最多发送一次 |
| 4 | 配置与文档 | 总开关；距离单位；中英文；精度及曲率限制 |
| 5 | 自动与游戏验证 | 算法测试、Mixin 检查、多人及渲染组合验收 |

首版距离建议：在原调用距离参数内补足 LoD 选点，并进一步受 NeoVoxy 实际 LoD 范围与联动配置限制。因此默认有效上限不会因为安装联动而自动变成几千格。扩大到完整 LoD 半径应作为独立后续功能，明确处理 PingWheel 设置语义和接收端过滤。

首版曲率建议：`earth_curve_ratio != 0` 时停用新增 LoD 选点，并在配置说明中明确原因；已有 PingWheel 行为继续保留。数据空间直线不等于曲率画面中的视线，不能把未经校正的结果标为“准确命中”。

## 可直接复制的开发提示词

```text
你正在 D:\WorkSpace\Gitea\NeoVoxy 中开发 Minecraft 1.21.1 / NeoForge 21.1.244 / Java 21 模组。请为 NeoVoxy 实现 PingWheel 可选联动的第一阶段，并完成相应验证。

目标
让玩家通过 PingWheel 标记“原版区块已卸载，但 NeoVoxy 已保存 LoD 数据”的远处静态地形。复用 PingWheel 的输入、频道、网络协议、标记渲染、声音和过期机制。仅在普通选点为空或 MISS 时补充 LoD 选点，原有近处方块、实体及 Sable 船体处理保持原语义。

先检查
1. 阅读适用的 AGENTS.md、README.md、docs/testing.md 及当前工作区改动。工作区有正在进行的 Xaero/存储/配置改动，保留它们，不回滚、不覆盖、不顺手重构。
2. 兼容基线为 Ping-Wheel-1.12.2-neoforge-1.21.1.jar，Modrinth version ID 为 Zrh2Fmn9。通过目标 JAR/对应源码重新核实 mod ID、方法签名、Mixin 注入位置、配置和网络调用。不要从其他 Minecraft 版本猜接口。
3. 已核实入口：nx.pingwheel.common.core.PingController.performPingAction(float)；nx.pingwheel.common.math.Raycast.traceDirectional(Vec3,float,double,boolean)；普通 MISS 后的 traceDistantAsync 当前受 HasDistantHorizons 控制。不要通过伪造 HasDistantHorizons 来接入 NeoVoxy，也不要给 NeoVoxy 增加对 DH 的必需依赖。
4. 检查本仓库 WorldEngine、WorldSection、Mapper、ActiveSectionTracker、SectionStorage，以及 client/compat/xaero/SurfaceExtractor.java 的本地读取和已知区域处理。先确认线程与副作用，再决定复用方式。

实现结构
5. 建议新增 client/compat/pingwheel/ 下的版本门控、桥接及请求管理；通用射线算法可放 client/core/picking/，不得依赖 PingWheel。名称可以按仓库风格调整。
6. 依赖使用 compileOnly 或现有等价可选编译方式，并用精确版本/版本 ID 避免 Modrinth 同版本多加载器歧义。不要内嵌 PingWheel。通过 ClientVoxyMixinPlugin 在客户端且受支持版本存在时注册 Mixin；未安装、未知版本、专用服务端不得加载 PingWheel 客户端类或申请渲染资源。初始只声明实际验证过的版本支持。
7. 优先使用存在的公开扩展点；若无合适扩展点，用窄范围 Mixin 接入 performPingAction 的普通 MISS 分支，在 DH 回退前发起 NeoVoxy 请求。避免覆写整个控制器或修改全局 Minecraft 拾取/交互距离。
8. 查到 LoD 命中后，在客户端线程通过既有 PingLocationC2SPacket 和平台网络服务发送，保留频道、sequence、dimension 的含义，实体 UUID 为空。不要新增一套 NeoVoxy ping 网络协议。若没有可复用的发送方法，可以封装已有包发送，但不能假设存在未核实的 API。
9. NeoVoxy 不可用或查询无结果时，恢复原有 MISS/DH 回退语义。异步接管后不能让原分支同时发送或启动 DH 查询；每次操作最多发送一个 ping。续接回退不得再次进入自己创建新请求的入口。

射线算法与数据规则
10. 首版使用异步 CPU 分层体素遍历，查询既有本地 LoD，不加载/生成原版区块，不向服务器请求额外 LoD，不扫描全库存储 key。先按区段/AABB 跳过范围，再按体素遍历，避免逐格触发磁盘读取。
11. 优先使用可用的细层数据；细数据明确为空时不得被粗层实体覆盖。缺少细数据时可以用粗层近似，但结果要记录 lodLevel/精度。正确支持 uniform section，不能把内部 data == null 当作没有数据。
12. 区分已知空气、未知数据、未保存区段和读取失败。未知不是空气；若前方未知导致无法判断遮挡，保守返回 INCOMPLETE，不得穿过未知区域声称后方目标可见。不生成默认地面坐标或世界原点 ping。
13. 正确处理负坐标 floor、区段边界、轴向射线、零方向分量、边/角同时穿越、起点位于体素内、维度高度与世界边界、非有限输入。返回世界空间命中点和合理表面方向，并明确粗 LoD 的近似误差。
14. 流体/透明方块筛选参照 traceDirectional 的 crouching、VISUAL/OUTLINE、NONE/ANY 语义；LoD 缺少原版形状时明确使用近似规则，不把“所有非空气体素”无条件当实心，也不伪造精确模型碰撞。
15. 所有可能阻塞的缓存晋升、存储读取及遍历都放有界后台任务。审计 acquireIfCachedLocal 的 LRU 竞争加载路径；普通 acquireIfExists 可能有额外副作用，不可直接认定为只查本地。需要加载时使用已核实的 local-only 路径。
16. WorldEngine/WorldSection 引用正确成对释放。按现有并发协议复制所需数据，不在长时间持锁期间做 I/O 或遍历整条射线，不在后台访问活跃 ClientLevel/Entity 的非线程安全状态。错误、取消和世界关闭同样释放资源。
17. 仅用户触发 ping 时查询，不做每帧扫描。限制在途任务、局部缓存、区段读取数和遍历工作量；超预算/超时取消，不能静默发出不完整结果。记录实际耗时和读取次数，避免无界线程池及请求堆积。

距离、生命周期与兼容
18. 首版曾遵守 traceDirectional 实际收到的距离；2026-10-24 按远距离标记需求调整：LoD 查询独立使用 NeoVoxy 有效 LoD 范围及非零联动上限的最小值，0 跟随完整 LoD 视距。普通方块/实体射线仍是 min(raycastDistance,pingDistance)，默认 1024/2048。接收端 pingDistance >= 2048 会跳过距离过滤，调低时仍可能过滤远处标记。不修改用户已有 PingWheel 配置。
19. 用触发时的 cameraEntity 眼睛位置、方向和 partial tick 构建请求，沿用 PingWheel 的视角语义。第三人称不能随意改为屏幕中心相机原点；不接入 Vista 副视口或阴影视图。
20. 发起时捕获连接、世界/维度、engine generation、频道、sequence、射线和配置状态；完成时在客户端线程核验有效性。退出、换服、同维度世界重载、切维度、voxy reload、关闭渲染或联动、后续操作使旧请求失效时，丢弃旧结果。不要在回调里读取新的 sequence 然后发送旧射线结果。
21. NeoVoxy 未启用、渲染关闭、数据不可用或 earth_curve_ratio 非零时停用新增查询，保留原 PingWheel 行为，并说明曲率模式暂不支持。普通/Iris 无曲率场景都需要验收；首版不宣称按最终像素精确选点。
22. 本功能只增加发送端地形选点。服务器和接收端继续遵守 PingWheel 本身安装与协议要求；不要宣称只装发送端 PingWheel 就能在任意服务器广播。接收玩家只有 PingWheel 而没有 NeoVoxy 的组合需实测已有协议是否正常显示。

配置与文档
23. 按 VoxyConfig、VoxyNeoForgeConfig、VoxyConfigMenu 的现有方式增加联动开关。联动上限使用明确单位，0 如表示跟随距离需写明。未安装 PingWheel 时隐藏相关菜单；中英文语言键齐全。候选字段 pingwheel_lod_picking、pingwheel_max_distance_blocks，按现有规范定名。
24. 说明首版是近似 LoD 地形选点，不增加实际交互距离，不跟踪远处动态实体/运动船体，不支持曲率下精确对齐；说明原版数据过期、粗 LoD 与原模型差异，以及默认距离仍受 PingWheel 限制。

验证与交付
25. 为算法与请求生命周期写有意义的测试：近处命中不被替换；仅 LoD 命中；已知空气与未知；粗细层覆盖；负坐标/边界/零分量；流体规则；距离/预算上限；乱序完成；取消/换世界；无重复发包；没有本地数据时无远程请求、无区块加载。
26. 把新增 Mixin 纳入现有 validateMixins。静态检查必须能解析目标 PingWheel 构建，不用 require=0 或无条件跳过掩盖签名不匹配。按 docs/testing.md 运行适用 testJunit 与 build/check；若没有更改 GPU 路径，不把全量 GPU 测试作为默认前提。
27. 游戏验收覆盖：不装 PingWheel；安装受支持版本；关闭联动；专用服务端启动；单人及多人；原版区块卸载后的远山；近处方块/实体；天空与数据空洞；负坐标；切维度/reload；Sodium；Iris 开关；可用时检查 DH/Sable 共存。
28. 性能检查记录硬件、射线距离、LoD 层级、冷热缓存条件、读取量及延迟。保持主线程无同步磁盘访问。编译成功不等于完成游戏验收；缺少环境时如实列出未验证项。
29. 最终交付代码、测试、配置说明、支持版本、验证结果及仍有限制。保留原有未提交改动，不自动提交或发布。

本次完成上述第一阶段。GPU 深度选点、完整 LoD 半径扩展、曲率下选点和 PingWheel 标记投影统一列为后续计划，不混入本次完成声明。
```

## 第二阶段的技术注意事项

GPU 方案可让命中更接近实际渲染的 LoD 表面，但需要明确深度属于哪个相机、哪一帧、哪条管线、哪个不透明/透明阶段。原版与 LoD 混合深度不能自动当作 LoD 命中来源；天空、手持物、实体及副视口也必须区分。

异步读回需要让深度与当帧矩阵、相机位置、视口尺寸、世界 generation 绑定，处理 PBO/fence 的资源释放和过时结果，避免每帧同步 glReadPixels。重建坐标需遵循 RenderProperties 的深度范围及 reverse-Z 约定，同时处理 Iris/TAA。

世界曲率会同时影响“选中哪个地形点”和“世界坐标 ping 在屏幕哪里显示”。仅反投影并逆变形选点仍不足以保证 PingWheel 图标贴合弯曲地形；应一并验证其世界到屏幕投影、距离显示与普通玩家视角语义，再开放该组合。
