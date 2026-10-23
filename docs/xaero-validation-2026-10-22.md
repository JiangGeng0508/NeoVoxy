# Xaero 兼容验收记录（2026-10-22）

环境：Windows、Java 21、Minecraft 1.21.1 / NeoForge 21.1.244、Sodium 0.8.12、Xaero World Map 1.47.0、Xaero Minimap 26.4.2（嵌套 XaeroLib 1.7.1）。初始提示中的 `E:\App\PCL.minecraft\versions\Aeronautics\mods` 已不存在；本轮从已准备的隔离验收依赖核对并加载实际版本，没有访问用户存档或地图缓存。

## 实际通过

完整命令：`gradlew.bat -I scripts/xaero-smoke.gradle writeXaeroSmokeLaunch --offline --console=plain`，随后 `python scripts/xaero-smoke/run.py`。

最终完整运行：`build/reports/xaero-smoke/run-20261022-222020`。新建平坦测试世界，写入后正常退出，再以独立 JVM 重启。两个客户端进程均退出码 0，脚本报告 `XAERO_SMOKE PASS`，13 个断言标记齐全：

| 标记 | 验收结果 |
|---|---|
| write | LOD0 沙底 285、水面 300、沼泽生物群系、覆盖层和原生纹理高度 |
| precision | 暂定 LOD4 石面由确认 LOD0 金块地表替换 |
| real-map | 玩家附近真实扫描地图抵御冲突的 LOD0 更新 |
| real-handoff | 实际 MapWriter 扫描移除相同像素的 LOD 所有权 |
| cache | 普通旧缓存读取通知补全暂定绿宝石地表，不调用 dirty 通知 |
| import | 合成 MCA 经实际 WorldImporter 导入并补全铁块地表 |
| receive | 实际客户端映射与 payload handler 注入红石地表 |
| reload | 文件发现与各入口像素、来源、覆盖层及高度跨 JVM 保留 |
| coarse-reload | 仅有 LOD4 的相邻石面及其暂定精度跨 JVM 保留 |
| eviction | 原生瓦片清理后更新恢复源像素，保留邻居并写入 -17 高度地表 |
| subworld | 未确认的子世界不接收上一个绑定的地图任务 |
| native-zip | 确认可写子世界的原生 ZIP 保存重载保留铜块地表 -20 和 LOD0 来源 |
| dimension | 切换下界停止地表 worker |

无 Xaero 复验：使用 `-PxaeroBare` 导出启动参数，运行 `python scripts/xaero-smoke/run.py --bare`。最终运行 `build/reports/xaero-smoke/run-20261022-222356` 仅装 Sodium，正常到达标题界面、退出码 0，报告 `XAERO_SMOKE PASS`。

探针完成后执行普通 `gradlew.bat build --offline --console=plain`，结果成功：148 项测试，117 项通过、0 失败、31 项 GPU 测试按默认配置跳过；中英文各 225 个键一致；90 个注册 Mixin、100 个目标检查、274 个成员检查通过（5 个未安装可选目标明确跳过）。日志为 `build/reports/xaero-build.log`。

分发包 `build/libs/voxy-0.2.17-beta+neoforge.jar` 已检查，不含 `XaeroSmoke`、`BareXaeroSmoke`、Xaero 模组类或其依赖 JAR。

## 本轮修复与边界

明确请求源像素，避免已有纹理缓存导致永久等待；补充区域从已清理状态重新进入原生加载完成流程，避免没有 MCA 的区域重载后无法更新；临时 WorldSection 读取完成或失败时释放数组；worker 初始化和补充读取清理异常也遵循释放与故障停用流程。

DistantGen 的 JSON/TOML 默认值改为 `true`，中英文提示、TOML 注释和 README 同步更新。已有配置显式保存的值不迁移；隔离验收显式关闭生成和自动摄取。

网络验收使用进程内 payload，没有启动远端专用服务器或验证 socket 传输。此次未单独启动专用服务器；客户端类隔离由配置门控及公共测试覆盖。原生 ZIP 验收在单人自定义子世界执行，实际多人子世界选择与服务器切换仍需后续实机覆盖。Minimap 已同时安装并正常启动，但没有对其世界地图模式画面做独立截图验收；它读取同一世界地图数据的机制不等同于独立小地图扫描支持。

未执行整个整合包、长期批量导入性能或完整 GPU 回归。Xaero 外部版本/Patreon 请求有超时日志，部分维度退出出现原生读取线程中断；最终两个进程正常退出，兼容层无故障停用记录。洞穴、下界特殊选层、独立小地图扫描仍不在实现范围。详见 [功能与配置](xaero-map.md) 和 [可重复验收步骤](../scripts/xaero-smoke/README.md)。
