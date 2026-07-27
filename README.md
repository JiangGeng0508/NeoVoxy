# NeoVoxy

## 中文

NeoVoxy 是一个面向 Minecraft NeoForge 1.21.1 的 Voxy 移植/复刻项目，目标是在 NeoForge 环境中提供基于 LoD 的远距离地形渲染能力。

本项目主要参考 `.reference/voxy-aero`，并结合 NeoForge 1.21.1 的加载、Mixin、运行时依赖和资源系统进行适配。当前项目依赖 Sodium NeoForge，并保留了 Voxy 的核心客户端渲染与存储逻辑。

### 当前状态

- 目标平台：Minecraft 1.21.1 + NeoForge 21.1.x
- 必需依赖：Sodium 0.8.12 或更新版本
- 已禁用/暂未移植的可选兼容：Iris、Flashback、Nvidium、Chunky、Vivecraft
- 项目仍处于移植验证阶段，后续可能继续修复运行期兼容问题

## English

NeoVoxy is a Voxy port/reimplementation targeting Minecraft NeoForge 1.21.1. Its goal is to bring LoD-based far-distance terrain rendering to the NeoForge environment.

This project primarily follows `.reference/voxy-aero`, with adaptations for NeoForge 1.21.1 mod loading, Mixins, runtime dependencies, and resource handling. It currently depends on Sodium NeoForge and keeps Voxy's core client rendering and storage systems.

### Status

- Target platform: Minecraft 1.21.1 + NeoForge 21.1.x
- Required dependency: Sodium 0.8.12 or newer
- Optional compatibility currently disabled/not yet ported: Iris, Flashback, Nvidium, Chunky, Vivecraft
- The project is still in porting and validation, so runtime compatibility issues may remain
