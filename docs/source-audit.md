# SteadyChunks 源码审计报告

> Phase 0 交付物：竞争基线、许可证边界、模块重叠表与性能契约的源码级清单。
> 更新日期：2026-07-31
> 状态：Phase 0 初稿，精确提交哈希将在 Phase 1 工程骨架建立后补全

## 1. 目标环境冻结

| 项 | 版本 | 来源 |
|---|---|---|
| Minecraft | 1.21.1 | 原版 |
| NeoForge | 21.1.218 | `gradle.properties` |
| Java | 21 (toolchain) | `build.gradle.kts` |
| Parchment mappings | 2024.11.17 (MC 1.21.1) | `gradle.properties` |
| Gradle | 9.2.1 | `gradle-wrapper.properties` |
| moddev plugin | 2.0.139 | `build.gradle.kts` |
| FastNoise | 1.21.x（多 loader，最新 Modrinth 版本） | Modrinth `zfastnoise` |
| Bye-Pregen | 1.0.10.0（NeoForge 21.1.233） | `gradle.properties`（vendored） |
| C2ME 1.21.1 backport | 0.4.0-alpha.0.116 | Bye-Pregen vendored jar 命名 |
| FlowSched | `fae2126`（C2ME 引用版本） | C2ME `.gitmodules` |
| 整合包基线 | 通用 NeoForge 1.21.1 + FastNoise + Bye-Pregen | 用户决策 2026-07-31 |
| 默认 JVM 参数 | 待 Phase 1 运行配置定稿 | — |
| 默认视距 | 待 Phase 2 基准校准 | — |

## 2. 竞争 / 兼容项目源码功能地图

每个项目按"功能—注入点—许可证—重叠"清单列出。

### 2.1 C2ME（C²M-Engine）

| 项 | 内容 |
|---|---|
| URL | https://github.com/RelativityMC/C2ME-fabric |
| 1.21.1 分支 | `backports/1.21.1`（LTS） |
| 许可证 | **混合**：主体代码 MIT；`c2me-opts-accel-opencl/` 为 All Rights Reserved |
| SPDX | `MIT + ARR`（混合） |
| 项目定位 | 多核并行化区块生成 / IO / 加载的 Fabric 模组，对原版保持 parity |
| 是否改变线程模型 | **是**。并行区块生成、IO 工作线程、光照线程化（`c2me-threading-lighting`），通过 FlowSched 做任务调度 |
| 是否改变世界生成顺序 | 否（README 强调 vanilla parity），但利用并行 + DFC + 原版 bug 修复 |
| 是否写入存档 | **是**。`c2me-rewrites-chunk-serializer` 与 `c2me-rewrites-chunkio` 重写区块序列化与 IO |
| 与 FastNoise 冲突 | 否（互补，JMH 基准显示叠加收益） |
| 与 Bye-Pregen 冲突 | 否（Bye-Pregen vendored C2ME NeoForge 端口并主动兼容） |
| 可否直接复用 | **部分**。MIT 段可借鉴架构思路但需 clean-room；ARR 段严禁复用 |
| 可否仅借鉴思路 | **是**（MIT 段） |
| 需要 clean-room 重写的部分 | 调度器、chunk-system、chunkio、lighting-threading 全部；ARR 段不可参考 |

**核心模块**（按子工程）：

```
c2me-base                              基础 accessor 与 util
c2me-server-utils                      服务端工具
c2me-fixes-chunkio-threading-issues    chunkio 线程 bug 修复
c2me-fixes-general-threading-issues    通用线程 bug 修复
c2me-fixes-worldgen-threading-issues   世界生成线程 bug 修复
c2me-fixes-worldgen-vanilla-bugs       原版世界生成 bug 修复
c2me-notickvd                          无 tick 视距解耦
c2me-client-uncapvd                    客户端解除视距上限
c2me-opts-allocs                       分配优化
c2me-opts-chunkio                      chunkio 优化
c2me-opts-dfc                          DFC 不动点密度函数
c2me-opts-math                         数学优化
c2me-opts-natives-math                 native 数学（Clang 编译）
c2me-opts-scheduling                   调度优化（FlowSched 集成）
c2me-opts-src                          源码优化
c2me-opts-worldgen-biome-cache         生物群系缓存
c2me-opts-worldgen-general             世界生成通用优化
c2me-opts-worldgen-vanilla             原版世界生成优化
c2me-opts-accel-opencl                 OpenCL 加速【ARR 严禁复用】
c2me-rewrites-chunk-serializer         区块序列化重写
c2me-rewrites-chunk-system             区块系统重写
c2me-rewrites-chunkio                  chunkio 重写
c2me-threading-lighting                光照线程化
```

