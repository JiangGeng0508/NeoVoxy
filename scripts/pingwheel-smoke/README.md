# PingWheel 隔离验收

需要 Java 21、Python 3、可运行 Minecraft 的 Windows 图形环境。每次新建
`build/reports/pingwheel-smoke/run-*`，只使用该目录的存档和配置，不读取玩家整合包。

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat -I scripts/pingwheel-smoke.gradle writePingWheelSmokeLaunch --offline --console=plain
python scripts/pingwheel-smoke/run.py
python scripts/pingwheel-smoke/run.py --bare
python scripts/pingwheel-smoke/run.py --iris
```

首次依赖未缓存时移除 `--offline`。运行期间不要同时编译，因为启动参数引用 build/classes。
探针源码只在显式 init 脚本下编译，普通构建不包含它们。

完整模式创建平坦世界，关闭摄取/后台生成，以真实 Voxy renderer 的引擎写入已知 LoD
LOD4 走廊和 x=8192 石面；原版视距 2 区块，LoD 半径 16384 格，联动上限 0。
断言 PingWheel 距离仍为默认 1024/2048。通过反射触发 PingWheel 私有控制器（真实 Mixin），
经集成服原有网络收回 PingManager 标记，检查坐标、维度、空实体 UUID 和数量为 1。
再以俯角 0.57°、偏航 -83° 射向同一远处石面，检查收到且仅收到一个标记，三维坐标位于原射线上。
随后检查关闭联动、曲率停用、取消旧请求和原版 x=4 方块优先，最后正常退出。
这验证合成 LoD 区块位于原版视距外，未模拟玩家探索后卸载区块的过程。

| 标记 | 验证 |
| --- | --- |
| `lod.pass` | MISS 后实际 LoD 选点及原有网络收包一次 |
| `shallow.pass` | 浅角度斜向射线命中 8192 格外 LOD，标记坐标和数量正确 |
| `disabled.pass` | 关闭联动无 LoD 发包 |
| `curve.pass` | 开启曲率无 LoD 发包 |
| `cancel.pass` | 取消在途请求无发包 |
| `near.pass` | 原版近处方块优先，收到一个标记 |
| `complete.pass` | 世界正常关闭、进程正常退出 |
| `bare.pass` | 无 PingWheel 时进入标题界面并正常退出 |

Python 必须检查退出码为 0 和全部对应标记才输出 PASS。超时 420 秒，只终止自己创建的进程。
`--iris` 加入已锁定的 Iris 1.8.14-beta.1，保持光影关闭；不代表光影开启组合已通过。

验收完成后运行普通构建清除 build/classes 中的探针，再确认分发包无 Smoke 类：

```powershell
.\gradlew.bat build --offline --console=plain
```

## 本次结果

2026-10-29：浅角度区段跨界回归通过（`run-20261029-215718-sodium`）。原算法重新计算
`origin + direction * t` 后，边界 128 可被舍入为 `127.99999999999999`，落回刚离开的区段，
随后把零长度交集误报为 BUDGET。新增的 6 个浅角度/水平斜射、正负方向样例在旧实现上
全部失败；有的只查了两个区段，耗时不足 1 ms，并非实际预算耗尽。
改为直接推进整数区段索引后，24 项 CPU 拾取测试全部通过，包括维度外进入、正负大坐标、
几乎同时跨界的未知间隙，以及本地存储 LOD4 的浅角度命中。

真实 PingWheel 控制器在俯角 0.57°、偏航 -83° 时，经集成服收到且仅收到一个 x=8192
标记，三维坐标落在触发时射线上。该次查询 4.46 ms、564 次区段查找、873 节点；
这是已准备夹具的样本，不代表冷缓存性能保证。普通远处命中、关闭联动、曲率停用、取消、
近处原版命中和正常退出也全部通过，进程退出码 0。

2026-10-24：远距离回归通过（`run-20261024-215023-sodium`）。PingWheel 保持默认
1024/2048，真实 Mixin 命中 x=8192 的合成 LOD4 石面，经集成服收到且仅收到一个标记；
关闭联动、曲率停用、取消旧请求、原版近处方块优先和正常退出全部通过。
此次查询 13.27 ms、501 次区段查找、771 节点；该样本不代表一般冷缓存性能。
CPU 定向回归同时验证 2048/8192/32752 格命中及显式距离上限。此次未重跑 Iris 组合。
最终普通构建通过：163 项测试中 132 通过、31 项可选 GPU 跳过；语言键和 Mixin 检查通过。

2026-10-23：Java 21.0.10、NeoForge 21.1.244、i7-13620H、RTX 4060 Laptop GPU。
最终标准构建 160 项测试：129 通过、31 项可选 GPU 跳过；语言键与 Mixin 静态检查通过。
无 PingWheel 启动通过（`run-20261023-121701-bare`）。Sodium 0.8.12
（`run-20261023-125024-sodium`）及 Sodium + Iris 1.8.14-beta.1 无光影
（`run-20261023-125024-iris`）完整运行全部标记与正常退出通过。两者实际使用
`NormalRenderPipeline`；Iris 光影管线仍需单独验证。

首个合成 LOD0 样本：射线有效上限 320 方块、命中 x=256，20 次区段查找、18 节点、
47.2 ms；最终完整 Sodium/Iris 样本分别为 10.85/14.75 ms，记录在对应 `client.log`。数据刚写入存储、
不是可重复冷缓存基准，不宣称一般性能保证。

尚未覆盖：专用服务端真实启动、独立服务器多人、仅安装 PingWheel 的接收端、
实体/Sable/DH 共存、Iris 光影开启、第三人称、真实探索后的卸载、游戏切维度与 reload。
负坐标、未知数据、层级覆盖、取消和顺序保护由 CPU 回归覆盖；不作为这些游戏组合的验收声明。
