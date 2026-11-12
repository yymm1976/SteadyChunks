# SteadyChunks 兼容性与模块所有权

> Phase 0 交付物：与 FastNoise / Bye-Pregen / C2ME 的功能所有权表与 Mixin 冲突矩阵。
> 更新日期：2026-07-31
> 状态：Phase 0 初稿，Mixn 入口精确清单将在 Phase 1 各模块实现时补全

## 1. 模块所有权表

依据计划 §4 与 §10.1，对每个区块生命周期环节明确唯一所有者：

| 环节 | 所有者 | SteadyChunks 行为 | 备注 |
|---|---|---|---|
| 区块需求 / Ticket | SteadyChunks | 追踪、优先级、取消、公平性 | 核心新方向 |
| `STRUCTURE_STARTS` | SteadyChunks | 专项分析、缓存、算法优化 | 核心新方向 |
| `STRUCTURE_REFERENCES` | SteadyChunks | 索引与边界优化 | 核心新方向 |
| `BIOMES` | FastNoise 优先 | 仅调度，不重复内部优化 | Bye-Pregen 不冲突 |
| `NOISE` | FastNoise / Bye-Pregen 优先 | 资源预算和调度 | FastNoise 替换 populateNoise |
| `SURFACE` | FastNoise / Bye-Pregen 优先 | 资源预算和调度 | FastNoise 替换 surfaceBuilder |
| `CARVERS` | SteadyChunks（可独立模块） | 计时与有界调度；数据证明后再优化 | — |
| `FEATURES` | SteadyChunks | 深度分析与结构/特征专项优化 | 避免与 Bye-Pregen 重叠 |
| `INITIALIZE_LIGHT` / `LIGHT` 算法 | Bye-Pregen 优先 | 调度与完成平滑；算法替换可选 | YALight 启用时让路 |
| `LIGHT` 任务预算 | SteadyChunks | 在途光照区块数、完成回调整形 | 与 Bye-Pregen 协作 |
| `SPAWN` | 低优先级 | 先测量 | — |
| `FULL` 整合 | SteadyChunks | 每 Tick 预算与完成队列 | 核心新方向 |
| 区块发送 | SteadyChunks | 发送配额与玩家公平性 | 核心新方向 |
| 客户端准备 | SteadyChunks（诊断） | 诊断、反馈与可选节流 | 不替换渲染器 |
| 调度器（DAG + 多资源 + 完成优先） | SteadyChunks | 主 | 替代 C2ME 的 FlowSched 集成 |
| 自适应资源治理 | SteadyChunks | 主 | C2ME 弱 |
| 诊断观测 | SteadyChunks | 主（Chunk Flight Recorder） | Bye-Pregen 有 JFR 钩子但不重叠 |
| `populateNoise` / `populateBiomes` 算法 | FastNoise | 不注入 | — |
| `SurfaceBuilder.surfaceBuilder` 算法 | FastNoise | 不注入 | — |
| Palette 紧凑存储 | Bye-Pregen | 不注入 | FastNoise 写入侧优化但不重写 |
| Placement 优化 | Bye-Pregen | 不注入 | 10 个 placement mixin |
| NBT / 序列化 / IO GC-free | Bye-Pregen | 背压 + 调度 | 不修改序列化格式 |
| 结构选址 / Jigsaw / 引用优化 | SteadyChunks | 主 | C2ME 弱 |
| OpenCL / GPU 加速 | 禁止 | 不实现 | C2ME ARR 段，FastNoise 也有 |
| Native 数学加速 | 禁止 | 不实现 | 维护成本过高 |

## 2. 启动时所有权日志

依据计划 §10.1，启动时输出最终所有权表：

```
[SteadyChunks] Module ownership:
  Chunk scheduling              : SteadyChunks
  Structure profiling           : SteadyChunks
  FULL commit smoothing         : SteadyChunks
  Chunk send quota              : SteadyChunks
  Adaptive resource governor    : SteadyChunks
  Diagnostics (Flight Recorder) : SteadyChunks
  NOISE algorithm               : FastNoise         [if FastNoise present]
  BIOMES algorithm              : FastNoise         [if FastNoise present]
  SURFACE algorithm             : FastNoise         [if FastNoise present]
  Palette recount               : Bye-Pregen        [if Bye-Pregen present]
  Light algorithm               : Bye-Pregen        [if Bye-Pregen present]
  Serialization                 : Bye-Pregen        [if Bye-Pregen present]
  Light task budget             : SteadyChunks
  I/O backpressure              : SteadyChunks
  I/O GC-free path              : Bye-Pregen        [if Bye-Pregen present]
  Placement optimization        : Bye-Pregen        [if Bye-Pregen present]
```

未探测到 FastNoise / Bye-Pregen 时，对应算法行回退到 `Vanilla`，SteadyChunks 不接管算法层。

## 3. Mixin 冲突矩阵

按计划 §10.2 的格式维护。SteadyChunks 计划注入的关键 Mixin 入口：

