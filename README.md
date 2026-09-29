# NeoVoxy

NeoVoxy 是面向 Minecraft 1.21.1 + NeoForge 的 Voxy 移植项目，目标是在 NeoForge 环境中提供 Voxy 强大的远距离（LoD）渲染能力。本分支不依赖信雅互联，强制依赖只有 Sodium `0.8.12+mc1.21.1` 以上。

项目主要参考 [voxy](https://github.com/M4G4MED/voxy)，并针对 NeoForge 1.21.1 做了适配。当前仍处于 beta 验证阶段。

> 主要工作由 AI 完成，代码质量不能保证，但在作者自制的整合包（含 300+ 模组）中基本可用。

可以参考类似项目: [voxy-forged](https://github.com/falling-colud/voxy-forged)

## 当前状态

- Minecraft: `1.21.1`
- NeoForge: `21.1.244`
- Mod 版本: `0.2.16-beta+neoforge`
- Java: `21`
- 必需依赖: Sodium NeoForge `0.8.12+mc1.21.1` 以上
- 可选依赖: Iris `1.8.x+1.21.1-neoforge`（启用光影时自动切换到 Iris 渲染管线）

## 功能范围

- Sodium 渲染管线深度集成（吞并最外层 vanilla 区块环交给 LoD 渲染，消除区块交界处的水面接缝）
- Voxy LoD 区块的加载、保存与渲染（GPU 驱动的 MDIC 渲染后端、HiZ 遮挡剔除、SSAO）
- 世界曲率（地球曲率）模拟，参照 Distant Horizons 实现
- 集成服/局域网主机的**远处区块后台生成**（Distant Generation），持续向 LoD 商店灌入地形
- 世界导入（World / Bobby / Raw / Zip / Current / Distant Horizons SQLite）
- 单人世界与服务器世界的本地 LoD 数据路径管理
- Iris 光影兼容（光影包可通过 `voxy.json` 提供 LoD 片元着色器补丁）
- 远处实体渲染：远距离玩家 / 载具快照渲染（服务端同样安装 Voxy 时可用）
- 模组联动：Create（列车 / 轨道 / 装置 / 动力网格电线）、Create: Copycats+、Create: Simulated
  激光、Create: Aeronautics（Sable 船载）、FramedBlocks、Little Tiles、Domum Ornamentum、
  Ecliptic Seasons 季节雪线与冻结水面，均可单独开关并设置各自的 LoD 距离
- 远景信标光柱、放置式动力机械的远处剔除等原版扩展
- `/voxy reload`
- `/voxy import world <name>`
- `/voxy import bobby <name>`
- `/voxy import raw <path>`
- `/voxy import zip <zipPath> [innerPath]`
- `/voxy import current`
- `/voxy import cancel`
- `/voxy import distant_horizons <sqlDbPath>`
- `/voxy distantgen status|pause|resume|reset`
- `/voxy debug verifyTLNChildMask [attemptRepair]`
- `/voxy debug beacons|probe|trains|kinetics|ship|fog|perf|capture`

## 配置

配置文件位于 `config/voxy-config.json`，也可以在游戏内的 Voxy 设置界面（模组菜单）调整。

常用配置项：

| 配置项 | 默认值 | 说明 |
| ---- | ---- | ---- |
| `enabled` | `true` | 完全启用或禁用 Voxy |
| `enable_rendering` | `true` | 启用或禁用 Voxy 渲染 |
| `ingest_enabled` | `true` | 将新区块转换为 LoD 数据 |
| `section_render_distance` | `16` | LoD 渲染距离（区块） |
| `use_environmental_fog` | `true` | 环境雾（远距离雾） |
| `fog_intensity` / `fog_density` | `1.0` / `0.0` | 环境雾强度与密度 |
| `sky_fog_distance` | `96` | 天空雾距离（区块） |
| `ssao_mode` | `auto` | SSAO 模式（auto/basic/better/best） |
| `earth_curve_ratio` | `0` | 世界曲率倍率；`0` 关闭，`50-5000` 开启（值越大曲率越明显） |
| `distant_gen_enabled` | `true` | 集成服远处区块后台生成 |
| `distant_gen_radius` | `96` | 后台生成半径（区块） |
| `distant_gen_max_inflight` | `16` | 同时生成的区块数量 |
| `distant_gen_max_mspt` | `45` | 生成时的服务器 MSPT 上限 |
| `distant_beacons` / `distant_beacon_max_chunks` | `true` / `0` | 远景信标光柱与距离上限（0 = 跟随 LoD 半径） |
| `distant_trains` / `distant_tracks` / `distant_contraptions` | `true` | Create 列车 / 轨道 / 装置的远处渲染 |
| `distant_kinetics` | `true` | 剔除超出渲染距离的放置式动力机械部件 |
| `distant_copycats` / `distant_framed_blocks` / `distant_little_tiles` / `distant_domum` | `true` | 各装饰方块的远处专用网格 |
| `distant_powergrid_wires` / `distant_simulated_lasers` | `true` | PowerGrid 悬垂电线 / Simulated 激光的远处渲染 |
| `enable_far_player_rendering` / `enable_far_vehicle_rendering` | `true` | 远距离玩家 / 载具快照渲染 |
| `render_far_player_names` | `true` | 远景玩家代理的名牌显示 |

### 运行时开关

- `/voxy distantgen`：查看/暂停/恢复/重置远处区块生成
- `/voxy debug`：输出信标 / 列车 / 动力学 / 雾 / 性能等调试信息（`capture` 可抓取性能采样）

## 兼容性

已测试环境：

| 模组名称         | 版本                              | 兼容状态    |
| ------------ | ------------------------------- | ------- |
| Sodium       | `mc1.21.1-0.8.12-neoforge`      | 兼容良好    |
| Iris         | `1.8.x+1.21.1-neoforge`         | 基本可用    |
| ReForgedPlay | `0.3`                           | 兼容良好    |
| Lithium      | `mc1.21.1-0.15.4-neoforge`      | 暂未发现冲突  |
| Chunky       | `1.4.23`                        | 暂未发现冲突  |
| Create       | `6.0.x+mc1.21.1`                | 兼容良好（列车 / 轨道 / 装置 / 电线等远景渲染） |
| Create: Copycats+ / FramedBlocks / Little Tiles / Domum Ornamentum | 对应 1.21.1 版本 | 兼容良好（可选；未安装时相关选项自动隐藏） |
| Create: Aeronautics（Sable） | `2.0.x+mc1.21.1`     | 实验性     |
| Ecliptic Seasons | `0.15.0-rc-3-1`              | 实验性     |

不确定的工作：

- 代码中似乎有导入 DH 区块的部分，但未经测试。

原版有但本分支暂未移植的兼容：

- Nvidium
- Vivecraft

## 上游移植

本分支的部分通用修复与模组联动移植自 `neo-voxy-multiversion`（Neo-Voxy 0.5.0 系列），
包括 Iris 光影管线与深度交接、Create / Sable / FramedBlocks 等联动、远景实体渲染，以及
本地保留的 Vista 副渲染通道（电视机/后视镜）、世界曲率遮挡剔除、导入任务自愈与
退出时摄取队列排空等修复。

## 构建

使用 Gradle 运行 `.\gradlew build` 即可，产物为 `build/libs/voxy-<版本>.jar`（`assemble` 会自
动执行 `slimJar`）。`build/intermediate-jars` 下是未裁剪的中间产物，请勿发布。

> 分发包内嵌 RocksDB、LWJGL ZSTD、LWJGL LMDB（含 Windows/Linux x86_64 原生库，解包在 Jar 根
> 目录）、Jedis + commons-pool2 与 xz/sqlite-jdbc，因此默认存储、LMDB 后端、Redis 后端以及
> Distant Horizons 数据库导入都可直接使用。LZ4 由 Minecraft 自身提供，故不内嵌（内嵌会造成
> 模块冲突）。

## 安装

1. 将构建的 Jar 放入实例的 `mods` 目录。
2. 同时安装 Sodium NeoForge `0.8.12+mc1.21.1` 或更新版本。
3. 使用 Minecraft `1.21.1`、NeoForge `21.1.244` 版本启动。

## 已知问题

1. 有时 LoD 区块不会自动细化，需要重新加载，光影环境下更容易触发。
2. 极远距离的水面/地形仍可能显示硬边缘。

使用 ComplementaryReimagined 效果
<img width="2560" height="1441" alt="2026-07-28_12 22 49" src="https://github.com/user-attachments/assets/2a3499b8-c860-4e9e-9dcc-20343c99a080" />
