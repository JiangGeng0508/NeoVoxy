# Xaero 补图与刷新排查（2026-10-29）

后续变更：按用户要求移除了内置种子地形预览，保留外部 SeedGen 兼容及本次 Xaero 修复。下文有关预览与运行配置的描述记录的是排查当时的状态。

用户场景为单人游戏、NeoVoxy 自带远景生成。核对 Xaero World Map 1.47.0 的 `MapProcessor`、`MapSaveLoad`、`WorldDataReader`、`MapTileChunk`、`LeafRegionTexture` 实现后，修复以下兼容层问题：

- 原队列每次从区域最低位取瓦片，持续通知可以让前面的坐标反复被处理，后面的坐标长期等待。改为区域内部游标轮转；区域之间继续轮转并保留容量上限。
- 原提交队列在队首等待源数据、缓存或 PBO 时停止处理全部后续瓦片。改为归还坐标、延迟 250 ms 重试并重新提取最新数据，其他区域继续前进。
- Xaero 的池化瓦片可在 `isLoaded() == false` 时保留原位置的 `MapBlock`。这些残留对象不再参与真实地图来源保护；已加载的真实地图仍然受到保护。
- 原代码即使像素相同也替换、重绘，并为邻居请求整区域刷新。现在比较 Xaero 原生像素内容，来源精度单独保存；改变地形时只标记当前及受影响的相邻 64×64 纹理，由原生上传流程合并更新。
- `MapProcessor.addToProcess` 本身不去重，原代码每次写入都追加区域。现在先检查是否已经在处理列表中。

这些是代码与回归夹具确认的问题，尚未在用户出现空洞的原存档逐坐标复现，不能据此断言每一个旧空洞都是同一个原因。

## 延迟与加载动画

只读检查本地 `run/client/config/voxy-config.json` 时，每秒地图瓦片数为 20、真实生成半径为 128、粗 LOD 同步关闭、种子预览开启。没有修改运行配置。

128 区块半径圆形范围约有 5.1 万个瓦片，按 20 瓦片/秒单遍至少约 43 分钟；提取与生成会重叠，不能把这个数直接当成生成后的等待时间。粗 LOD 关闭时，地图等待真实 LOD0；种子预览只提供临时渲染数据，不能据此确认真实区块已经生成。可以将地图处理速度调到 64 来缩短排队，但会增加后台提取开销，客户端仍受每 tick 四个瓦片的上限约束。

Xaero 单人 `WorldDataReader.buildRegion` 会提交服务器保存并等待完成，再读取 MCA。其 `handleRefresh` 遍历整个区域并请求重新缓存；洞穴起始高度过期时还会走 MCA 重建。兼容层不再为普通邻居更新调用这条整区域刷新路径。地图仍有原生加载、上传、保存及缓存更新，修复不承诺所有正常加载动画消失。

## 已执行验收

队列回归命令：`gradlew.bat testJunit --tests '*SurfaceQueueTest' --offline --console=plain`。5 项全部通过，包括连续 1024 次父层更新覆盖每个坐标一次，以及繁忙区域延后期间其他区域前进、延后瓦片最终不丢失。

真实客户端命令：

```powershell
.\gradlew.bat -I scripts/xaero-smoke.gradle writeXaeroSmokeLaunch --offline --console=plain
python scripts/xaero-smoke/run.py
```

输出目录：`build/reports/xaero-smoke/run-20261029-201030`。写入和重启两个独立 JVM 均退出码 0，报告 `XAERO_SMOKE PASS`，14 个断言标记全部齐全。使用新建隔离平坦世界、Sodium、World Map 1.47.0 和 Minimap 26.4.2。

新增 `incremental.pass` 在真实 Xaero 区域上验证：池化未加载瓦片能补图；连续 20 次相同提交保留像素对象且不增加处理列表条目；存在邻近纹理时不请求整区域刷新，原有相邻地图保留。其他验收继续覆盖真实扫描接管、LOD0/LOD4 精度、导入与进程内网络接收、源瓦片清理后更新、单人补充 ZIP 跨 JVM 持久化、子世界隔离及原生 ZIP 重载、下界切换。

随后执行普通 `gradlew.bat build --offline --console=plain` 成功：182 项测试中 150 项通过、0 失败、32 项按默认条件跳过；语言键检查通过，Mixin 校验为 93 个注册类、103 项目标检查、281 项成员检查通过，10 项未安装可选目标跳过。日志为 `build/reports/xaero-fix-build-2026-10-29.log`。最终 `build/libs/voxy-0.2.18-beta+neoforge.jar` 不包含验收探针或 Xaero 类。构建保留并包含工作区原有的其他改动。

本轮没有运行完整整合包的 128 区块半径生成性能基准，也没有对 Minimap 动画做独立画面计时；上述时间估算与源代码路径分析不代表实测加速倍率。
