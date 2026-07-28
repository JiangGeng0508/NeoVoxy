# NeoVoxy

NeoVoxy 是面向 Minecraft 1.21.1 + NeoForge 的 Voxy 移植项目，目标是在 NeoForge 环境中提供 Voxy 强大的远距离渲染能力。本分支不依赖于信雅联结，强制依赖只有sodium0.8.12。

项目主要参考 [voxy](https://github.com/M4G4MED/voxy)，并针对 NeoForge 1.21.1 做了适配。当前仍处于 beta 验证阶段。

>  主要工作由gpt5.5完成，代码质量不能保证，但在作者自制的整合包（含300+模组）中基本可用。

## 当前状态

- Minecraft: `1.21.1`
- NeoForge: `21.1.244`
- Mod 版本: `0.2.15-beta+neoforge`
- Java: `21`
- 必需依赖: Sodium NeoForge `0.8.12+mc1.21.1` 以上

## 功能范围

- Sodium 渲染管线集成
- Voxy LoD 区块加载、保存和渲染
- 单人世界和服务器世界的本地 LoD 数据路径管理
- `/voxy reload`
- `/voxy import world`
- `/voxy import bobby`
- `/voxy import raw`
- `/voxy import zip`
- `/voxy import current`
- `/voxy import cancel`

## 兼容性

已测试环境：

| 模组名称         | 版本                              | 兼容状态    |
| ------------ | ------------------------------- | ------- |
| Sodium       | `mc1.21.1-0.8.12-neoforge`      | 兼容良好    |
| Iris         | `1.8.14-beta.1+1.21.1-neoforge` | 有少量视觉问题 |
| ReForgedPlay | `0.3`                           | 兼容良好    |
| Lithium      | `mc1.21.1-0.15.4-neoforge`      | 暂未发现冲突  |
| Chunky       | `1.4.23`                        | 暂未发现冲突  |

不确定的工作：

- 代码中似乎有导入DH区块的部分，但未经测试。

原版有但本分支暂未移植的兼容：

- Nvidium
- Vivecraft

## 构建

使用Gradle 运行 NeoVoxy:jarJar

> 注意是jarJar不是jar，模组需要包含rockdb和sqlite等，需要jarJar生成

## 安装

1. 将构建的 Jar 放入实例的 `mods` 目录。
2. 同时安装 Sodium NeoForge `0.8.12+mc1.21.1` 或更新版本。
3. 使用 Minecraft `1.21.1`、NeoForge `21.1.244` 版本启动。

## 已知问题

1. 有时lod区块不会自动细化，需要重新加载，光影环境下更容易触发。


