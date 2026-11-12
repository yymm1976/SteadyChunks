# ADR-007: 是否采用自研调度器或第三方调度库

- **状态**：**Deferred（推迟到 Phase 3 决定）**
- **日期**：2026-07-31
- **决策者**：SteadyChunks 项目

## 上下文

SteadyChunks 的核心模块之一是区块任务调度器，需支持：

- DAG 依赖（ChunkStatus 链与邻区块依赖）
- 多资源同时锁定（CPU、IO、STRUCTURE_PLANNING、NOISE_HEAVY、FEATURES_WRITE、LIGHT、MAIN_THREAD_COMMIT、CHUNK_SEND、IO_READ、IO_WRITE）
- 优先级评分（距离、可见紧迫度、阶段进度、排队年龄、玩家公平性、维度公平性、运动方向、需求惩罚）
- 完成优先（优先推进近处半成品区块）
- 背压（队列 / ProtoChunk 数超过阈值时停止派发远处任务）
- 软取消（ADR-005）
- 公平性（多玩家、多维度配额）

可选项：

1. **直接 vendored FlowSched**（MIT，C2ME 已使用的调度库）
2. **clean-room 轻量化自研**
3. **基于 Java 标准库（`java.util.concurrent`）实现**
4. **第三方调度库（如 Akka、Project Reactor、RxJava）**

### FlowSched 评估

**优点**：

- MIT 许可，可直接 vendored 或依赖；
- C2ME 已在生产环境验证其多资源锁定设计；
- 优先级 + 多资源同时锁定正好满足 SteadyChunks 需求；
- 纯 Java 17 库，依赖少（fastutil、slf4j、rxjava）；
- 子模块成熟度高（C2ME 持续维护）。

**缺点**：

- 无独立 release，作为 C2ME 子模块演进，版本管理需自行处理；
- 依赖 rxjava 3.1.12，引入额外运行时依赖；
- 通用调度器设计，可能包含 SteadyChunks 不需要的复杂度；
- FlowSched 解决"多资源锁定"，但 SteadyChunks 还需要"完成优先 + 背压 + 公平性"等上层策略，这些需自行实现；
- 直接使用会让 SteadyChunks 与 FlowSched 的演进耦合。

### Clean-room 轻量化评估

**优点**：

- 完全自主可控，可针对区块调度场景定制；
- 无外部依赖，减小发布物体积；
- 可与 SteadyChunks 的诊断模块深度集成；
- 可针对 NeoForge 1.21.1 的具体类结构优化。

**缺点**：

- 多资源锁定的正确性验证复杂（死锁、优先级反转、饥饿）；
- 工程量可观，可能延迟 Phase 3 交付；
- 失去 FlowSched 在 C2ME 生产环境的验证收益。

### Java 标准库评估

**优点**：

- 零依赖；
- API 稳定。

**缺点**：

- `PriorityBlockingQueue` + `Semaphore` 组合难以表达多资源同时锁定；
- 容易引入死锁与竞争；
- 不适合 DAG 调度场景。

### 第三方调度库评估

**Akka**：过重，actor 模型与区块调度场景不匹配。
**Project Reactor / RxJava**：FlowSched 已基于 RxJava，直接用 Reactor 等于自研 FlowSched 上层。
**Quartz / ScheduledExecutorService**：仅支持时间调度，不支持 DAG。

## 决策

**推迟到 Phase 3 决定**。Phase 0/1/2 使用占位调度器接口，不绑定具体实现。

### 7.1 Phase 0/1/2 行为

- 定义调度器接口 `ChunkTaskScheduler`，仅声明 SteadyChunks 需要的能力；
- 实现一个最小化的 `NaiveScheduler`（基于 `PriorityBlockingQueue` + `Semaphore`），仅用于 Phase 2 诊断观测；
- `NaiveScheduler` 不做背压、不做多资源锁定、不做完成优先，只是"原版调度的薄包装 + 诊断钩子"；
- Phase 2 诊断结果将用于评估真实调度瓶颈，作为 Phase 3 决策依据。

### 7.2 Phase 3 决策依据

Phase 3 启动时，基于以下数据决策：

1. **真实瓶颈**：Phase 2 Chunk Flight Recorder 数据显示卡顿来自调度竞争、完成洪峰、还是其他环节？
2. **资源锁定复杂度**：SteadyChunks 实际需要的资源令牌数与互斥关系复杂度（计划 §3.2）；
3. **FlowSched 适配性**：FlowSched 的多资源锁定模型是否覆盖 SteadyChunks 的所有资源类型；
4. **依赖预算**：是否可接受 rxjava 3.1.12 作为运行时依赖；
5. **工程预算**：自研调度器的工程时间是否在 Phase 3 范围内；
6. **C2ME 对照**：FlowSched 在 C2ME 1.21.1 backport 的实际表现（通过对照基准采集）。

### 7.3 决策路径

- 若 Phase 2 数据显示调度竞争是主要瓶颈，且 FlowSched 适配性良好 → **直接 vendored FlowSched**；
- 若 FlowSched 适配性不足（如缺公平性、缺完成优先） → **clean-room 轻量化自研**，借鉴 FlowSched 多资源锁定思路；
- 若 Phase 2 数据显示调度竞争不是主要瓶颈（卡顿来自客户端或完成洪峰） → **保留 `NaiveScheduler`**，Phase 3 专注完成整形与发送配额。

### 7.4 接口稳定性

- `ChunkTaskScheduler` 接口在 Phase 0/1/2 标记 `@ExperimentalApi`；
- Phase 3 决策后冻结接口；
- 1.0 后通过 semver 维护向后兼容。

## 后果

### 正面

- Phase 0/1/2 不被调度器选型阻塞；
- Phase 3 决策基于真实数据，而非过早推测；
- 保留三种路径的灵活性；
- 接口先行，实现后置，符合"先测量后替换"原则（计划 §5.1）。

### 负面

- Phase 3 决策点之前，`NaiveScheduler` 性能可能不如 C2ME，Phase 2 基准数据可能偏弱；
- 接口可能在 Phase 3 决策时调整，引入返工；
- 文档与 ADR 需在 Phase 3 决策时更新本 ADR 状态。

### Phase 3 时本 ADR 处理

- Phase 3 决策后，本 ADR 状态从 `Deferred` 改为 `Accepted` 或 `Superseded`；
- 若决策为"直接 vendored FlowSched"，新增 ADR-007a 记录决策细节；
- 若决策为"clean-room 自研"，本 ADR 改为 `Accepted` 并补充决策依据。

## 备选方案

### A. Phase 0 即决定直接 vendored FlowSched

被否决：
- Phase 2 数据未采集，无法判断 FlowSched 适配性；
- 可能引入不必要的 rxjava 依赖；
- 违反"先测量后替换"原则。

### B. Phase 0 即决定 clean-room 自研

被否决：
- 工程量预估需基于 Phase 2 真实瓶颈；
- 可能重复 FlowSched 已解决的问题；
- 违反"先测量后替换"原则。

### C. Phase 0/1/2 不实现调度器，仅做诊断

被否决：
- Phase 3 需要可运行的调度器原型作为对照；
- `NaiveScheduler` 工程量小，可作为占位实现。
