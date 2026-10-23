# Xaero 隔离游戏验收

需要 Windows、Java 21、Python 3，以及可启动 Minecraft 1.21.1 NeoForge 的图形环境。依赖和 assets 需已下载；首次准备时移除 `--offline`。这不是静态检查或模拟 Xaero API。

从仓库根目录运行：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
.\gradlew.bat -I scripts/xaero-smoke.gradle writeXaeroSmokeLaunch --offline --console=plain
python scripts/xaero-smoke/run.py
```

init 脚本仅为本次显式调用加入游戏探针源码，导出 Java 启动参数并复制构建依赖中的 Sodium、World Map 1.47.0。要一并验证 Minimap，把合法取得的 NeoForge 1.21.1 / Minimap 26.4.2 JAR 复制到 `build/reports/xaero-smoke/mods/` 后再运行 Python；不要复制存档或地图缓存。验证期间不要同时编译项目，因为启动参数引用当前构建 classes。

Python 每次创建独立 `build/reports/xaero-smoke/run-<时间>/client`，关闭自动 LOD 摄取和 LOD 渲染，使用正常 Xaero 渲染。探针建立平坦世界，执行写入阶段，正常保存退出，再用新 JVM 打开同一个测试世界。不会访问实际整合包的配置、存档或地图。

必须两个进程均正常退出、所有标记齐全才打印 `XAERO_SMOKE PASS`：

| 标记 | 断言 |
|---|---|
| `write.pass` | LOD0 沙底 y=285、水面 y=300、沼泽生物群系、覆盖层及原生纹理高度 |
| `precision.pass` | LOD4 暂定石面由 LOD0 完整金块地表替换 |
| `real-map.pass` | 原版真实地图抵御冲突的 LOD 注入 |
| `real-handoff.pass` | 实际 MapWriter 重新扫描像素，即使地形相同也移除 LOD 所有权 |
| `cache.pass` | 旧格式/无覆盖信息的已存 section 仅靠正常读取通知写入暂定地图 |
| `import.pass` | 构造的 MCA 文件经过实际 WorldImporter 导入并写入地图 |
| `receive.pass` | 实际客户端映射及 payload handler 注入；进程内调用，不验证远端网络传输 |
| `reload.pass` | 原生文件区域发现、沙/水高度和覆盖恢复，各入口像素及来源/精度/暂定标记恢复；不重新注入 LOD |
| `coarse-reload.pass` | 仅有 LOD4 的相邻地表及其精度、暂定状态跨 JVM 保留 |
| `eviction.pass` | 原生源瓦片清理后，再次更新会恢复保存像素，保留邻居来源并更新负高度地表 |
| `subworld.pass` | 切换 Xaero 子世界后旧地图任务没有进入新子世界 |
| `native-zip.pass` | 确认可写的子世界使用原生区域 ZIP，切走并重新加载后保留负高度和精度 |
| `dimension.pass` | 切换下界后地表 worker 被停止 |

无 Xaero 启动验收：

```powershell
.\gradlew.bat -I scripts/xaero-smoke.gradle -PxaeroBare writeXaeroSmokeLaunch --offline --console=plain
python scripts/xaero-smoke/run.py --bare
```

bare 模式只复制 Sodium，探针确认未加载 World Map，正常进入标题界面并退出。不要把 bare 启动参数用于完整探针，反之亦然。

日志、启动参数、隔离世界和地图均保留在该次 `run-*` 目录。单个进程超时为 420 秒；Python 仅终止自己创建的测试进程。Xaero 的外部更新/Patreon 查询可能超时，应与兼容层错误区分；标记和进程退出状态才是自动验收依据。

定位重启阶段的问题时，可用 `--reload-run build/reports/xaero-smoke/run-<时间>` 单独复跑已有隔离世界。该选项只接受验收输出目录的直接子目录；它会更新该测试世界和日志。重启测试本身包含地形修改和维度切换，复跑前需确认夹具仍满足最初写入阶段的条件。最终验收应使用不带此选项的新建完整运行。

探针不属于普通构建，验收完成后重新运行标准构建，确认分发 JAR 没有 `XaeroSmoke` 或 `BareXaeroSmoke` 类：

```powershell
.\gradlew.bat build --offline --console=plain
```