**关键 Mixin 注入点**（来自 `c2me-base.mixins.json` 等）：

- `IChunkHolder` / `IThreadedAnvilChunkStorage` / `IStorageIoWorker` / `IServerChunkManager` / `IServerLightingProvider`（accessor）
- `INoiseChunkGenerator` / `IDoublePerlinNoiseSampler` / `IPerlinNoiseSampler` / `ISimplexNoiseSampler`（噪声 accessor）
- `IMultiNoiseBiomeSource` / `IAquiferSamplerFluidLevel` / `IBlender`（世界生成 accessor）
- `IRegionBasedStorage` / `IRegionFile` / `IWeightedList` / `IVersionedChunkStorage`（IO accessor）
- `scheduler.MixinServerChunkManager` / `scheduler.MixinThreadedAnvilChunkStorage`（调度入口）
- `theinterface.MixinStorageIoWorker`（IO 接口）
- `instrumentation.MixinServerChunkManager`（插桩）
- `bugfixes.config_enforce_main.MixinServerConfigurationNetworkHandler`（配置强制）
- `util.log4j2shutdownhooksnomore.*`（关停钩子）

**外部依赖**：`rxjava`、`asyncutil`、`jctools`、FlowSched（vendored）。

### 2.2 FlowSched

| 项 | 内容 |
|---|---|
| URL | https://github.com/RelativityMC/FlowSched |
| 许可证 | **MIT** |
| SPDX | MIT |
| 项目定位 | Java 多资源优先级任务调度器集合（纯 Java 库，非 MC 模组） |
| 是否改变线程模型 | **是**。本库就是调度器本身：基于优先级 + 多资源同时锁定 |
| 是否改变世界生成顺序 | 无直接影响 |
| 是否写入存档 | 否 |
| 与 FastNoise 冲突 | N/A（库） |
| 与 Bye-Pregen 冲突 | N/A（库） |
| 可否直接复用 | **是**（MIT，可直接 vendored 引入） |
| 可否仅借鉴思路 | 是 |
| 需要 clean-room 重写的部分 | 无（可直接复用）；若想轻量化可 clean-room |

**核心模块**（包路径）：

```
com.ishland.flowsched.executor     优先级任务执行器
com.ishland.flowsched.scheduler    多资源锁定调度器
com.ishland.flowsched.structs      自定义数据结构
com.ishland.flowsched.util         工具
```

**依赖**：`fastutil 8.5.12`、`slf4j-api 2.0.9`、`rxjava 3.1.12`（api）。

**关键能力**：优先级 + 多资源同时锁定，避免传统 lock-per-resource 死锁；最近优化 `byte[]` 依赖引用计数。

### 2.3 Bye-Pregen

| 项 | 内容 |
|---|---|
| URL | https://github.com/MoePus/Bye-Pregen |
| 许可证 | **LGPL-3.0-only**（根目录 LICENSE 文件为准；`gradle.properties` 中误标为 MIT） |
| SPDX | LGPL-3.0-only |
| 项目定位 | NeoForge 1.21.1 模组，重写区块生成 / IO / 光照内部存储格式，消除 GC 压力 |
| 是否改变线程模型 | 间接（通过 vendored C2ME `rewrites-chunk-system` + `opts-scheduling` + `rewrites-chunkio`）；本身主要做存储格式与 GC-free 优化 |
| 是否改变世界生成顺序 | 不改变生成语义；改写存储路径与放置算法以消除分配 |
| 是否写入存档 | **是**。`gcfree.ChunkMapGcFreeSaveMixin` / `gcfree.ChunkSerializerWorldgenStateMixin` / `gcfree.ChunkStorageRawMixin` / `gcfree.IOWorkerRawMixin` 直接重写存档读写路径 |
| 与 FastNoise 冲突 | 否（互补，运行时依赖 FastNoise） |
| 与 C2ME 冲突 | 否（vendored C2ME 端口） |
| 可否直接复用 | **否**（LGPL 不可复制进 MIT 项目） |
| 可否仅借鉴思路 | **是**（YALight 光照 / Arenae 内存池 / Palette 重写设计可学习） |
| 需要 clean-room 重写的部分 | 全部；不可复制代码，只能通过接口调用或运行时依赖 |

**核心模块**（包路径）：

