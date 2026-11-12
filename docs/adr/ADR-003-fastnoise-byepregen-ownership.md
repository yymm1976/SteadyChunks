# ADR-003: FastNoise 与 Bye-Pregen 的功能所有权

- **状态**：Accepted
- **日期**：2026-07-31
- **决策者**：SteadyChunks 项目

## 上下文

FastNoise（MPL-2.0）与 Bye-Pregen（LGPL-3.0-only）是 NeoForge 1.21.1 生态下两个成熟的区块生成相关模组，覆盖范围与 SteadyChunks 部分重叠：

**FastNoise 覆盖**：
- `NoiseChunkGenerator.populateNoise` / `populateBiomes`（替换）
- `SurfaceBuilder.surfaceBuilder`（替换）
- Palette 紧凑存储（生成时写入侧）
- 通过减少分配、避免 palette resizing、延迟 block counting、预计算与缓存加速

**Bye-Pregen 覆盖**：
- YALight 光照引擎（重写 `LevelLightEngine` / `LightEngine` / `BlockLightEngine` / `SkyLightEngine` 等）
- GC-free IO（重写 `IOWorker` / `ChunkStorage` / `RegionFile` / `ChunkSerializer`）
- Palette 重写（`PaletteedContainer` / `GlobalPalette` / `HashMapPalette` / `LinearPalette` / `SingleValuePalette`）
- Placement 优化（10 个 placement mixin）
- NBT 优化（`CompoundTagFastRuntime` / `CompoundTagLoadSizing`）
- Arenae 内存池（`ChunkAccessArenae` / `LevelChunkArenae` / `NoiseChunkArenae` 等）
- vendored C2ME NeoForge 端口（chunk-system / scheduling / chunkio）

**功能重叠风险**：
- Palette：三方都碰（FastNoise 写入侧 + Bye-Pregen 重写 + C2ME allocs）
- 光照：C2ME threading-lighting + Bye-Pregen YALight
- IO / 序列化：C2ME rewrites-chunkio + Bye-Pregen gcfree
- 调度：C2ME opts-scheduling + Bye-Pregen vendored

## 决策

SteadyChunks **不重复** FastNoise 与 Bye-Pregen 已覆盖的算法层优化，明确以下所有权：

### 3.1 FastNoise 所有（SteadyChunks 不实现）

- `populateNoise` / `populateBiomes` 算法
- `surfaceBuilder` 算法
- 生成时 palette 写入侧优化

**SteadyChunks 行为**：BIOMES / NOISE / SURFACE 阶段只做调度与资源预算，不注入 `NoiseChunkGenerator` / `SurfaceSystem` / `SurfaceBuilder` 内部方法。Mixin Gate 检测到 FastNoise 启用时，对应调度路径调整为只限流不限内容。

### 3.2 Bye-Pregen 所有（SteadyChunks 不实现）

- YALight 光照算法
- GC-free IO 与序列化格式
- Palette 重写
- Placement 优化
- NBT 优化
- Arenae 内存池

**SteadyChunks 行为**：
- 不注入 `IOWorker` / `ChunkStorage` / `RegionFile` / `ChunkSerializer` 内部方法（Bye-Pregen 已重写）；
- 不注入 `LevelLightEngine` / `LightEngine` / `BlockLightEngine` / `SkyLightEngine` 内部方法（YALight 已重写）；
- 不注入 `PaletteedContainer` 及 palette 子类（Bye-Pregen 已重写）；
- 不注入 Placement 类（Bye-Pregen 已优化）；
- 仅做 **外层** I/O 队列背压、光照任务预算与完成整形、调度优先级。

### 3.3 SteadyChunks 所有（FastNoise / Bye-Pregen 不覆盖）

- 区块需求 / Ticket / 优先级 / 软取消
- 调度器（DAG + 多资源 + 完成优先）
- 自适应资源治理 / 紧急模式
- FULL 整合队列与完成整形
- 区块发送配额与玩家公平
- 客户端反馈协议
- 诊断观测（Chunk Flight Recorder）
- 结构选址 / Jigsaw / 引用优化
- FEATURES 拆分与长尾治理

### 3.4 让路原则

当 FastNoise / Bye-Pregen 启用时，SteadyChunks 对应模块**让路**：

- 让路 = 不注入算法层 Mixin，但保留调度与诊断层；
- 让路决策在启动时通过 Mixin Gate 一次性确定，运行时不再切换；
- 让路状态输出到所有权日志（计划 §10.1）。

### 3.5 未安装 FastNoise / Bye-Pregen 时

- BIOMES / NOISE / SURFACE 算法回到原版路径，SteadyChunks 仅做调度；
- 光照算法回到原版路径，SteadyChunks 可选做完成整形（不重写算法）；
- 序列化与 IO 回到原版路径，SteadyChunks 做 I/O 背压；
- Palette 回到原版路径，SteadyChunks 不优化。

## 后果

### 正面

- 与 FastNoise / Bye-Pregen 形成互补，用户可叠加收益；
- 避免 Mixin 冲突，三方可同装；
- SteadyChunks 工程量减少（不重写光照、Palette、IO 算法）；
- 许可证清洁（不复制 LGPL/MPL 代码）。

### 负面

- SteadyChunks 在未安装 FastNoise / Bye-Pregen 时，相对 C2ME 的算法层收益可能不足；
- 必须严格维护 Mixin Gate 逻辑，否则升级后可能产生注入冲突；
- 启动时所有权日志必须清晰，否则用户难以理解每个优化由谁负责。

## 备选方案

### A. SteadyChunks 自己实现光照 / Palette / IO 优化

被否决：
- Bye-Pregen YALight 在 NeoForge 1.21.1 已成熟且活跃维护；
- SteadyChunks 重写需大量工程与测试，且必然与 Bye-Pregen 冲突；
- 违反"不重复 Bye-Pregen 已启用模块"的非目标（计划 §3）。

### B. 强制依赖 FastNoise / Bye-Pregen

被否决：
- 限制用户选择；
- SteadyChunks 在最小 NeoForge 环境下应仍可运行（仅诊断 + 调度）。

### C. 通过 Mixin 优先级抢占 FastNoise / Bye-Pregen

被否决：
- Mixin 优先级是隐式的，依赖加载顺序；
- 抢占会破坏 FastNoise / Bye-Pregen 的功能完整性，引入世界生成正确性风险；
- 违反"兼容"承诺。
