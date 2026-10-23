# PingWheel 兼容

第一阶段为客户端增加近似本地 LoD 地形选点。兼容基线是 Minecraft 1.21.1 / NeoForge、
PingWheel **1.12.2**，Modrinth version ID `Zrh2Fmn9`。其他版本不注册兼容 Mixin。
PingWheel 是 `compileOnly` 可选依赖，不内嵌、不自动安装；专用服务端不注册客户端兼容入口。

正常使用 PingWheel 的按键、频道和标记：原有方块、实体和 Sable 船体选点优先，只有
普通选点为空或 MISS 时，才在 DH 回退前查询 NeoVoxy。命中后使用原有
`PingLocationC2SPacket`，实体 UUID 为空；样式、声音、过期和服务器协议沿用 PingWheel。
服务器及接收端仍需满足 PingWheel 自身安装与协议要求。

## 配置

JSON：`config/voxy-config.json`；NeoForge TOML：`config/voxy-client.toml`。
受支持 PingWheel 存在时，Sodium 的 Voxy 设置显示 PingWheel 页，提供中英文说明。

| JSON 字段 | 默认 | 含义 |
| --- | --- | --- |
| `pingwheel_lod_picking` | `true` | 普通 MISS 后查询本地 LoD |
| `pingwheel_max_distance_blocks` | `0` | 额外距离上限，单位方块；范围 0–32768 |

有效距离为 **PingWheel 本次普通射线距离、NeoVoxy LoD 半径、非零联动上限** 的最小值。
0 表示跟随前两者。PingWheel 普通射线距离是 `min(raycastDistance,pingDistance)`，默认
1024/2048，因此默认最多 1024 方块；本兼容不会修改 PingWheel 配置，也不会扩大方块交互距离。
接收端 `pingDistance >= 2048` 跳过距离过滤的行为不改变发送端射线语义。

## 选点与数据限制

- 从触发时 cameraEntity 的眼睛位置与 partial tick 方向出发，第三人称也沿用该语义。
- 在后台按 32 方块区段 AABB 跳过范围、分层遍历体素；使用细层已知数据，已知细层空气
  不会被粗层实体覆盖。缺少细数据时允许 LOD1–4 近似；返回结果记录层级和法向。
- 已知空气、缺失区段、未知空气、读取失败分开处理。前方未知时返回 INCOMPLETE，
  不穿过数据空洞发出后方地形标记，不生成原点或默认地面坐标。
- 普通模式近似 VISUAL/NONE；潜行近似 OUTLINE/ANY，允许流体。使用空世界的方块形状
  判断是否可选，再用完整体素近似表面；依赖实时世界或实体的特殊形状不承诺精确支持。
  玻璃在原版 VISUAL 下不可选，潜行 OUTLINE 下可选。
- 所有层级均为体素盒近似，LOD0 也不保证楼梯、草、玻璃等模型表面精度；LOD4 体素边长
  16 方块，误差可达一个体素。保存数据可能过期，不跟踪远处动态实体或运动船体。
- NeoVoxy 禁用、渲染关闭、renderer 不存在、联动关闭或 `earth_curve_ratio != 0` 时保留
  原 PingWheel 行为。世界曲率选点和标记投影校正、GPU 可见像素选点属于后续工作。

## 后台与生命周期

只在用户执行 ping 时查询。最多一个后台任务，无排队请求，后续操作使旧结果失效。
每次请求最多 256 次区段查找、32768 个遍历节点、16 个缓存区段快照，加上当前五层快照
最多约 5.25 MiB 稠密数组，
预算 250 ms。磁盘/锁等待不可强制中断，完成读取后重新检查时间；超预算不发不完整命中。
引擎引用保护存储关闭，区段引用在复制完快照后立即释放，异常与取消也释放。
查询只使用 `acquireIfCachedLocal` / `loadSectionLocal`，不请求远程 LoD，不加载或生成原版区块，
不扫描存储 key。修正缓存引用竞争重试丢失 local-only 加载器的问题。

完成时在客户端线程核验连接、世界对象、维度、cameraEntity、renderer/engine 身份、
配置快照和操作代数。换服、切维度、同维度重载、renderer 重建、reload、配置变化或
后续 ping 会丢弃过期结果。频道、sequence、dimension 使用触发时快照，发送至多一次。
LoD 无结果时直接续接原 DH API，不重新执行控制器；因该 API 内部从当前相机读取原点，
只有眼睛位置仍匹配时才续接。DH 回调也核验上下文并复用单次发送保护。

DEBUG 日志 `PingWheel LOD pick` 记录状态、耗时、区段查找次数、节点数和层级。
“区段查找”包含缓存命中及 local-only 路径可能发生的存储读取，不等于实际磁盘 I/O 数。

## 验证

CPU 回归随 `testJunit` / `build` 执行。覆盖本地命中、uniform、粗细层覆盖、未知遮挡、
负坐标、轴向/边角边界、起点在体素内、维度高度、非有限输入、距离/读取/遍历预算、
VISUAL/OUTLINE 流体规则、取消、后续操作失效与重复发送保护。存储 miss listener 断言
没有远程请求，结束断言所有区段引用归零。

`validateMixins` 必须解析实际 PingWheel 编译依赖，检查控制器方法和 Shadow 字段。
没有 `require=0` 或可选目标跳过。真实注入位置由隔离客户端另外验证。

真实客户端复现步骤见 [隔离验收脚本](../scripts/pingwheel-smoke/README.md)。
本次验收结果及未覆盖项在该文档中记录；编译通过不能替代完整游戏验收。