| 目标类 | 目标方法 | FastNoise 注入 | Bye-Pregen 注入 | SteadyChunks 注入 | 注入顺序 | 冲突处理 | 回退路径 |
|---|---|---|---|---|---|---|---|
| `ServerChunkManager` | 主线程 tick | 否 | 否 | 是（调度入口） | SteadyChunks 独占 | — | 禁用调度器模块 |
| `ThreadedAnvilChunkStorage` | `getChunkFuture` / `schedule` | 否 | 否 | 是（任务派发） | SteadyChunks 独占 | — | 禁用调度器模块 |
| `ChunkHolder` | 任务回调 | 否 | 否 | 是（完成整形） | SteadyChunks 独占 | — | 禁用完成整形 |
| `ChunkMap` | `save` | 否 | 是（GC-free save） | 否 | Bye-Pregen 优先 | SteadyChunks 不注入 save 路径 | — |
| `IOWorker` | `submit` / `write` | 否 | 是（Raw IO） | 是（队列背压） | Bye-Pregen 内部，SteadyChunks 包装外层 | Mixin Gate 检测 Bye-Pregen 启用状态；启用时 SteadyChunks 只做背压，不注入 IOWorker 内部 | 关闭 I/O 背压模块 |
| `ChunkStorage` / `RegionFile` | 读写 | 否 | 是（Raw） | 否 | Bye-Pregen 独占 | SteadyChunks 不注入 | — |
| `ChunkSerializer` | 序列化 / 反序列化 | 否 | 是（worldgen state） | 否 | Bye-Pregen 独占 | SteadyChunks 不注入 | — |
| `NoiseChunkGenerator` | `populateNoise` / `populateBiomes` | 是（替换） | 否 | 否 | FastNoise 独占 | SteadyChunks 不注入 | — |
| `SurfaceSystem` / `SurfaceBuilder` | `surfaceBuilder` | 是（替换） | 否 | 否 | FastNoise 独占 | SteadyChunks 不注入 | — |
| `PaletteedContainer` | palette 操作 | 否 | 是（重写） | 否 | Bye-Pregen 独占 | SteadyChunks 不注入 | — |
| `LevelLightEngine` / `ThreadedLevelLightEngine` | 光照任务 | 否 | 是（YALight） | 是（任务预算） | Bye-Pregen 内部，SteadyChunks 外层调度 | Mixin Gate 检测 YALight；启用时 SteadyChunks 只做完成整形，不注入光照引擎内部 | 关闭光照调度模块 |
| `LightEngine` / `BlockLightEngine` / `SkyLightEngine` | 光照传播 | 否 | 是（YASuper） | 否 | Bye-Pregen 独占 | SteadyChunks 不注入 | — |
| `StructureStart` / `StructureManager` | 结构选址 | 否 | 否 | 是（规划分析） | SteadyChunks 独占 | — | 关闭结构优化模块 |
| `StructureTemplate` | 模板加载 | 否 | 否 | 是（元数据缓存） | SteadyChunks 独占 | — | 关闭模板缓存 |
| `PlacedFeature` | 特征放置 | 否 | 否 | 是（FEATURES 拆分观测） | SteadyChunks 独占 | — | 关闭 FEATURES 诊断 |
| `ServerConfigurationNetworkHandler` | 配置阶段 | 否 | 否 | 是（C2ME 互斥检测） | SteadyChunks 独占 | — | — |
| `Minecraft` (client) | 客户端主循环 | 否 | 否 | 是（帧时间反馈） | SteadyChunks 独占 | — | 关闭客户端反馈模块 |
| `ClientPacketListener` | 区块批次接收 | 否 | 是（YALight 接收） | 是（接收指标） | Bye-Pregen 内部 YALight 数据接收，SteadyChunks 外层指标采集 | Mixin Gate 检测 YALight；不修改 YALight 数据路径，只挂钩子统计 | 关闭客户端接收指标 |
| `SectionRenderDispatcher` | Section 编译 | 否 | 否 | 是（可选编译节流） | SteadyChunks 独占 | 与 Embeddium/Sodium 兼容探测；冲突时关闭 | 关闭客户端编译节流 |
| Placement 类（10 个） | `place` | 否 | 是（优化） | 否 | Bye-Pregen 独占 | SteadyChunks 不注入 | — |

## 4. 兼容 API 设计

依据计划 §10.3，SteadyChunks 提供以下只读或协作接口（可选，未适配模组仍使用保守路径）：

```java
package com.mochi_753.steadychunks.api;

// 注册阶段所有权
public interface StageOwnership {
    StageOwner ownerFor(ChunkStatus status);
    void registerOwner(ChunkStatus status, StageOwner owner);
}

// 注册世界生成任务安全等级
public interface WorldgenSafetyLevel {
    SafetyClass classify(Generator<?> generator);
    void registerClassifier(Generator<?> generator, SafetyClass clazz);
}

// 注册结构成本估计器
public interface StructureCostEstimator {
    CostEstimate estimate(StructureStart start);
    void registerEstimator(ResourceKey<Structure> key, CostEstimate estimator);
}

// 提供任务优先级提示
public interface PriorityHintProvider {
    int suggestPriority(ChunkPos pos, ChunkStatus target);
}

// 提供模块状态
public interface ModuleStateReport {
    Map<String, ModuleState> snapshot();
}

// 订阅缓存失效
public interface CacheInvalidationListener {
    void onInvalidate(InvalidationEvent event);
}

// 注册可安全并行的自定义生成器
public interface ParallelGeneratorRegistry {
    boolean register(ResourceLocation id, SafeGenerator generator);
}
```

