# NeoVoxy

NeoVoxy 是面向 Minecraft 1.21.1 + NeoForge 的 Voxy 移植项目，目标是在 NeoForge 环境中提供基于 LoD 的远距离地形渲染能力。

项目主要参考 `voxy`，并针对 NeoForge 1.21.1 的加载流程、Mixin、运行时依赖、资源系统和外部启动器环境做了适配。当前仍处于 beta 验证阶段。

## 当前状态

- Minecraft: `1.21.1`
- NeoForge: `21.1.244`
- Mod 版本: `0.2.15-beta+neoforge`
- Java: `21`
- 必需依赖: Sodium NeoForge `0.8.12+mc1.21.1` 以上

## 功能范围

- Sodium 渲染管线集成
- Voxy LoD 区块摄入、存储、保存和渲染
- 单人世界和服务器世界的本地 LoD 数据路径管理
- `/voxy reload`
- `/voxy import world`
- `/voxy import bobby`
- `/voxy import raw`
- `/voxy import zip`
- `/voxy import current`
- `/voxy import cancel`
- `/voxy debug verifyTLNChildMask`
- Distant Horizons 导入命令会在运行时检测到 `sqlite`、`xz`、`zstd` 相关类后启用

## 兼容性

已验证或已做适配：

- Sodium NeoForge `0.8.12+mc1.21.1`
- Lithium NeoForge `0.15.4+mc1.21.1`
- NeoForge `21.1.244`

暂未测试的模组兼容：

- Iris
- Nvidium
- Chunky
- Vivecraft
- Mod Menu

## 构建

NeoVoxy:jar 在build/libs中生成jar

## 安装

1. 将 `build/libs/voxy-0.2.15-beta+neoforge.jar` 放入实例的 `mods` 目录。
2. 同时安装 Sodium NeoForge `0.8.12+mc1.21.1` 或更新版本。
3. 使用 Minecraft `1.21.1`、NeoForge `21.1.244` 版本启动。

## 已知问题

1. 加载光影时lod区块会闪烁