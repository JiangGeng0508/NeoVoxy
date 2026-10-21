# 自动验证

使用 Java 21 和仓库内 Gradle wrapper。依赖已缓存时可添加 `--offline`；CPU 回归仍需 NeoForge 的类加载环境及 RocksDB 原生库，不需要启动游戏世界或创建 OpenGL 上下文。

```powershell
.\gradlew.bat check --console=plain
.\gradlew.bat build --console=plain
```

`check` 直接依赖 `testJunit` 和 `checkLangKeys`，`build` 通过 `check` 运行测试与语言检查。普通 Java `test` 任务仅作为依赖 `testJunit` 的入口，自身禁用，因此日志中的 `:test SKIPPED` 是预期行为；应查看 `:testJunit` 及其报告。未更改输入时 Gradle 可复用有效测试结果；需要强制重新执行测试时使用：

```powershell
.\gradlew.bat testJunit --rerun --console=plain
.\gradlew.bat testJunit --tests '*WorldSectionPositionTest' --console=plain
```

第二条只验证一个测试类，不能作为全套回归结果。断言失败、测试运行异常、存在测试类却未发现测试、显式测试过滤器未匹配到测试，都应使 `testJunit` 返回失败。失败详情和跳过项直接打印到控制台。

报告位置：

- HTML：`build/reports/tests/testJunit/index.html`
- JUnit XML：`build/test-results/testJunit/TEST-*.xml`
- 分发包：`build/libs/voxy-<版本>.jar`；测试类和测试资源不打入分发包。

## 语言键检查

```powershell
.\gradlew.bat checkLangKeys --console=plain
# 修改检查器后，在独立夹具工程中验证成功/失败边界；需要 Python 3
python scripts/verify-lang-keys.py
```

`checkLangKeys` 每次执行都会检查 `src/main/resources/assets/voxy/lang` 下的所有语言 JSON，要求至少存在 `en_us.json`、`zh_cn.json`，内容为非空对象且翻译值为非空字符串。所有语言必须包含相同键集合；新增语言也参与比较。保留后台生成等现有翻译，不删除“没有直接引用”的键。

源码检查使用运行 Gradle 的 JDK 21 自带 Java 语法解析器，只解析 `src/main/java`，不编译或加载 Minecraft。收集 `translatable` / `translatableWithFallback` 调用首参数中的静态字符串，检查 `voxy.`、`key.voxy.`、`message.voxy.` 及 `key.categories.voxy`；另外收集快捷键和消息专用命名空间的字符串，以覆盖 `KeyMapping` 与消息包装函数的间接使用。支持括号、转义及纯字符串字面量拼接；忽略注释、字符串里的示例代码和其他模组的键。按调用名匹配，不进行方法类型绑定。

变量、字段常量、枚举和运行时拼接不做数据流推导；未解析的翻译调用会列入报告的 `dynamicReferences`，不能将其视为静态覆盖。当前 5 处为画质 `renderQuality.0–6`、压力 `renderPressure.0–4`、树叶模式 `leafLodMode.fast/balanced/quality`、自动 `.tooltip` 和快捷键消息包装调用。快捷键与消息命名空间中的完整字面量仍会单独检查。新增动态键需核对实际取值与翻译集合；中英同时删掉一个仅动态引用的键不会被集合比较发现。

报告为 `build/reports/lang-keys/check.json`，包含状态、各语言键数、静态键的源码行号、动态调用位置和错误。失败时控制台也会列出缺键及引用位置。解析错误、缺语言或源码目录、非字符串/空白翻译、键集合差异及缺少静态键都会使 `checkLangKeys`、`check` 和 `build` 返回失败。

独立验证脚本不修改项目源码或语言文件，在 `build/reports/lang-keys/fixtures-*` 保留每个夹具及 Gradle 日志，汇总到 `fixture-verification.json`。它验证静态/动态区分、注释与转义处理、双语同时缺键、间接消息键、缺语言、额外语言不完整、无效内容及源码缺失/解析错误，不需要 NeoForge 测试环境。

## 参考验证的覆盖映射

参考来源为 `neo-voxy-multiversion` 的 Minecraft 1.21.1 根工程。适用 CPU 验证已融入 `src/test/java`，无需维护另一套 `main()` 程序或依赖参考仓库目录。

