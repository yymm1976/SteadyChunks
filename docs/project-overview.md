# SteadyChunks 项目概览

> 本文档基于开发计划与 Phase 0 源码审计预备研究，作为 Phase 0 正式启动前的项目定位基线。
> 更新日期：2026-07-31

## 1. 角色定位

SteadyChunks 是 Minecraft 1.21.1 + NeoForge + Java 21 环境下的区块生命周期调度与 QoS 模组。它在生态中的位置：

```
           ┌────────────────────────────────────────────────────┐
           │                  SteadyChunks                       │
           │  需求追踪 / 优先级 / 背压 / 完成整形 / 发送配额 /    │
           │  自适应资源治理 / 诊断观测 / 客户端反馈              │
           └─────────────┬──────────────────────┬───────────────┘
                         │ 让路（兼容探测）      │ 让路（兼容探测）
                         ▼                      ▼
            ┌──────────────────────┐  ┌─────────────────────────┐
            │      FastNoise       │  │      Bye-Pregen         │
            │  NOISE / BIOMES /    │  │  YALight 光照 / GC-free │
            │  SURFACE 算法优化    │  │  IO / Palette /         │
            │  (MPL-2.0)           │  │  Placement / NBT (LGPL) │
            └──────────────────────┘  └─────────────────────────┘
                         ▲                      ▲
                         │   互斥（启动阻止）   │
                         │                      │
            ┌────────────┴──────────────────────┴───────────────┐
            │                     C2ME                          │
            │  chunk-system / chunkio / lighting / scheduling / │
            │  dfc / natives-math / notickvd (MIT + ARR)        │
            └───────────────────────────────────────────────────┘
```

**核心定位**：SteadyChunks 不重复 FastNoise / Bye-Pregen 的算法优化，也不与 C2ME 共存；它聚焦于调度、资源治理、完成整形与诊断观测，把算法热路径让给已成熟的兼容模组。

## 2. 兼容矩阵

| 目标模组 | 关系 | 启动行为 | 模块重叠处理 |
|---|---|---|---|
| **C2ME** | 互斥 | 检测到即默认阻止启动；开发模式可仅启用分析器 | SteadyChunks 替代其 `chunk-system` / `scheduling` / `lighting-threading` / `chunkio` 调度链路；不复用其 ARR 段（`opts-accel-opencl`） |
| **FastNoise** | 兼容并让路 | 同装可用 | SteadyChunks 不注入 `NoiseChunkGenerator.populateNoise / populateBiomes` 与 `SurfaceBuilder.surfaceBuilder`；NOISE/BIOMES/SURFACE 阶段只做调度与资源预算 |
| **Bye-Pregen** | 兼容并让路 | 同装可用 | SteadyChunks 不注入 `PaletteedContainer` / `IOWorker` / `ChunkSerializer` / `LightEngine` 等被 Bye-Pregen 重写的类；序列化、光照算法、Palette 由 Bye-Pregen 负责；SteadyChunks 只做 I/O 队列背压与光照完成整形 |
| **FlowSched** | 可选直接复用 | N/A（库） | MIT 许可，可作 vendored 依赖；ADR-007 决定是直接复用还是 clean-room 轻量化实现 |
| **Embeddium / Sodium / Voxy / Sable** | 不冲突 | 同装可用 | SteadyChunks 不替换渲染器；仅通过可选客户端反馈协议协作 |
| **Lithium** | 不冲突 | 同装可用 | Bye-Pregen 已有 `compat.LithiumHashPaletteMixin`，SteadyChunks 不重复 |

## 3. 模块所有权表（与 FastNoise / Bye-Pregen 分工）

