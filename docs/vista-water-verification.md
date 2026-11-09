# Vista LOD 水面实施与验证记录

日期：2026-11-09。

## 后续修复：无光影电视中的不透明 LOD 发白

普通地形的 Alpha 原本保存面朝向、LOD 层级和 AO 标记，由主画面的 SSAO 阶段转换为覆盖率。普通副视口跳过 SSAO，直接预乘合成时误将这些标记当作透明度，叠加了天空颜色；标记为零的有效不透明面还会被最终合成丢弃。

现在复用 SceneUniform 中偏移 124 的填充槽，随视口上传 `opaqueAlphaIsCoverage`。普通副视口在实际片元着色器完成颜色计算后，将不透明面的 Alpha 设为 1，再叠加水面。主视口保留原有 SSAO 元数据；半透明材质和 Iris 补丁路径不执行该转换。

扩展 `SecondaryViewportDepthGpuTest`，使用生产 `quads.frag` 覆盖多组 Alpha 元数据（含零）、暗色地形与白色天空、水面覆盖地形/天空，以及切回主视口后的元数据保留。此前夹具仅使用 Alpha=1 的合成颜色，未覆盖这一协议差异。

本次验证与下面的原实施记录分别统计：

- 标准离线构建通过：344 项测试，308 通过、0 失败、36 项可选 GPU 测试跳过；语言键和 Mixin 检查通过。[修复构建日志](../build/vista-opaque-fix-build.log)、[0.2.20 构建日志](../build/vista-0.2.20-build.log)。
- 用 glslangValidator 编译并链接生产地形与回归夹具的 8 组着色器（不透明/水面、完整/半分辨率遮罩），全部通过；反射确认新字段仍位于偏移 124。[检查结果](../build/reports/vista-opaque-shaders/results.json)。
- 已尝试严格 GPU 回归，但当前环境无法建立测试所需的 OpenGL 4.6 能力，NVIDIA 驱动查询也失败；测试在上下文初始化处失败，未执行像素断言。本次没有重新进行游戏画面验收，不能沿用下方历史 GPU/游戏结果作为此次修复通过的依据。[GPU 尝试日志](../build/vista-opaque-regression-before.log)。
- 更新产物：`build/libs/voxy-0.2.20-beta+neoforge.jar`，SHA-256 `919aaccc17c8aebf4ddf86759b1c2a4a390b969fe5608d8fb1fc1b46f8d99008`；已确认模组元数据版本为 `0.2.20-beta+neoforge`、包含更新后的着色器，且不含 audit 测试类。

## 原计划实施记录

已实现可嵌套世界渲染上下文；Vista 身份通过可选桥识别，视口按实际输出目标隔离，光影资源按 Iris 实例隔离。主视口访问接口继续用于拾取和其他联动，当前渲染入口使用上下文中的相机、矩阵、区块遮罩和所属管线。

虚拟维度仍作为 Iris 缓存键，仅在程序查找时映射回真实维度。副视口执行深度准备、Hi-Z、遍历、指令生成、不透明和半透明绘制；原生光影由 Vista/Iris 完成合成，遵守光影包的原版深度回写设置。普通水面在透明背景上累积预乘颜色，并通过深度重投影合成。光影副管线编译失败时释放部分资源、记录一次原因，并在 Iris 最终合成后降级绘制。

目标销毁、同尺寸重建、Iris 管线销毁、光影重载和世界退出均有释放路径。补齐了失败 shader program 的释放、共享 SSBO 声明的只读使用、深度 sampler 的复用，以及无 Iris 时原版 12 个纹理缓存槽位的边界处理。

## 自动测试

执行命令：

```powershell
.\gradlew.bat testJunit --tests '*WorldRenderContextTest' --tests '*SecondaryViewportDepthGpuTest' -PverifyGpu=true --console=plain --offline
.\gradlew.bat build --console=plain -PverifyGpu=true --offline
```

- 定向回归：4 项通过；覆盖实际副视口生产入口的水面提交、无不透明几何、多层 Alpha、水面后的天空/地形、近景遮挡、零 Alpha、深度重投影、异常后 GL 状态恢复和矩阵历史隔离。
- 完整构建：344 项测试全部通过，0 失败、0 跳过，GPU 测试实际执行。
- Mixin 校验：94 个注册项、104 个目标检查、287 个成员检查通过；10 个未安装的可选集成目标按已有规则跳过。
- GPU：NVIDIA GeForce RTX 4060 Laptop GPU，OpenGL 4.6，驱动 591.74，Java 21.0.10，Windows 11。
- 使用 `--offline` 的原因是 NeoForge Maven 连接超时；所需依赖已缓存，未跳过构建或测试任务。

完整日志：[vista-final-build.log](../build/vista-final-build.log)。

## 实际游戏自动回归

使用 Vista 5.4.1、Moonlight 3.3.3；光影场景使用 Iris 1.8.14-beta.1 和未修改的 Complementary Reimagined r5.8.1。

| 配置 | 结果 | 记录 |
| --- | --- | --- |
| Vista，无 Iris | 120 次刷新通过，普通水面 | [plain-01](../build/reports/vista-water/plain-01/results.json) |
| Vista `iris_off_hack=true`，主画面开启光影 | 120 次刷新通过，普通水面，含重载/开关 | [iris-off-01](../build/reports/vista-water/iris-off-01/results.json) |
| Vista 原生 Complementary，含故障注入 | 120 次刷新通过；编译失败期间副画面独立降级，恢复后使用原生光影 | [iris-final](../build/reports/vista-water/iris-final/verification.json) |
| Vista 原生 Complementary，近距离水域相机 | 120 次刷新通过，最终提交 71 条水面绘制指令 | [iris-water-view](../build/reports/vista-water/iris-water-view/verification.json) |

每次实际刷新均检查主深度纹理 ID、尺寸、全部深度像素、当前矩阵和上一帧矩阵不变。原生光影路径另检查副视口两张深度纹理存在且不属于主视口、管线归属正确。副画面生成非零水面指令，并覆盖同尺寸目标、重建、关闭重开和相机/FOV 变化。故障注入日志仅出现一次副管线降级原因，未出现未释放或重复释放的诊断。

截图：[原生光影水域近景](../build/reports/vista-water/iris-water-view/feed-120.png)、[对应主画面](../build/reports/vista-water/iris-water-view/main-120.png)、[普通水面取景器](../build/reports/vista-water/plain-01/feed-40.png)。已查看截图，人工水域的透明水面和水底可见；天然海岸的长期主观画质仍属于下面列出的人工验收范围。

## 验收边界

以上游戏测试执行了真实 Vista/Iris 渲染链，但采用世界副本中的人工 LOD 水域，电视开关通过纹理关闭/重开模拟。尚未完成真实电视方块交互、天然海岸长时间游玩中的反射残影和闪烁人工验收。普通水面降级结果未计入 Complementary 原生光影通过结果。Vista 跳过远程阴影绘制的限制保持不变。

复现方法见 [游戏回归脚本说明](../scripts/vista-water/README.md)。