```
com.moepus.byepregen.bootstrap       启动与 MixinGate
com.moepus.byepregen.compat          兼容层（C2ME/FastNoise/Lithium/Sable/Voxy）
com.moepus.byepregen.gcfree          GC-free IO 与序列化
com.moepus.byepregen.jfr             JFR 钩子
com.moepus.byepregen.mixin           Mixin 入口
com.moepus.byepregen.optimization    通用优化
com.moepus.byepregen.PaletteContainer  Palette 重写
com.moepus.byepregen.PostProcess     后处理
com.moepus.byepregen.worldgen        世界生成
com.moepus.byepregen.yalight         YALight 光照引擎
com.moepus.byepregen.Feature         FEATURES 优化
com.moepus.byepregen.test            测试
```

**关键 Mixin 注入点**（约 90 个 mixin，按类别）：

- **GC-free / IO 原始访问**：`gcfree.{ChunkMapGcFreeSave, ChunkSerializerWorldgenState, ChunkStorageRaw, IOWorkerRaw, LevelChunkWorldgenState}Mixin`
- **Arenae 内存区**：`ChunkAccessArenae`、`LevelChunkArenae`、`NoiseChunkArenae`、`NoiseInterpolatorArenae`、`NoiseBasedChunkGeneratorArenae`、`NoiseChunkCellCacheArenae`、`ChunkSerializerArenaeRead`
- **YALight 光照引擎**：`LightEngineYASuper`、`BlockLightEngineYASuper`、`SkyLightEngineYASuper`、`LevelLightEngineYA`、`ThreadedLevelLightEngineYA`、`ChunkAccessYALightData`、`LevelChunkYALightData`、`ImposterProtoChunkYALightData`、`EmptyLevelChunkYALightData`、`ChunkAccessYASkyLightSources`、`ChunkSkyLightSources{Dirty,Storage}`、`ClientPacketListenerYALight`、`ClientboundLightUpdatePacketDataYALight`、`WorldGenRegionYALight`、`LevelRendererYALight`(client)
- **Palette 重写**：`GlobalPalette`、`HashMapPalette`、`PaletteedContainer`、`LinearPalette`、`SingleValuePalette`
- **Placement 优化**：`CarvingMaskPlacement`、`CountOnEveryLayerPlacement`、`EnvironmentScanPlacement`、`FixedPlacement`、`HeightmapPlacement`、`HeightRangePlacement`、`InSquarePlacement`、`PlacementFilter`、`RandomOffsetPlacement`、`RepeatingPlacement`
- **NBT 优化**：`nbt.CompoundTagFastRuntime`、`nbt.CompoundTagLoadSizing`
- **兼容层**：`compat.C2MEHookCompatibilityMixin`、`compat.C2MEServerBlockTickingMixin`、`compat.FastNoiseOpenCLArenaeMixin`、`compat.LithiumHashPaletteMixin`、`compat.SableNaturalSpawnerMixin`、`compat.VoxyWorldConversionFactoryMixin`

**运行时依赖**：FastNoise、vendored C2ME NeoForge 端口（`libs/c2me-neoforge-*-mc1.21.1-0.4.0-alpha.0.116.jar`）、Lithium、Sodium、Forgified Fabric API、Architectury、Chunky、Sable、Voxy。

### 2.4 FastNoise

| 项 | 内容 |
|---|---|
| URL | https://codeberg.org/ZenXArch/FastNoise（Codeberg WebFetch 受限） / https://modrinth.com/mod/zfastnoise |
| 许可证 | **MPL-2.0**（Mozilla Public License 2.0，file-level copyleft） |
| SPDX | MPL-2.0 |
| 项目定位 | 现代世界生成优化模组，替换 `NoiseChunkGenerator` 的存储路径以加速 noise/biome/surface 阶段 |
| 是否改变线程模型 | 否（单线程内的算法优化） |
| 是否改变世界生成顺序 | 维持 vanilla parity，包括 datapack；可能有 vanilla 非确定性（MC-55596） |
| 是否写入存档 | 否（只优化生成过程中的存储，不写存档） |
| 与 C2ME 冲突 | 否（互补） |
| 与 Bye-Pregen 冲突 | 否（被 Bye-Pregen 运行时依赖） |
| 可否直接复用 | **否**（MPL-2.0 不可复制进非 MPL 项目） |
| 可否仅借鉴思路 | **是**（减少 palette resizing、延迟 block counting、缓存 block state） |
| 需要 clean-room 重写的部分 | 全部；不可复制代码 |

**核心替换点**：

```
NoiseChunkGenerator.populateNoise      替换：减少分配、避免 palette resizing、延迟 block counting
NoiseChunkGenerator.populateBiomes     替换：预计算、缓存
SurfaceBuilder.surfaceBuilder          替换：紧凑存储
```

**已知明确不兼容**：

- Moonrise（"changes minecraft internals drastically"）
- Noisium（FastNoise 是其继任者，作者致谢 Stevenplays）
- Anti-Xray（紧凑存储与额外 block state 数据冲突）