### 4.1 API 稳定性

- API 包 `com.mochi_753.steadychunks.api` 在 Alpha 阶段标记 `@ExperimentalApi`；
- Beta 阶段冻结核心接口（`StageOwnership`、`WorldgenSafetyLevel`）；
- 1.0 后通过 semver 维护向后兼容。

### 4.2 未适配模组行为

未通过 API 注册的第三方世界生成代码默认按 `SafetyClass.C`（依赖模组实现）或 `SafetyClass.D`（必须串行）处理，使用保守串行路径。

## 5. C2ME 互斥策略

依据计划 §10.5：

### 5.1 检测点

- `ServerConfigurationNetworkHandler` 阶段（玩家连接前）扫描 mod 列表；
- 检测 `modid == "c2me"`（Fabric 版）或 vendored C2ME NeoForge 端口（通过 `c2me-base` 类存在性检测）。

### 5.2 默认行为

- 检测到 C2ME：**默认阻止游戏启动**，输出清晰错误：

```
[SteadyChunks] FATAL: C2ME detected.
SteadyChunks replaces C2ME's chunk scheduling and chunk system; coexistence is unsupported.
Please remove either SteadyChunks or C2ME.
Migration guide: docs/migration-from-c2me.md
```

### 5.3 开发模式例外

- 配置项 `[compatibility] c2me = "reject" | "analyzer_only" | "force_coexist"`；
- `analyzer_only`：仅启用 SteadyChunks 诊断器，不启用调度、chunk-system 替换、I/O 背压等冲突模块；
- `force_coexist`：仅供开发对照测试，启动时打印 `WARN` 不阻止，但不承诺正确性。

### 5.4 迁移文档

`docs/migration-from-c2me.md`（Phase 12 产出）需包含：

- C2ME 与 SteadyChunks 配置项映射表；
- 移除 C2ME 后的预期行为差异；
- 与 FastNoise / Bye-Pregen 共存时的所有权确认步骤。

## 6. 版本与模块探测

依据计划 §10.1：

### 6.1 探测时机

- `FMLCommonSetupEvent` 之前：通过 `ModList.get().isLoaded(modid)` 检测模组存在性；
- `FMLCommonSetupEvent`：通过反射或 API 调用探测模块启用状态；
- `ServerAboutToStartEvent`：最终所有权表确认与日志输出。

### 6.2 探测策略

| 目标 | 探测方式 | 失败回退 |
|---|---|---|
| FastNoise 存在 | `ModList.isLoaded("zfastnoise")` | 视为未安装 |
| FastNoise 模块状态 | 反射调用 `FastNoise.getConfig().isEnabled(module)`（若 API 可用） | 保守假设全启用，不重复优化 |
| Bye-Pregen 存在 | `ModList.isLoaded("byepregen")` | 视为未安装 |
| Bye-Pregen YALight 启用 | 反射检查 `Byepregen.getConfig().yalight` 或类存在性 `Class.forName("com.moepus.byepregen.yalight.LevelLightEngineYA")` | 保守假设启用，让路 |
| Bye-Pregen Palette 重写启用 | 反射检查 `PaletteedContainer` 是否被 mixin 替换（通过 `getClass().getName()` 含 `byepregen`） | 保守假设启用 |
| C2ME 存在 | `ModList.isLoaded("c2me")` 或 `Class.forName("com.ishland.c2me.base.C2MEMod")` | 视为未安装 |
| Embeddium / Sodium 存在 | `ModList.isLoaded("embeddium")` 或 `ModList.isLoaded("sodium")` | 视为未安装，不启用客户端编译节流 |

### 6.3 无法确认时

按计划 §5.5 与 §10.1，无法确认具体模块状态时采用**保守关闭**：

- 假设第三方模块已启用可能与之冲突的优化；
- SteadyChunks 关闭对应模块（不注入 Mixin）；
- 输出 `WARN` 日志说明关闭原因；
- 用户可通过配置 `[compatibility] unknown_chunk_system = "disable_risky_modules" | "force_enable"` 覆盖。

## 7. 版本更新与门控

依据计划 §10.4：

- 锁定测试版本：FastNoise 最新 1.21.x、Bye-Pregen 1.0.10.0；
- CI 每次构建运行最小兼容启动测试（计划 §1.5）；
- FastNoise / Bye-Pregen 更新版本后，新版本进入"兼容候选"状态；
- 兼容候选版本必须通过组合测试矩阵（计划 §10.4）才能进入"已验证"状态；
- 用户日志可看出每个优化由谁负责（计划 §10.4 验收标准）。
