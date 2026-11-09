# Vista 水面游戏回归

该脚本启动独立客户端，复制测试世界，调用 Vista 5.4.1 的实际 `VistaLevelRenderer.render` 入口。它向副本的 Voxy 数据写入含岸边、浅水和深水的人工水域，不修改原世界、Vista JAR 或光影包。测试源码仅在 `-I scripts/vista-water.gradle` 时加入编译，不进入普通构建。

需要本地存在以下文件：

- `run/client/mods/vista-1.21.1-5.4.1-neoforge.jar`
- `run/client/mods/moonlight-1.21.1-3.3.3-neoforge.jar`
- 光影场景另需 `run/client/mods/iris-neoforge-1.8.14-beta.1+mc1.21.1.jar` 和 `run/client/shaderpacks/ComplementaryReimagined_r5.8.1.zip`
- 一个可加载的 1.21.1 世界目录，通过 `-PvistaWorld=路径` 指定；默认使用 `.agents/work/verification/server/smoke-world`。

每次使用新的 `vistaRun` 名称，避免覆盖已保存的世界副本和结果：

```powershell
# Vista，无 Iris
.\gradlew.bat runClient -I scripts/vista-water.gradle -PvistaRun=plain-new --console=plain

# Vista + Complementary 原生 Voxy 光影
.\gradlew.bat runClient -I scripts/vista-water.gradle -PvistaShaders=true -PvistaRun=iris-new --console=plain

# Iris 保持开启，Vista 使用 iris_off_hack
.\gradlew.bat runClient -I scripts/vista-water.gradle -PvistaShaders=true -PvistaIrisOff=true -PvistaRun=hack-new --console=plain

# 故意让副管线补丁编译失败，验证独立降级与恢复
.\gradlew.bat runClient -I scripts/vista-water.gradle -PvistaShaders=true -PvistaFailPatch=true -PvistaRun=failure-new --console=plain
```

全部依赖已缓存时可加 `--offline`。客户端加载后隐藏窗口，完成 120 次实际刷新后自动退出。输出位于 `build/reports/vista-water/<vistaRun>/`。

检查内容包括每次主视口深度像素和矩阵不被副视口改变、水面绘制指令非零、光影路径和深度纹理归属、同尺寸不同目标、目标重建、关闭重开、转动与 FOV 变化，以及光影重载和开关。重载后会继续渲染并等待网格恢复，超时视为失败。故障注入只改测试进程中副管线的数据，普通水面必须在 Iris 最终合成后绘制；随后恢复原生管线。

成功要求进程退出码为零、日志包含 `VISTA_WATER PASS`，且 `verification.json`、`results.json` 的 `state` 为 `passed`。截图中的 `feed-*` 已按 Vista 的纹理显示方向翻转，便于与 `main-*` 比较。

这是实际游戏中的自动回归夹具，不能替代真实电视方块交互和天然海岸长时间游玩的视觉验收。