| 参考验证 | 本项目主要测试类 | 覆盖 |
|---|---|---|
| `WorldSectionPositionVerification` | `WorldSectionPositionTest` | 固定旧版/扩展缓存键、全部高度和编码层级、边界与随机坐标、层级切换、扩展坐标越界 |
| `TerrainMipperVerification` | `MipperTest` | 256 种占用、薄墙/屋顶、四级地表高度与材料回填、流体与光照 |
| `LiveHandoffVerification` | `LiveHandoffStateTest`、`SectionHandoffTest`、`VisibleSectionIndexTest` | 同帧决定、250 ms 等待中断、空间回差、实际可见区段与边界 |
| `RocksDBLifecycleVerification` | `RocksDBLifecycleTest`、`LittleTilesStorageLifecycleTest` | 真实数据库重开/关闭、关闭等待 native iterator、旧世界/存储迁移 |
| `DistantMeshLodVerification` | `DistantMeshLodTest`、`LodViewStateTest` | 几何面积/绕序、索引预算、像素剔除、放大恢复、复用失效 |
| `DistantMeshCoarseningVerification` | `DistantMeshCoarseningTest`、`DistantPolylineLodTest`、`DistantMeshBuilderTest` | 真实粗网格、材质/边界、线缆端点与误差、透明安全合并 |
| `NativeLodSelectionVerification` | `NativeLodSelectionTest` | 跨区段取级、模型变换、视距/分辨率/FOV 缓存失效、多视图隔离 |

坐标测试使用固定十六进制缓存键，避免编码器和解码器同时改错却通过往返检查。另对 4,096 个有符号高度和 16 个格式层级，分别检查边界和固定随机种子的横向坐标，共 131,072 次往返；层级 0–15 是键格式的容量，当前渲染最大层级仍是 `WorldEngine.MAX_LOD_LAYER`。旧布局保留 24 位 X/Z 和 8 位 Y；扩展布局为 23 位 X、24 位 Z、12 位 Y。越界拒绝测试针对现有扩展布局检查，不改变旧布局的历史编码行为。本组不执行 GPU 的坐标解码程序。

保留的本项目回归还包括 `VoxyNetworkTest`、`LodSectionRequestsTest`、`VoxyClientNetworkTest`、`LodMappingTranslatorTest`、`SectionSerializationStorageTest`、`MapperImportTest`、`LodBlockAndTintGetterTest` 和 `SectionUpdateRouterTest`，覆盖网络协商/重试/连接隔离、远端映射、负缓存、季节表面标记、连接纹理邻居查询与更新路由。配置持久化、Domum/信标重建及静态模型组装也包含在同一套测试中。

## 可选 GPU 检查

```powershell
# 只运行 GPU 测试
.\gradlew.bat testJunit --console=plain -PverifyGpu=true --tests '*GpuTest'
# 构建并运行全部 CPU/GPU 测试
.\gradlew.bat build --console=plain -PverifyGpu=true
# 汇总当前 XML 证据，缺项、跳过、失败或缺少环境记录时返回非零
python scripts/summarize-gpu-tests.py --expect-run --output build/reports/gpu-summary.json
```

旧入口 `-I scripts/enable-mesh-gpu-tests.gradle` 仍可使用，现在也启用地形 shader 检查。默认 `check/build` 不创建 GPU 测试上下文，打印 `GPU checks NOT RUN` 并由 JUnit 明确记录 skipped。只设置 `-PverifyGpu=false` 与默认相同；属性值必须为 `true` 或 `false`。

完整 GPU 组需要 OpenGL 4.6，以及 64 位路径使用的 `GL_ARB_gpu_shader_int64`。显式启用后，GLFW/上下文不可用、扩展缺失、shader 编译/链接失败或数据不一致均为失败，不能当作通过。它通过隐藏窗口执行，不需要玩家世界；JUnit 并行执行被关闭，避免 GLFW 全局状态串扰。

启用任何 GPU 组时，`testJunit` 不使用 Gradle up-to-date/build cache 结果，每次都在当前驱动上执行。普通 CPU 测试仍可复用有效结果。`--tests` 仍会限制实际执行范围，单类成功不代表完整 GPU 组成功。