**与 C2ME 叠加收益**（JMH 基准，来自 Modrinth README）：

- Noise 阶段：1.3x → 1.95x
- Surface 阶段：1.4x → 2.78x
- End Surface：高达 2023x

### 2.5 NeoForge 1.21.1 区块与世界生成实现

| 项 | 内容 |
|---|---|
| 许可证 | LGPL-3.0-only（NeoForge） |
| 关键类 | `ServerChunkManager` / `ThreadedAnvilChunkStorage` / `ChunkHolder` / `ChunkStatus` / `ChunkStep` / `NoiseChunkGenerator` / `SurfaceSystem` / `StructureManager` / `StructureTemplate` / `StructureStart` / `PlacedFeature` / `LevelLightEngine` / `ThreadedLevelLightEngine` / `IOWorker` / `ChunkStorage` / `RegionFile` / `PaletteedContainer` |
| 注入目标 | SteadyChunks 通过 Mixin 注入这些类，但不重写其内部算法（除非 Phase 6/7/8 数据证明必要） |
| 世界生成顺序 | ChunkStatus 链：`EMPTY → STRUCTURE_STARTS → STRUCTURE_REFERENCES → BIOMES → NOISE → SURFACE → CARVERS → FEATURES → INITIALIZE_LIGHT → LIGHT → SPAWN → FULL` |

### 2.6 Minecraft 客户端区块接收与渲染调度

| 项 | 内容 |
|---|---|
| 许可证 | Mojang EULA（不可复用源码） |
| 关键类 | `ClientPacketListener` / `ClientChunkStorage` / `LevelRenderer` / `SectionRenderDispatcher` / `SectionRenderCache` |
| SteadyChunks 责任 | 仅诊断与可选节流；不替换渲染器；与 Embeddium/Sodium 兼容探测 |

## 3. 许可证策略

### 3.1 总策略

SteadyChunks 主许可证：**MIT**（用户决策 2026-07-31）。

| 来源 | 许可证 | 复用方式 |
|---|---|---|
| FlowSched | MIT | **可直接 vendored 或依赖** |
| C2ME MIT 段 | MIT | 借鉴思路 + clean-room 重写；保留版权声明 |
| C2ME ARR 段（`opts-accel-opencl`） | ARR | **严禁参考**，不可 clean-room |
| Bye-Pregen | LGPL-3.0-only | 不可复制代码；可运行时依赖或通过接口调用；YALight / Arenae / Palette 设计可学习但需 clean-room |
| FastNoise | MPL-2.0 | 不可复制代码；可运行时依赖；设计原则可 clean-room 实现 |
| NeoForge | LGPL-3.0-only | 通过 Mixin / API 调用，不复制代码 |
| Minecraft 原版 | Mojang EULA | 不可复用源码；只能通过 Mixin / 反射 / 官方 API |

### 3.2 来源登记要求

对每个借鉴点保留：

```
来源项目
来源文件路径
来源提交哈希
借鉴内容描述
clean-room 实现路径
```

### 3.3 第三方通知与发布物

发布物必须包含：

- `LICENSE`（SteadyChunks MIT）
- `NOTICE`（包含所有借鉴来源的版权声明）
- `THIRDPARTY.md`（所有运行时依赖与许可证）
- 来源清单（来自来源登记）

## 4. 性能契约（待 Phase 2 冻结）

依据计划 §6.2，1.0 性能门槛为暂定目标，Phase 2 后冻结正式门槛。当前仅记录约束：

- 诊断关闭时运行开销低于 1%
- 基础计数开启时低于 3%
- P99 帧时间 / P99 MSPT 改善目标不少于 25% / 20%（相对 C2ME 对照组）
- 24 小时持续跑图无死锁、崩溃、内存持续增长或存档损坏

## 5. 待补全项

| 项 | 状态 | 计划补全时机 |
|---|---|---|
| C2ME 1.21.1 backport 精确提交哈希 | 待补 | Phase 1 CI 建立 |
| FlowSched `fae2126` 验证 | 待补 | Phase 1 |
| Bye-Pregen 1.0.10.0 提交哈希 | 待补 | Phase 1 |
| FastNoise mixin 文件清单 | 待补（Codeberg WebFetch 受限） | Phase 1，通过本地安装 jar 反编译 |
| NeoForge 1.21.1 关键类源码入口 | 待补 | Phase 2 编写诊断器时 |
| 默认 JVM 参数 | 待定 | Phase 1 运行配置 |
| 默认视距 | 待定 | Phase 2 基准校准 |
| 目标冒险整合包列表 | 待定 | Phase 2 性能门槛冻结前 |