| 阶段 / 模块 | SteadyChunks | FastNoise | Bye-Pregen | C2ME（被替代） |
|---|---|---|---|---|
| 区块需求 / Ticket / 优先级 / 取消 | **主** | — | — | 部分 |
| 调度器（DAG + 多资源 + 完成优先） | **主** | — | — | 主（基于 FlowSched） |
| 自适应资源治理 / 紧急模式 | **主** | — | — | 弱 |
| FULL 整合队列与完成整形 | **主** | — | — | 弱 |
| 区块发送配额与玩家公平 | **主** | — | — | 弱 |
| 客户端反馈协议 | **主** | — | — | — |
| 诊断观测（Chunk Flight Recorder） | **主** | — | JFR 钩子 | 弱 |
| `populateNoise` / `populateBiomes` 算法 | — | **主** | — | 部分（DFC） |
| `SurfaceBuilder.surfaceBuilder` 算法 | — | **主** | — | — |
| Palette 紧凑存储 | — | 写入侧 | **主**（重写） | 部分 |
| Placement 优化 | — | — | **主**（10 个 mixin） | — |
| NBT / 序列化 / IO GC-free | 背压 + 调度 | — | **主** | 主（被 Bye-Pregen vendored） |
| 光照算法（YALight / 线程化） | 完成整形 | — | **主**（YALight） | 主（threading-lighting） |
| 结构选址 / Jigsaw / 引用优化 | **主** | — | — | 弱 |
| FEATURES 拆分与长尾治理 | **主** | — | — | 弱 |
| OpenCL / GPU 加速 | **禁止** | 有 | 兼容适配 | ARR 段，禁用 |
| Native 数学加速 | **禁止** | — | — | 主（natives-math） |

## 4. 关键约束（来自研究结论）

1. **许可证清洁**：只有 FlowSched（MIT）可直接复用。C2ME MIT 段可借鉴思路但需 clean-room；C2ME ARR 段、Bye-Pregen LGPL 段、FastNoise MPL 段严禁复制代码。
2. **优先兼容探测**：每个模块启动时探测 FastNoise / Bye-Pregen 模块状态，命中即让路，避免重复 Mixin。
3. **不写存档格式**：SteadyChunks 不修改 RegionFile 与序列化格式，避免与 Bye-Pregen 冲突；只做 I/O 队列调度。
4. **不替换渲染器**：客户端模块仅提供反馈与可选编译节流，与 Embeddium/Sodium 等保持兼容探测。
5. **C2ME 1.21.1 状态**：`backports/1.21.1` 分支活跃维护（LTS），Bye-Pregen vendored 0.4.0-alpha.0.116。SteadyChunks 必须假设用户会从 C2ME 1.21.1 迁移，启动时检测要可靠。

## 5. Phase 0 入口方向

基于上述定位，Phase 0 的工作要点：

- **0.1 冻结目标环境**：MC 1.21.1 / NeoForge 21.1.218 / Java 21 / FastNoise 1.21.x / Bye-Pregen 1.0.10.0 (NeoForge 21.1.233) / C2ME 0.4.0-alpha.0.116 (1.21.1 backport)；整合包基线暂用通用 NeoForge。
- **0.2 源码功能地图**：以本研究报告为起点，补充每个项目精确到提交哈希的注入点清单。
- **0.3 许可证策略**：SteadyChunks 主许可证确定为 **MIT**；审计结论见 `docs/source-audit.md`。
- **0.4 ADR 编写**：7 个 ADR 的初始依据已就绪，可直接起草。

## 6. 已确认的关键决策（2026-07-31）

下列决策已与用户对齐，作为 Phase 0 与 Phase 1 的输入：

1. **SteadyChunks 主许可证**：**MIT**。与 FlowSched 一致，最宽松；不可复制 LGPL/MPL/ARR 段代码，只允许 clean-room 或运行时依赖。
2. **目标整合包基线**：**暂用通用 NeoForge 1.21.1 + FastNoise + Bye-Pregen 最小兼容基线**。具体冒险整合包列表推迟到 Phase 2 性能门槛冻结前补充。
3. **FlowSched 使用方式**：**Phase 3 时再决定**。Phase 0/1/2 使用占位调度器接口；ADR-007 保留为开放决策，待 Phase 3 原型阶段补充数据后冻结。
4. **Gradle 工程结构**：**单模块 + 包边界**。按计划 §7 的包结构（`bootstrap/config/telemetry/scheduler/governor/worldgen/network/client/compat/api/command/mixin/test`）做逻辑隔离，不拆 Gradle 子工程。

## 7. Phase 0 启动状态

- 目标环境已部分冻结：MC 1.21.1 / NeoForge 21.1.218 / Java 21 / Parchment 2024.11.17 / Gradle 9.2.1。
- 国内 Gradle 镜像已配置（腾讯云发行版 + Aliyun Maven / Gradle Plugin / NeoForge / Parchment 仓库）。
- 示例代码已清理，主入口 `com.mochi_753.steadychunks.SteadyChunks` 就绪。
- 源码审计预备研究完成，本研究报告作为 `docs/source-audit.md` 的输入。