| 测试类 | JUnit 项数 | 覆盖 |
|---|---:|---|
| `DistantMeshLodGpuTest` | 8 | VBO/索引生命周期、选级、颜色/深度回放、F3 统计、信标/激光普通与 TAA 路径、10 种 shader 变体 |
| `MeshReplacementGpuTest` | 2 | Domum/信标替换、失败保留与删除释放 |
| `LittleTilesUploadGpuTest` | 4 | 双层上传、失败恢复、旧快照/旧世界隔离与释放 |
| `ScreenspaceGpuTest` | 5 | 近面保护、世界曲率、深度约定、Hi-Z 和视口尺寸 |
| `ViewportLodGpuTest` | 1 | 主副视口细节选择与深度目标隔离 |
| `TerrainLodGpuTest` | 10 | 地形放大、CPU/GPU 选级对照、32/64 位字段、流体角点、完整模型 ID 范围与区段坐标 |

`TerrainLodGpuTest` 加载分发使用的 shader 资源，递归展开本项目的 `#import`。参考节点调试的未调用 `printf` 被去除，测试使用自己定义的输入、缓冲和输出入口；被验证的解码、模型、投影函数来自正式资源。曲率为零时对照 CPU 选级，曲率行为由 `ScreenspaceGpuTest` 单独覆盖。细分路径不需要 Hi-Z 深度纹理，完整遮挡行为不由该项推断。

地形测试的 10 项包含 2,591,908 次数据断言：两条路径各枚举 65,536 个模型 ID × 16 种附加数据；随机 quad 字段对照；131,072 个 CPU/GPU 选级组合；65,536 个区段坐标 × 4 个输出分量；以及流体高度、模型角点与缩放检查。这些循环断言数与 JUnit 测试项数是两种单位，不能相加。

每个 GPU 类在控制台和 XML 的 `system-out` 中输出 `GPU_ENV` JSON，包含显卡、厂商、OpenGL/GLSL、64 位扩展、Java 和操作系统。新增数据循环输出 `GPU_CHECKS ... assertionsCompleted=...`，最终成功与否以 JUnit 状态为准。汇总脚本读取所有当前 GPU 测试类，生成 `passed`、`failed`、`partial` 或 `not_run` 状态；带 `--expect-run` 时仅接受完整通过且含环境记录的结果。

`build/test-results/testJunit-run.json` 在执行开始时标记 running，只有测试任务成功结束才改为 passed。汇总脚本同时检查该标记及 XML 时间戳，拒绝把失败/中断后残留的旧 XML 当作新结果。归档时同时保留 XML 目录和标记文件；读取其他目录可使用 `--results <目录> --run-status <标记文件>`。脚本总结已有证据，时间戳不代表又执行了一次硬件验证。

## 验证记录与边界

T1 在 2026-10-21 实测普通 `check` 为 92 项通过、20 项 GPU 跳过，启用 GPU 的 `build` 为 112 项通过；故意注入的 JUnit 断言让 `check/build` 均返回退出码 1，无匹配测试过滤器也返回 1。T2 新增 10 项 GPU 测试后，共 32 个测试类、122 项，其中 30 项需显式启用 GPU。以当次 XML 报告为准，不把 skipped 当成 passed。

T2 最终两次相同的 GPU `build` 均实际运行并通过全部 122 项；旧 init 入口的 GPU-only 检查通过 30 项。模拟 GLFW 库不可用时，报告 NOT RUN 和初始化失败，退出码 1，完成标记保持 running。一次前期 GPU-only 运行曾在测试结果产生前原生退出，未定位原因；后续诊断运行与两次正式构建未复现，不将其描述为已修复的生产问题。

T3 在 2026-10-21 验证中英各 216 个键一致、127 个静态键齐全，并列出 5 处动态调用。18 项独立检查器夹具全部通过；实际工程中从两种语言同时移除 `voxy.config.distantGen.enabled` 后，`check/build` 均因静态缺键返回退出码 1。语言文件按原字节恢复，普通完整构建为 92 项通过、30 项 GPU 跳过，分发 JAR 哈希与 T2 相同。日志及结构化结果保存在 `build/reports/lang-keys/`。

这些回归验证代码契约和指定 OpenGL 行为。shader-pack 片段输出使用测试桩，不能代替实际 Iris 光影包验收；完整世界画面、实际联动、Vista 副视口与长期多人负载仍需游戏验收。模型性能对照另见 [基准运行说明](../scripts/model-lod-benchmark/README.md)。
