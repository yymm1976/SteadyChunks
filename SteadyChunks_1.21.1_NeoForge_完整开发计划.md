# SteadyChunks（暂定名）开发计划

> **目标平台：** Minecraft Java Edition 1.21.1 + NeoForge + Java 21  
> **项目定位：** 以“跑图过程的帧时间与 MSPT 稳定性”为第一目标，逐步替代 C2ME 的区块生成、调度、加载与完成链路；与 FastNoise、Bye-Pregen 兼容并避免重复实现其优势模块。  
> **文档状态：** 初始完整路线图  
> **更新日期：** 2026-07-31

---

## 1. 项目摘要

SteadyChunks 不是以“预生成每秒区块数最大化”为唯一目标的多线程世界生成模组，而是一个覆盖区块完整生命周期的性能与服务质量（QoS）系统：

```text
区块需求
→ 磁盘读取或创建 ProtoChunk
→ ChunkStatus / ChunkStep 依赖调度
→ 结构规划
→ 生物群系、噪声、地表、雕刻
→ FEATURES 中的结构与特征放置
→ 光照
→ FULL 转换与主线程整合
→ 区块发送
→ 客户端接收与渲染准备
```

项目最终应满足以下定位：

1. **在 NeoForge 1.21.1 环境中替代 C2ME，而不是与 C2ME 共存。**
2. **与 FastNoise 和 Bye-Pregen 同时安装时保持兼容。**
3. **不重复 FastNoise 的噪声、群系、地表和 palette 热路径优化。**
4. **不重复 Bye-Pregen 已启用的 palette、序列化、后处理、特征、光照等模块。**
5. **默认优先降低跑图时的 P95/P99 帧时间、P95/P99 MSPT、CPU 峰值、GC 峰值和任务完成洪峰。**
6. **保持世界生成正确性、存档安全和模组兼容性；任何高风险优化均可单独关闭。**
7. **支持单人集成服务器和独立服务器两种运行模型，并采用不同的默认资源策略。**

---

## 2. 核心问题定义

### 2.1 需要解决的问题

高速跑图时，卡顿可能来自多个环节同时形成洪峰：

- 世界生成工作线程占用过多 CPU 核心；
- 工作线程与渲染线程、客户端主线程、服务端主线程争抢调度时间；
- 噪声、结构或 FEATURES 阶段产生大量短生命周期对象，引发 GC；
- 生成任务波前过宽，大量 ProtoChunk 和 Future 同时驻留；
- 多个区块在短时间内一起达到 FULL，集中回到主线程整合；
- 光照任务、区块序列化和网络发送集中完成；
- 客户端一次收到大量新区块，集中进行光照应用、Section 编译和 GPU 上传；
- 复杂 Jigsaw 结构或大型模板造成极端长尾；
- 模组世界生成代码在并发环境中存在隐含线程安全问题；
- 玩家改变方向后，旧方向的高成本生成任务仍继续推进。

### 2.2 项目优化目标排序

优先级从高到低：

1. **跑图过程的客户端 P99 帧时间**
2. **集成服务器或独立服务器的 P99 MSPT**
3. **超过 100 ms、250 ms、500 ms 的卡顿事件数量**
4. **CPU 峰值与渲染线程饥饿**
5. **FULL 完成、光照和区块发送的洪峰**
6. **同时驻留的 ProtoChunk、Future 和生成上下文数量**
7. **GC 分配率与暂停时间**
8. **可见区块从请求到显示的延迟**
9. **平均区块生成吞吐量**
10. **纯预生成吞吐量**

项目允许为了显著改善 P99 稳定性，牺牲少量最大吞吐量；但不得造成玩家前方长期空洞或明显低于原版的可见区块完成速度。

---

## 3. 非目标

以下内容不属于首个完整版本的默认目标：

- 与 C2ME 同时运行；
- 替换整个 Minecraft 渲染器；
- 替换 FastNoise 的 `BIOMES`、`NOISE`、`SURFACE` 数据写入优化；
- 在 Bye-Pregen 已启用时重复其 GC-free 序列化、palette、后处理或光照实现；
- 让任意模组的所有世界生成代码自动并行；
- 在没有线程安全证明的情况下并行结构 Piece 放置；
- 跨 Tick 暂停并恢复任意原版结构或特征生成函数；
- 修改世界种子、结构位置、战利品、方块状态或生成概率；
- 为追求跑分而默认使用全部逻辑处理器；
- 自动上传性能数据；
- 对存档格式进行不可逆修改。

---

## 4. 区块生成拆分与模块边界

| 环节 | 主要工作 | 典型风险 | SteadyChunks 责任 | 与现有模组关系 |
|---|---|---|---|---|
| 区块需求与 Ticket | 决定目标状态、距离和优先级 | 任务波前过宽、过期需求 | 需求追踪、优先级、取消与公平性 | 核心新方向 |
| 磁盘加载 | RegionFile 读取、解压、反序列化 | I/O 队列洪峰、主线程等待 | 先测量，后做有界 I/O 调度 | Bye-Pregen 模块存在时让其处理序列化热路径 |
| `STRUCTURE_STARTS` | 结构选址、Jigsaw 展开、Piece 规划 | 极端长尾、对象分配、模组线程安全 | 专项分析、缓存和算法优化 | 核心新方向 |
| `STRUCTURE_REFERENCES` | 跨区块结构引用 | 大结构扫描、重复查找 | 索引与边界优化 | 核心新方向 |
| `BIOMES` | 生物群系采样与填充 | 稳定 CPU 负载 | 仅调度，不重复内部优化 | FastNoise 优先 |
| `NOISE` | 密度函数、Aquifer、方块写入 | 高 CPU、内存带宽、分配 | 资源预算和调度 | FastNoise / Bye-Pregen 优先 |
| `SURFACE` | Surface Rules 与地表写入 | 分支、palette 写入 | 资源预算和调度 | FastNoise / Bye-Pregen 优先 |
| `CARVERS` | 洞穴、峡谷、mask | 长尾、重复扫描 | 计时与有界调度；数据证明后再优化 | 可做独立模块 |
| `FEATURES` | 特征与结构实际放置 | 冒险包最大长尾、跨区块写入 | 深度分析与结构/特征专项优化 | 核心新方向；避免与 Bye-Pregen 重叠 |
| `INITIALIZE_LIGHT` / `LIGHT` | 光照初始化与传播 | 完成洪峰、边界传播 | 调度与完成平滑；算法替换可选 | Bye-Pregen 光照模块存在时让路 |
| `SPAWN` | 初始生物生成 | 模组初始化成本 | 先测量 | 低优先级 |
| `FULL` | ProtoChunk 转换、POI、方块实体、事件 | 主线程集中整合 | 每 Tick 预算与完成队列 | 核心新方向 |
| 区块发送 | 序列化、压缩、网络发送 | 客户端接收洪峰 | 发送配额与玩家公平性 | 核心新方向 |
| 客户端准备 | 应用区块、光照、Section 编译、上传 | 帧时间尖峰 | 诊断、反馈与可选节流 | 不替换渲染器 |

---

## 5. 总体技术原则

### 5.1 先测量后替换

所有优化必须经历以下状态：

```text
仅观测
→ 实验开关
→ 默认关闭
→ 通过基准与正确性门槛
→ 默认开启
```

不得因为理论上“应该更快”就直接进入默认配置。

### 5.2 有界并发而非无限并发

需要同时限制：

- 工作线程数量；
- 每个 ChunkStatus 的运行任务数；
- 已提交但未完成的 Future 数；
- 等待邻区块依赖的任务数；
- 每个维度的任务数；
- 每个玩家产生的任务数；
- 每 Tick 进入 FULL 整合的区块数；
- 每个时间窗口发送给客户端的区块数。

### 5.3 完成优先

相比不断让新区块进入早期阶段，应优先完成距离玩家较近、已经接近 FULL 的区块，以降低半成品波前、内存压力和完成延迟。

### 5.4 安全阶段分类

所有阶段按线程安全能力分级：

- **A：** 已知可并行，且不进行共享可变世界写入；
- **B：** 在同一 Chunk 或受控邻域内可并行；
- **C：** 依赖模组实现，需兼容白名单或运行时降级；
- **D：** 必须串行或在原版指定线程执行。

未经证明的模组世界生成代码默认按 C 或 D 处理。

### 5.5 模块化与失败关闭

每个模块必须具备：

- 独立配置开关；
- 独立 Mixin Gate；
- 启动时兼容性检查；
- 注入失败时明确日志；
- 安全回退路径；
- 独立性能计数；
- 独立正确性测试。

当无法确认兼容性时，必须关闭优化而不是猜测。

---

## 6. 项目级验收指标

所有比例指标均以相同硬件、JVM、整合包、世界种子、移动轨迹和视距下的对照组为基础。最终阈值应在 Phase 2 完成后根据基准噪声校准。

### 6.1 必须满足的正确性门槛

- 无已知区块损坏、丢失、永久卡在 ProtoChunk 或错误状态；
- 无可复现死锁、依赖环或无法完成的 Chunk Future；
- 无结构重复、结构缺失、Piece 截断或引用丢失；
- 无因任务取消造成的部分结构、部分光照或部分后处理；
- 区块保存后重启可正常加载；
- 数据包重载、维度卸载和服务器关闭不会残留任务或缓存；
- FastNoise 与 Bye-Pregen 各自单独、共同安装时均可启动并完成基准；
- 检测到 C2ME 时明确阻止启动或关闭 SteadyChunks 的冲突模块；
- 24 小时持续跑图压力测试无死锁、崩溃、内存持续增长或存档损坏。

### 6.2 建议的 1.0 性能门槛

在目标冒险整合包的单人集成服务器场景中：

- 相比 C2ME 对照组，P99 客户端帧时间改善目标不少于 25%；
- 超过 250 ms 的帧时间事件减少目标不少于 50%；
- P99 MSPT 改善目标不少于 20%，或至少不劣于无 C2ME 基线；
- 世界生成引起的 CPU 峰值可被配置限制，且不长期占满全部逻辑处理器；
- 可见前沿区块完成延迟不得比 C2ME 对照组恶化超过 15%，除非启用“极致平滑”预设；
- 同时驻留 ProtoChunk 峰值降低目标不少于 20%；
- 世界生成相关对象分配率不得高于无本模组基线；
- 诊断关闭时运行开销低于 1%，基础计数开启时低于 3%。

在独立服务器场景中：

- P99 MSPT 不劣于 C2ME 对照组；
- 多玩家分散探索时不存在单玩家长期饥饿；
- 区块发送速率平滑，不产生周期性网络与客户端构建洪峰；
- 平均区块生成吞吐量至少达到无 C2ME 基线的 1.5 倍，或由测试证明稳定性收益足以补偿较低吞吐量。

这些数值是项目门槛建议，不是预先保证的结果。Phase 2 后应冻结正式门槛。

---

# Phase 0：项目定义、源码审计与法律边界

## 目标

建立准确的竞争基线、许可证边界、模块重叠表和性能契约，避免后续开发因错误假设返工。

## 具体步骤

### 0.1 冻结首个目标环境

记录并锁定：

- Minecraft 1.21.1；
- NeoForge 目标版本范围；
- Java 21；
- FastNoise 目标兼容版本范围；
- Bye-Pregen 目标兼容版本范围；
- C2ME 1.21.1 对照版本；
- 目标整合包的世界生成、结构、群系和渲染模组列表；
- 默认 JVM 参数、内存和视距。

### 0.2 建立源码功能地图

对以下项目做“功能—注入点—许可证—重叠”清单：

- C2ME 1.21.1 分支及其调度组件；
- FlowSched；
- FastNoise；
- Bye-Pregen；
- NeoForge 1.21.1 区块与世界生成实现；
- Minecraft 客户端区块接收与渲染调度；
- 目标结构模组。

清单必须至少包含：

```text
模块名称
修改类/方法
优化目标
是否改变线程模型
是否改变世界生成顺序
是否写入存档
是否与 FastNoise 冲突
是否与 Bye-Pregen 冲突
许可证
可否直接复用
可否仅借鉴思路
需要 clean-room 重写的部分
```

### 0.3 确定许可证策略

- 在正式复制或改写任何代码前完成许可证审计；
- 若只做兼容，不把“兼容”误认为“可以复制源码”；
- 对参考实现保存来源、提交哈希和修改说明；
- 对无法确认授权的实现采用 clean-room 设计记录；
- 将第三方通知、许可证文本和来源清单纳入发布物；
- 项目主许可证在审计完成后确定。

### 0.4 编写架构决策记录（ADR）

至少建立：

- ADR-001：为什么以 P99 稳定性而非最大 CPS 为第一指标；
- ADR-002：为何与 C2ME互斥；
- ADR-003：FastNoise 与 Bye-Pregen 的功能所有权；
- ADR-004：严格正确性与世界生成顺序政策；
- ADR-005：任务取消的安全边界；
- ADR-006：集成服务器与独立服务器的资源模型；
- ADR-007：是否采用自研调度器或第三方调度库。

## 交付物

- `docs/source-audit.md`
- `docs/compatibility-ownership.md`
- `docs/adr/`
- 冻结的基准整合包清单；
- 初始风险登记册；
- 项目许可证决策或明确的待决项。

## 验收标准

- 每个计划修改的原版热点都有源码入口；
- FastNoise、Bye-Pregen 和 SteadyChunks 的功能所有权没有未解释重叠；
- 所有可能复制的实现均有许可证结论；
- C2ME 对照版本可稳定运行并可重复采集基准；
- 未完成此 Phase 前不得开始大规模源码移植。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 误判 C2ME 功能范围 | 以指定 1.21.1 提交为准，不以旧文章或模组简介为准 |
| 许可证污染 | 先记录设计需求，再独立实现；每个借鉴点保留来源 |
| 目标版本漂移 | 首个大版本锁定 1.21.1，后续版本单独分支 |
| 过早承诺性能数字 | Phase 2 前只使用暂定目标 |

---

# Phase 1：工程骨架与可重复测试平台

## 目标

创建可以稳定构建、启动、测试、录制和复现跑图场景的开发环境。

## 具体步骤

### 1.1 建立 NeoForge 工程

建议模块：

```text
steadychunks/
├─ common-core
├─ neoforge
├─ telemetry
├─ scheduler
├─ worldgen-structure
├─ worldgen-completion
├─ chunk-send
├─ client-feedback
├─ compat-fastnoise
├─ compat-byepregen
├─ testkit
└─ benchmark
```

若首期不使用多模块 Gradle，也要在包结构中保持同样边界。

### 1.2 建立运行配置

至少提供：

- `runClient`
- `runServer`
- `runGameTestServer`
- `runBenchmarkIntegrated`
- `runBenchmarkDedicated`
- `runWorldgenParity`
- `runStructureStress`
- `runLongSoak`

所有基准运行必须支持自动：

- 创建或清理测试世界；
- 使用固定种子；
- 注入固定玩家移动轨迹；
- 记录 JFR；
- 输出 SteadyChunks 指标；
- 保存游戏日志和崩溃报告；
- 记录模组列表、配置哈希、Git 提交和硬件信息。

### 1.3 建立基准场景

至少准备：

1. 原版地形直线飞行；
2. 高速连续转向；
3. 两名玩家相反方向探索；
4. 大型 Jigsaw 结构密集区域；
5. 大型模板结构跨多个区块；
6. FEATURES 丰富的群系；
7. 高频光照变化的地下与地表混合地形；
8. 快速跨维度；
9. 飞艇或高速载具巡航；
10. 持续跑图后返回旧区块，触发保存、卸载与重新加载。

### 1.4 建立对照组合

```text
A. NeoForge 基线
B. NeoForge + FastNoise
C. NeoForge + Bye-Pregen
D. NeoForge + FastNoise + Bye-Pregen
E. C2ME + FastNoise + Bye-Pregen
F. SteadyChunks + FastNoise + Bye-Pregen
```

如果某组合官方明确不兼容，则记录原因并调整矩阵，而不是强行运行。

### 1.5 CI 与质量门槛

CI 至少执行：

- 编译；
- Checkstyle 或 Spotless；
- 静态分析；
- 单元测试；
- GameTest；
- Mixin 配置验证；
- 启动烟雾测试；
- 最小 FastNoise 兼容启动；
- 最小 Bye-Pregen 兼容启动；
- 生成发布物和符号文件。

## 交付物

- 可构建工程；
- 可重复基准脚本；
- 基准世界与移动轨迹定义；
- CI；
- 自动生成的运行元数据文件。

## 验收标准

- 新机器按 README 操作可完成构建与所有运行配置；
- 同一环境连续运行同一基准的主要指标波动处于可接受范围；
- 测试世界、配置和 JFR 自动归档；
- 没有依赖人工点击完成的核心基准流程。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 基准不可重复 | 固定世界种子、轨迹、视距、模组版本和 JVM 参数 |
| JIT 与缓存污染结果 | 独立预热阶段，正式运行使用新世界并重复多次 |
| 单次跑分误导 | 每个组合至少多次运行，报告中位数和置信区间 |
| 测试只覆盖预生成 | 强制包含真实客户端渲染和输入场景 |

---

# Phase 2：端到端 Chunk Flight Recorder

## 目标

在不改变区块生成行为的前提下，完整记录“需求—生成—整合—发送—客户端显示”的时间线，并找到真实长尾。

## 具体步骤

### 2.1 区块任务身份与生命周期

为每个区块任务维护轻量标识：

```text
维度
ChunkPos
目标状态
需求来源
创建时间
最近 Ticket 时间
当前阶段
阶段入队时间
阶段开始时间
阶段完成时间
是否仍被玩家需要
是否被取消或降级
```

### 2.2 ChunkStatus / ChunkStep 指标

逐阶段采集：

- 排队时间；
- 执行时间；
- Future 等待时间；
- 邻区块依赖等待；
- 同阶段在途数量；
- 完成数量；
- 异常数量；
- 取消数量；
- P50/P90/P95/P99/最大值。

必须单独统计：

```text
EMPTY
STRUCTURE_STARTS
STRUCTURE_REFERENCES
BIOMES
NOISE
SURFACE
CARVERS
FEATURES
INITIALIZE_LIGHT
LIGHT
SPAWN
FULL
```

### 2.3 结构规划指标

按结构 registry key 和 modid 统计：

- 候选检查次数；
- 成功起点数；
- 失败原因；
- Jigsaw 展开节点数；
- Piece 数；
- 最大递归或扩展深度；
- 模板查找次数；
- 高度查询次数；
- 碰撞判断次数；
- 分配量近似值；
- 单次最大耗时。

### 2.4 FEATURES 细分

将 `FEATURES` 拆分为：

- 结构 Piece 放置；
- 普通 PlacedFeature；
- 模板读取；
- StructureProcessor；
- 方块实体与 NBT；
- 后处理记录；
- 跨区块读取；
- 跨区块写入；
- 每个 registry key；
- 每个 modid。

### 2.5 FULL、发送与客户端指标

服务端记录：

- 达到 FULL；
- 进入主线程整合队列；
- 开始整合；
- 完成整合；
- 进入发送队列；
- 数据包构建；
- 压缩；
- 实际发送。

客户端记录：

- 收到区块批次；
- 应用区块数据；
- 应用光照；
- 标记 Section 重建；
- 开始编译；
- 完成编译；
- 上传完成；
- 首次可见帧。

### 2.6 线程与系统资源

同步采集：

- 进程 CPU；
- 每类线程 CPU 时间；
- 服务端主线程 MSPT；
- 客户端帧时间；
- 渲染线程长时间未运行事件；
- 堆占用；
- 对象分配率；
- GC 次数与暂停；
- 工作队列深度；
- 同时驻留 ProtoChunk 数；
- 区块发送队列深度。

### 2.7 用户接口

命令建议：

```text
/steadychunks status
/steadychunks profile start
/steadychunks profile stop
/steadychunks stages
/steadychunks structures
/steadychunks features
/steadychunks queues
/steadychunks spikes
/steadychunks export
```

导出格式：

- 人类可读 Markdown 报告；
- JSON 原始数据；
- CSV 阶段数据；
- JFR；
- 配置与模组列表快照。

## 交付物

- 完整观测模块；
- 可视化或报告生成器；
- 首份目标整合包基准报告；
- 正式性能门槛草案。

## 验收标准

- 可从一次卡顿事件追溯到具体阶段、结构或发送批次；
- 能区分 CPU 饱和、GC、主线程整合、网络发送和客户端编译；
- 诊断关闭时开销低于 1%；
- 基础计数开启时开销低于 3%；
- 高精度分析模式开销被明确标记，不作为日常默认；
- 不改变区块生成输出和任务顺序。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 观测本身制造卡顿 | 使用环形缓冲、采样、LongAdder 或线程本地聚合 |
| 事件量过大 | 默认只保留聚合数据，尖峰前后才保留完整事件 |
| 结构调用栈采集昂贵 | 使用调用栈指纹和按需模式 |
| 客户端与服务端时钟不一致 | 同进程使用单调时钟；远程连接使用批次 ID 与相对时间 |

---

# Phase 3：固定预算的区块调度器原型

## 目标

在不做自适应算法和阶段内部优化的情况下，证明“有界并发、背压和完成优先”能够改善跑图稳定性。

## 具体步骤

### 3.1 建立任务模型

任务至少包含：

```text
ChunkPos
维度
目标 ChunkStatus
当前 ChunkStatus
依赖集合
需求玩家集合
距离
运动方向匹配度
排队年龄
完成进度
安全等级
取消状态
```

### 3.2 建立资源类

调度器不能只限制线程数，还要为阶段建立资源令牌：

```text
CPU_GENERAL
STRUCTURE_PLANNING
NOISE_HEAVY
FEATURES_WRITE
LIGHT
MAIN_THREAD_COMMIT
CHUNK_SEND
IO_READ
IO_WRITE
```

一个任务可以同时请求多个资源，必须固定资源获取顺序，避免死锁。

### 3.3 固定并发上限

第一版只支持配置固定值：

```toml
[scheduler]
worker_threads = 4
max_inflight_total = 64
reserve_logical_processors = 2

[stage_limits]
structure_starts = 2
biomes = 3
noise = 3
surface = 2
carvers = 2
features = 1
light = 2
```

默认值需根据硬件分类生成，但不得使用全部逻辑处理器。

### 3.4 背压

当队列或 ProtoChunk 数超过阈值：

- 不继续启动远处早期阶段；
- 优先推进近处半成品区块；
- 降低非玩家直接需求的任务优先级；
- 阻止预取或预测任务扩张；
- 保留服务器关键任务的 CPU 余量。

### 3.5 完成优先评分

初始评分建议：

```text
priority =
  距离权重
+ 可见范围紧迫度
+ 当前阶段进度
+ 排队年龄
+ 玩家公平性
+ 维度公平性
+ 运动方向预测
- 已失去需求惩罚
```

评分必须防止：

- 远处任务永久饥饿；
- 多玩家时单个高速玩家占满全部资源；
- 新维度任务被旧维度积压压制；
- 已到后期但不再需要的任务无限占优。

### 3.6 软取消

仅允许：

- 从等待队列移除；
- 阻止尚未开始的阶段；
- 阻止任务进入下一个高成本阶段；
- 释放未使用资源令牌。

禁止：

- 中断正在写区块的阶段；
- 中断 FEATURES 中的结构或特征放置；
- 中断光照传播；
- 中断保存；
- 取消仍被其他区块依赖的共享任务。

## 交付物

- 固定预算调度器；
- 阶段令牌系统；
- 优先队列；
- 软取消；
- 对照基准。

## 验收标准

- 无死锁、优先级反转和永久饥饿；
- 所有 permit 在正常、异常和取消路径均能释放；
- P99 帧时间或 P99 MSPT 至少一项显著改善；
- 同时驻留 ProtoChunk 峰值下降；
- 可见前沿延迟不超过暂定上限；
- 禁用调度器后行为恢复到原版路径。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 依赖任务被限流导致死锁 | 依赖解锁任务拥有保底优先级和保留 permit |
| 完成优先造成远处饥饿 | 年龄提升和每玩家/维度配额 |
| 取消判断错误 | 只做软取消；依赖引用计数必须可验证 |
| 队列锁竞争 | 分片队列、低频重排、不可变评分输入快照 |
| 与原版任务系统重复调度 | 明确唯一所有者，禁止两套执行器同时推进同一任务 |

---

# Phase 4：自适应资源治理与线程隔离

## 目标

根据实时游戏状态自动调节世界生成负载，为渲染、输入、服务端 Tick、GC 和网络保留资源。

## 具体步骤

### 4.1 区分运行模式

#### 集成服务器模式

优先保护：

- Render Thread；
- Client Main Thread；
- Server Thread；
- 音频与输入响应；
- GC；
- 区块网格编译线程。

#### 独立服务器模式

优先保护：

- Server Thread；
- 网络线程；
- GC；
- I/O；
- 其他玩家的活动区块。

### 4.2 控制器输入

采用滑动窗口，不直接对单次样本反应：

- P95/P99 MSPT；
- P95/P99 帧时间；
- 长帧事件率；
- 进程 CPU；
- 世界生成线程 CPU；
- 堆压力；
- 最近 GC 暂停；
- FULL 等待队列；
- 可见区块缺口；
- 区块发送队列；
- 客户端 Section 编译队列。

### 4.3 首先实现 AIMD

规则示例：

```text
健康窗口持续存在：
  缓慢增加一个阶段 permit 或提高小幅预算

出现 CPU、MSPT、帧时间或 GC 超标：
  立即减少高成本阶段 permit
  暂停远处早期阶段
  保留近处完成任务
```

PID 或更复杂控制器仅在 AIMD 基准不足时进入实验模块。

### 4.4 紧急平滑模式

触发条件示例：

- 连续出现长帧；
- MSPT 超过硬上限；
- GC 暂停；
- 客户端编译队列暴涨；
- 区块发送队列暴涨。

响应：

- 暂停启动新的 NOISE / FEATURES；
- 仅允许依赖解锁和近处完成任务；
- 降低发送速率；
- 限制 FULL 整合数量；
- 在恢复窗口内逐步提升，而不是立即回满负载。

### 4.5 防振荡

- 控制周期不得短于任务典型完成时间；
- 增加与减少采用不同阈值；
- 使用冷却时间；
- 所有控制变化写入日志和性能报告；
- 对每个阶段设置最小和最大 permit。

## 交付物

- 资源控制器；
- 集成服务器与独立服务器预设；
- 紧急模式；
- 控制器调试图表。

## 验收标准

- 控制器不会在两个并发值之间频繁振荡；
- 渲染长帧发生后可观测到世界生成负载下降；
- 恢复过程平滑；
- 低核心 CPU 上不会误留过多资源导致无法跑图；
- 高核心 CPU 上不会默认占满全部逻辑处理器；
- 关闭自适应后仍可使用固定配置。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 反馈滞后 | 使用阶段队列和客户端队列作为前置信号 |
| 操作系统 CPU 指标不稳定 | 多指标联合，不使用单一 CPU 百分比 |
| 控制器吞吐量过低 | 设置可见缺口保底和最小并发 |
| 客户端数据不可用 | 退化为服务端本地指标模式 |

---

# Phase 5：FULL 整合、发送与客户端负载平滑

## 目标

消除“工作线程并行完成，但主线程和客户端集中卡顿”的完成洪峰。

## 具体步骤

### 5.1 FULL 整合队列

将可安全延迟的主线程整合工作放入有界队列：

- 每 Tick 最大整合区块数；
- 每 Tick 最大预计成本；
- 近处和即将可见区块优先；
- 依赖其他任务的区块保底；
- 多玩家公平。

必须区分：

- 可以推迟一个或数个 Tick 的整合；
- 必须立即执行以解除依赖的整合；
- 原版线程约束要求立即执行的动作。

### 5.2 完成批次整形

避免多个阶段在同一时间窗口全部释放结果：

- 对 `LIGHT → FULL` 建立完成预算；
- 对大量 Future 的回调进行分批排放；
- 禁止在单一 Tick 无上限执行完成回调；
- 记录回调积压和最长等待时间。

### 5.3 区块发送配额

按玩家和时间窗口限制：

- 新区块数据包数；
- 总字节数；
- 光照数据量；
- 高优先级近处区块；
- 重发或更新区块。

发送策略不得破坏协议顺序或导致玩家等待已经生成好的最近区块。

### 5.4 客户端反馈

客户端只提供：

- 帧时间；
- Section 编译队列；
- 最近接收区块数；
- 区块应用耗时；
- 可见缺口。

服务端据此调整发送和生成预算。远程服务器必须允许关闭或忽略客户端反馈。

### 5.5 可选客户端编译治理

仅在不与 Embeddium 等渲染优化模组冲突的前提下：

- 限制同一帧提交的区块重建数；
- 近处和屏幕内 Section 优先；
- 禁止替换完整渲染器；
- 检测到不支持的渲染器时仅保留诊断。

## 交付物

- FULL 整合预算；
- 完成回调整形；
- 区块发送调速；
- 客户端反馈协议；
- 可选客户端构建节流。

## 验收标准

- FULL 完成洪峰显著下降；
- 区块发送不再形成周期性大批次；
- 客户端 P99 帧时间改善；
- 不产生网络超时、缺块或顺序错误；
- 近处区块不被远处积压压制；
- 独立服务器不要求客户端安装模组时仍能安全运行基础功能。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 延迟整合阻塞依赖 | 依赖解锁任务保留独立预算 |
| 发送过慢造成缺块 | 以可见缺口和移动速度动态设下限 |
| 客户端协议兼容 | 使用可选自定义握手；无握手时只做服务器端调速 |
| 与渲染优化模组冲突 | 兼容探测和模块关闭，不注入其私有实现 |

---

# Phase 6：结构规划与引用优化

## 目标

降低 `STRUCTURE_STARTS` 和 `STRUCTURE_REFERENCES` 的平均成本、对象分配和 P99 长尾，同时保持结构位置与 Piece 结果正确。

## 具体步骤

### 6.1 结构热点排名

使用 Phase 2 数据生成：

```text
结构 registry key
所属 modid
候选次数
成功次数
总耗时
P95/P99/最大耗时
平均 Piece 数
最大 Piece 数
模板查找数
高度查询数
碰撞判断数
```

仅优先优化占总成本或长尾显著的结构。

### 6.2 结构选址快速否决

研究并缓存安全的只读判断：

- StructureSet / placement 候选；
- 生物群系允许性；
- 已计算的区块级高度摘要；
- 结构配置与 registry 查找；
- 不依赖随机顺序的固定参数。

缓存键必须包含：

- 世界种子相关上下文；
- 维度；
- registry / datapack generation；
- ChunkPos；
- 结构或 StructureSet 标识。

数据包重载和世界卸载必须清空。

### 6.3 Jigsaw 展开优化

候选方向：

- 模板与连接点元数据缓存；
- 避免重复 registry 和模板管理器查找；
- 使用更紧凑的候选列表；
- 包围盒快速排除后再进行昂贵形状判断；
- 减少临时集合、迭代器和包装对象；
- 缓存只读 VoxelShape 或包围盒元数据；
- 限制重复构建等价碰撞结构；
- 对异常 Piece 数和展开深度提供诊断阈值。

不得缓存：

- 依赖随机数当前位置的最终 Piece 选择；
- 依赖动态 Processor 或 datapack 状态的最终方块结果；
- 跨世界共享的可变结构对象。

### 6.4 结构引用索引

分析 `STRUCTURE_REFERENCES`：

- 起点搜索半径；
- 大包围盒导致的扫描；
- 重复读取起点；
- 同一 ChunkStatus 内的重复引用判断。

可实现：

- 当前生成批次内的结构起点空间索引；
- 包围盒与 Chunk 区间预计算；
- 去重；
- 对异常大边界结构输出警告。

### 6.5 世界生成期间同步区块请求审计

检测结构或模组代码在生成线程中调用同步区块加载：

- 当前生成区块；
- 被请求区块；
- 目标 ChunkStatus；
- 调用方 modid；
- 是否等待；
- 嵌套深度；
- 是否形成依赖环。

默认先报告，不自动改变模组行为。经过验证后再提供适配修复。

## 交付物

- 结构规划分析器；
- 安全缓存；
- Jigsaw 元数据优化；
- 结构引用索引；
- 强制区块加载审计报告。

## 验收标准

- 结构起点、Piece 列表、包围盒和引用结果通过对照测试；
- 目标结构的 P99 规划耗时明显下降；
- 缓存命中率和内存占用可观察；
- 数据包重载后缓存完全失效；
- 未识别结构模组可退化到原版行为；
- 不因并行顺序改变结构随机结果。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 缓存改变随机调用顺序 | 只缓存纯函数和只读元数据 |
| VoxelShape 或模板对象可变 | 缓存不可变摘要，不共享可变实例 |
| Datapack 重载产生陈旧数据 | 使用 generation ID 并在重载时全量失效 |
| 模组结构依赖线程本地状态 | 未验证模组按保守安全等级调度 |
| 大型结构本身合法但触发警告 | 阈值仅诊断，不默认禁止 |

---

# Phase 7：FEATURES、结构放置与长尾治理

## 目标

针对冒险整合包最可能出现的长尾阶段，降低结构模板、Processor、PlacedFeature 和跨区块操作的成本。

## 具体步骤

### 7.1 FEATURES 责任分解

每次 `FEATURES` 运行必须可归因到：

```text
结构放置
结构 Piece
模板
ProcessorList
单个 Processor
PlacedFeature
ConfiguredFeature
方块实体/NBT
后处理
跨区块访问
未知模组调用
```

### 7.2 模板元数据缓存

可缓存的安全内容：

- 模板尺寸；
- 只读 block info 分组索引；
- 按 palette 或方块类型预分类；
- 连接点元数据；
- 不依赖位置与随机数的静态过滤结果；
- 旋转/镜像后的坐标变换表。

不得直接缓存：

- 经过随机 Processor 后的最终方块列表；
- 与世界高度、邻块、群系或位置相关的结果；
- Loot、实体或方块实体最终 NBT；
- 依赖随机调用次数的结果。

### 7.3 Processor 分类

为 Processor 建立能力描述：

- `PURE_STATIC`
- `POSITION_DEPENDENT`
- `WORLD_READ_DEPENDENT`
- `RANDOM_DEPENDENT`
- `WORLD_WRITE_DEPENDENT`
- `UNKNOWN`

只有 `PURE_STATIC` 才允许强缓存；其他类型只能优化查找、分配或使用短生命周期缓存。

### 7.4 数据结构与分配优化

候选方向：

- 使用 packed block position；
- 复用受控临时缓冲；
- 减少 `BlockPos`、Stream、Iterator 和临时 List；
- 对只读列表使用数组索引；
- 避免重复边界检查；
- 合并完全等价的后处理标记；
- 在 Bye-Pregen 未处理的路径中减少 palette 与 section 查找。

必须通过 Mixin Gate 避免重复 Bye-Pregen 的相同优化。

### 7.5 跨区块访问优化

- 缓存 `WorldGenRegion` 内合法的区块引用；
- 禁止把同步 `ServerLevel#getChunk` 当作普通读取；
- 对越界访问记录来源；
- 对同一 Piece 的重复区块定位使用局部缓存；
- 不扩大原版允许的写入边界。

### 7.6 长任务治理

默认策略：

- 不在任务执行中强行中断；
- 不跨 Tick 保存任意函数栈；
- 通过限制同阶段并发避免多个长 FEATURES 同时运行；
- 通过结构成本估计降低超大型结构并行度；
- 后续实验版可在 Piece 边界切分，但必须证明原子性、随机顺序和邻区块语义。

## 交付物

- FEATURES 细分性能报告；
- 模板元数据缓存；
- Processor 能力模型；
- 数据结构优化；
- 跨区块访问审计；
- 结构成本感知调度。

## 验收标准

- 结构放置结果与对照一致；
- 方块实体、Loot、实体、Processor 和后处理完整；
- 目标结构的 FEATURES P99 明显下降；
- 复杂结构同时生成时 CPU 与内存峰值下降；
- 与 Bye-Pregen 同装时无重复注入和重复缓存；
- 未知 Processor 默认走原版安全路径。

## 主要风险

| 风险 | 缓解 |
|---|---|
| Processor 被错误判断为纯函数 | 默认 UNKNOWN，只有白名单或明确接口才提升等级 |
| 缓冲复用发生线程串扰 | 线程本地或显式所有权；使用后清空 |
| 批量写入遗漏更新 | 不在早期重写原版写入语义；逐项建立 GameTest |
| Piece 切分改变随机顺序 | 1.0 默认不启用跨 Piece 并行或跨 Tick 切分 |

---

# Phase 8：光照与最终化调度

## 目标

控制光照计算和结果完成洪峰，并处理 `LIGHT → FULL` 之间的主线程负载。

## 具体步骤

### 8.1 兼容优先

启动时检测：

- Bye-Pregen 光照模块是否启用；
- 其他光照引擎是否安装；
- 是否存在已知冲突注入。

如果已有兼容光照算法：

- SteadyChunks 仅管理任务预算、优先级和完成整形；
- 不替换算法内部。

### 8.2 光照任务预算

按以下维度限制：

- 在途光照区块数；
- 每维度任务数；
- 边界传播任务数；
- 完成回调数；
- 近处与远处优先级。

### 8.3 光照完成与区块发送协调

- 避免多个相邻区块在同 Tick 同时进入发送；
- 近处完整光照区块优先；
- 防止因发送节流导致已完成光照数据长期积压；
- 记录光照完成至首次发送延迟。

### 8.4 FULL 最终化细分

测量并预算：

- ProtoChunk 转换；
- Heightmap 最终化；
- POI；
- 方块实体登记；
- Tick 列表；
- NeoForge 事件；
- 模组区块加载回调。

## 交付物

- 光照调度桥；
- 完成整形；
- FULL 子阶段指标；
- 与 Bye-Pregen 光照模块的兼容规则。

## 验收标准

- 无黑区块、错光、永久未完成光照或边界接缝；
- 光照队列不会无限增长；
- `LIGHT → FULL → send` 的 P99 峰值下降；
- Bye-Pregen 光照存在时算法模块自动让路；
- 关闭调度后可恢复原版或第三方光照行为。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 光照依赖复杂导致饥饿 | 相邻依赖和近处传播保留专用预算 |
| 第三方光照引擎私有线程模型 | 只使用兼容适配器，不强行注入内部 |
| 完成整形造成可见黑块 | 只有完整光照后才发送，并设置最大等待 |

---

# Phase 9：区块 I/O、序列化、保存与卸载

## 目标

确保持续跑图后不会因保存、压缩、RegionFile 操作和卸载造成新的周期性卡顿。

## 具体步骤

### 9.1 先测量

按环节统计：

- RegionFile 等待；
- 读取；
- 解压；
- NBT 解析；
- 序列化；
- 压缩；
- 写入；
- fsync；
- 保存队列；
- 卸载队列；
- 主线程等待。

### 9.2 与 Bye-Pregen 的所有权

当 Bye-Pregen 的 GC-free 或序列化模块启用：

- SteadyChunks 不注入同一序列化方法；
- 只进行队列调度和背压；
- 使用兼容测试确认其 Future 与回调线程模型。

### 9.3 有界 I/O 队列

- 读取和写入分离或设置公平配额；
- 玩家前方读取优先于远处保存，但保存不能永久饥饿；
- 限制同时压缩任务；
- 防止保存队列占用无限内存；
- 服务器停止时可靠排空；
- 单个 RegionFile 的操作保持正确顺序。

### 9.4 卸载与缓存生命周期

- 区块卸载时释放 SteadyChunks 的所有缓存和任务引用；
- 维度卸载时取消等待任务；
- 服务器关闭时拒绝新任务并安全排空；
- 提供泄漏检测计数。

## 交付物

- I/O 诊断；
- 有界读写队列；
- 保存背压；
- 生命周期清理；
- Bye-Pregen 序列化兼容桥。

## 验收标准

- 持续跑图后保存队列有界；
- 停服和重启不丢区块；
- RegionFile 无损坏；
- 不因写入饥饿导致内存持续增长；
- Bye-Pregen 启用时无序列化 Mixin 冲突；
- 读取延迟和写入延迟均可观测。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 写入重排破坏 RegionFile 语义 | 同区域串行化并维护提交顺序 |
| 停服排空时间过长 | 停服模式提升写入预算并停止新生成 |
| 保存饥饿 | 老化优先级和硬性最大等待时间 |
| 重复序列化优化 | Mixin Gate 检测 Bye-Pregen 模块状态 |

---

# Phase 10：FastNoise 与 Bye-Pregen 正式兼容层

## 目标

把“可以同时启动”提升为“明确分工、经过自动测试、性能不重复”的正式兼容承诺。

## 具体步骤

### 10.1 版本与模块探测

- 使用 modid 和版本范围；
- 读取可公开获取的模块配置状态；
- 无法确认具体模块状态时采用保守关闭；
- 启动日志输出最终所有权表。

示例：

```text
NOISE block placement: FastNoise
Palette recount: Bye-Pregen
Chunk scheduling: SteadyChunks
Structure profiling: SteadyChunks
Light algorithm: Bye-Pregen
Light task budget: SteadyChunks
Serialization: Bye-Pregen
I/O backpressure: SteadyChunks
```

### 10.2 Mixin 冲突表

维护：

```text
目标类
目标方法
FastNoise 注入
Bye-Pregen 注入
SteadyChunks 注入
注入顺序
冲突处理
回退路径
```

### 10.3 兼容 API

可公开以下只读或协作接口：

- 注册阶段所有权；
- 注册世界生成任务安全等级；
- 注册结构成本估计器；
- 提供任务优先级提示；
- 提供模块状态；
- 订阅缓存失效；
- 注册可安全并行的自定义生成器。

API 必须是可选的；未适配模组仍使用保守路径。

### 10.4 自动组合测试

至少覆盖：

```text
SteadyChunks
SteadyChunks + FastNoise
SteadyChunks + Bye-Pregen
SteadyChunks + FastNoise + Bye-Pregen
```

每个组合执行：

- 客户端启动；
- 专用服务器启动；
- 新世界创建；
- 固定半径生成；
- 结构基准；
- 保存重启；
- 数据包重载；
- 长时间跑图；
- parity 或语义一致性检查。

### 10.5 C2ME 互斥

检测到 C2ME：

- 默认阻止游戏启动并给出清晰错误；
- 开发模式可允许只启用分析器，但不得同时启用调度和区块系统替换；
- 文档提供迁移步骤和配置替代关系。

## 交付物

- 兼容层；
- 模块所有权日志；
- 组合测试；
- 兼容 API；
- 迁移文档。

## 验收标准

- 三种目标组合均通过自动测试；
- 无重复 palette、序列化、噪声或光照算法注入；
- 兼容失败时明确关闭模块，不产生静默错误；
- 更新 FastNoise 或 Bye-Pregen 后，版本门控阻止未经测试的高风险模块自动启用；
- 用户可从日志看出每个优化由谁负责。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 第三方内部配置没有稳定 API | 使用版本适配器和保守默认 |
| 版本更新改变注入点 | CI 锁定测试版本，新版本进入兼容候选状态 |
| 功能边界随第三方更新变化 | 所有权表按版本维护 |
| “兼容”但性能重复 | 指标中暴露每个模块命中次数和所有者 |

---

# Phase 11：正确性、压力测试与性能定型

## 目标

在进入公开测试前，证明调度、结构、光照、I/O 和取消逻辑不会损坏世界，并冻结 1.0 默认配置。

## 具体步骤

### 11.1 世界生成一致性测试

分两种模式：

#### 严格一致性测试

适用于可确定路径：

- 固定世界种子；
- 固定模组和 datapack；
- 固定线程策略；
- 对指定区域的区块数据计算规范化哈希；
- 比较结构起点、Piece、方块、流体、Heightmap、方块实体和结构引用。

#### 语义一致性测试

用于原版本身存在执行顺序非确定性的场景：

- 结构位置一致；
- 结构完整；
- 无缺块；
- 无引用丢失；
- 方块实体与 Loot 合法；
- 无越界和非法状态；
- 同配置重复运行差异不超过对照组自身差异。

### 11.2 故障注入

测试：

- 工作线程抛出异常；
- 维度在任务等待时卸载；
- 玩家退出；
- 服务器停止；
- Chunk Ticket 消失；
- 保存失败；
- 数据包重载；
- 客户端断开；
- 光照任务异常；
- 模组结构生成异常。

目标是验证任务、permit、缓存和文件资源均能清理。

### 11.3 并发压力

- 低核心、高核心 CPU；
- 4 GB、8 GB、16 GB 堆；
- 多玩家分散；
- 高频转向；
- 高频跨维度；
- 大量结构；
- 机械硬盘与 SSD；
- Windows 与 Linux；
- 集成显卡和独立显卡；
- 不同客户端渲染优化组合。

### 11.4 长时间测试

至少包含：

- 24 小时自动持续跑图；
- 周期性保存和重启；
- 世界大小持续增长；
- 多次 datapack reload；
- 多次维度创建与卸载；
- 堆使用趋势和任务计数趋势。

### 11.5 性能统计规范

- 每场景先预热；
- 使用多个新世界；
- 每个组合重复运行；
- 报告中位数、P95、P99、最大值；
- 区分事件发生时间和报告发布时间；
- 不只报告平均 FPS 或平均 TPS；
- 保存原始数据；
- 发布负收益和失败结果。

### 11.6 默认预设

建议：

#### `smooth_integrated`

- 为客户端和集成服务器保留较多核心；
- 较低 FEATURES 并发；
- 严格 FULL 与发送平滑；
- 默认用于单人冒险整合包。

#### `balanced`

- 稳定性与吞吐量平衡；
- 默认用于普通服务器。

#### `throughput_server`

- 较高并发；
- 仍保留背压和完成平滑；
- 适用于独立服务器或预生成，但不是项目主要宣传预设。

## 交付物

- 正确性测试报告；
- 24 小时压力报告；
- 性能对照报告；
- 默认配置；
- 已知问题列表；
- 兼容矩阵。

## 验收标准

- 所有项目级正确性门槛通过；
- 无持续内存增长；
- 无 permit 泄漏；
- 无永久等待任务；
- 性能提升在多次运行中稳定；
- 至少一个目标硬件档位达到 1.0 性能门槛；
- 负收益场景有自动降级或文档说明。

## 主要风险

| 风险 | 缓解 |
|---|---|
| 测试通过但真实整合包失败 | 引入多个结构和世界生成模组组合 |
| 哈希差异被误判为损坏 | 同时记录对照组自身非确定性 |
| 只在开发机有效 | 建立硬件矩阵和社区基准格式 |
| 长时间泄漏难定位 | 每类任务、缓存和区块状态提供实时计数 |

---

# Phase 12：Alpha、Beta、1.0 发布与维护

## 目标

安全地将实验实现转为可维护、可诊断、可回滚的公开模组。

## 具体步骤

### 12.1 Alpha

只面向开发和整合包测试：

- 默认启用诊断；
- 高风险优化默认关闭；
- 强制输出兼容性摘要；
- 每个崩溃报告附带任务和 permit 状态；
- 不承诺存档长期兼容；
- 重点收集结构模组和硬件差异。

### 12.2 Beta

- 冻结配置键；
- 默认启用已通过门槛的调度、背压和完成平滑；
- 结构优化仅启用安全缓存与已验证路径；
- 发布迁移指南；
- 发布性能复现脚本；
- 建立已知冲突数据库。

### 12.3 Release Candidate

- 只修复正确性、兼容性和严重性能回归；
- 所有默认模块完成组合测试；
- 发布第三方许可证与来源；
- 更新模组描述，明确与 C2ME 互斥；
- 提供一键诊断包导出。

### 12.4 1.0

1.0 必须包含：

- 有界区块调度；
- 固定与自适应资源预算；
- 完成优先和软取消；
- FULL 整合平滑；
- 区块发送平滑；
- 结构规划与 FEATURES 诊断；
- 至少一组经过验证的结构专项优化；
- FastNoise 与 Bye-Pregen 正式兼容；
- I/O 背压；
- 客户端反馈；
- 完整测试和迁移文档。

### 12.5 维护策略

- 1.21.1 分支只接受修复、兼容与受控优化；
- 新 Minecraft 版本使用独立分支；
- 每次更新 FastNoise 或 Bye-Pregen 先进入兼容候选；
- 性能 PR 必须附带原始基准；
- 高风险模块必须提供回退；
- 不使用“提高平均 CPS”作为唯一合并理由。

## 验收标准

- 用户可卸载 C2ME、安装 SteadyChunks，并使用迁移文档完成配置；
- 默认配置不要求手工调参即可改善目标整合包跑图稳定性；
- 崩溃或卡顿报告包含足够上下文定位具体阶段；
- 更新不会静默改变世界生成结果；
- 发布物包含符号、源码、许可证和变更记录。

---

## 7. 建议代码结构

```text
src/main/java/<package>/
├─ SteadyChunks.java
├─ bootstrap/
│  ├─ ModuleBootstrap.java
│  ├─ MixinGate.java
│  └─ CompatibilityProbe.java
├─ config/
│  ├─ CommonConfig.java
│  ├─ ServerConfig.java
│  ├─ ClientConfig.java
│  └─ Presets.java
├─ telemetry/
│  ├─ ChunkFlightRecorder.java
│  ├─ StageMetrics.java
│  ├─ StructureMetrics.java
│  ├─ FeatureMetrics.java
│  ├─ ClientFrameMetrics.java
│  ├─ RingEventBuffer.java
│  └─ ReportExporter.java
├─ scheduler/
│  ├─ ChunkTask.java
│  ├─ ChunkTaskGraph.java
│  ├─ PriorityModel.java
│  ├─ StageLimiter.java
│  ├─ ResourcePermit.java
│  ├─ BackpressureController.java
│  ├─ CancellationPolicy.java
│  └─ FairnessManager.java
├─ governor/
│  ├─ ResourceGovernor.java
│  ├─ AimdController.java
│  ├─ PressureSnapshot.java
│  └─ EmergencyMode.java
├─ worldgen/
│  ├─ structure/
│  ├─ features/
│  ├─ light/
│  ├─ completion/
│  └─ io/
├─ network/
│  ├─ ChunkSendGovernor.java
│  ├─ ClientFeedbackPacket.java
│  └─ Protocol.java
├─ client/
│  ├─ ChunkReceiveMetrics.java
│  ├─ RenderPressureProbe.java
│  └─ OptionalBuildGovernor.java
├─ compat/
│  ├─ fastnoise/
│  ├─ byepregen/
│  ├─ renderer/
│  └─ structures/
├─ api/
│  ├─ WorldgenSafetyLevel.java
│  ├─ StructureCostEstimator.java
│  ├─ StageOwnership.java
│  └─ SteadyChunksApi.java
├─ command/
├─ mixin/
└─ test/
```

---

## 8. 配置设计原则

### 8.1 默认只暴露少量预设

普通用户优先选择：

```toml
preset = "smooth_integrated"
```

高级配置放入独立区段。

### 8.2 配置草案

```toml
[general]
enabled = true
preset = "smooth_integrated"
strict_compatibility = true

[scheduler]
mode = "adaptive"
reserve_logical_processors = 2
worker_threads_min = 1
worker_threads_max = 4
max_inflight_total = 64
completion_first = true
soft_cancellation = true

[stage_limits]
structure_starts_min = 1
structure_starts_max = 2
noise_min = 1
noise_max = 3
features_min = 1
features_max = 2
light_min = 1
light_max = 2

[governor]
target_p95_mspt = 40.0
hard_mspt = 48.0
target_process_cpu = 0.75
heap_pressure = 0.78
long_frame_ms = 50.0
emergency_frame_ms = 150.0
increase_cooldown_ticks = 100
decrease_cooldown_ticks = 20

[completion]
max_full_commits_per_tick = 2
max_chunk_sends_per_tick_per_player = 2
max_send_bytes_per_tick_per_player = 1048576

[structure]
profiling = true
safe_metadata_cache = true
jigsaw_optimizations = true
forced_chunk_load_audit = true
unknown_structure_safety = "conservative"

[compatibility]
fastnoise = "auto"
byepregen = "auto"
c2me = "reject"
unknown_chunk_system = "disable_risky_modules"

[telemetry]
basic_metrics = true
high_detail = false
export_spike_context = true
automatic_upload = false
```

具体默认值必须由 Phase 2 与 Phase 11 校准，以上仅为配置结构示例。

---

## 9. 测试矩阵

### 9.1 硬件档位

| 档位 | 目的 |
|---|---|
| 4 核 / 8 线程 | 验证资源保留与低端 CPU |
| 6–8 核 / 12–16 线程 | 目标普通玩家 |
| 12–16 核以上 | 验证高并发和 NUMA/缓存争抢倾向 |
| 集成显卡 | 验证 CPU 与内存带宽共享压力 |
| 独立显卡 | 区分渲染与生成瓶颈 |
| SATA SSD / NVMe / HDD | 区分 I/O 与 CPU |

### 9.2 游戏场景

| 场景 | 核心指标 |
|---|---|
| 单人直线飞行 | 帧时间、CPU、可见前沿 |
| 单人高速转向 | 过期任务、软取消、波前 |
| 飞艇巡航 | 长时间稳定性 |
| 双玩家反向探索 | 公平性与总吞吐 |
| 结构密集区 | `STRUCTURE_STARTS` / `FEATURES` P99 |
| 大型 Jigsaw | Piece 数、碰撞与模板缓存 |
| 地下复杂地形 | NOISE、CARVERS、LIGHT |
| 反复跨维度 | 生命周期清理 |
| 持续跑图与返回 | 保存、卸载、读取 |
| 24 小时压力 | 泄漏、死锁、存档安全 |

### 9.3 指标报告

每份报告必须包含：

- P50/P95/P99/最大帧时间；
- P50/P95/P99/最大 MSPT；
- 超过 50/100/250/500 ms 的事件数；
- 平均和峰值 CPU；
- 每线程 CPU；
- 堆峰值；
- 分配率；
- GC 暂停；
- ProtoChunk 峰值；
- 各阶段在途峰值；
- FULL 队列峰值；
- 发送队列峰值；
- 请求到首次显示的延迟；
- 区块吞吐量；
- 结构和 FEATURES 长尾排名。

---

## 10. 全局风险登记册

| 风险 | 严重度 | 发生可能 | 主要缓解 |
|---|---:|---:|---|
| 区块依赖死锁 | 极高 | 中 | 依赖解锁保留资源、锁顺序、watchdog |
| 任务取消导致部分生成 | 极高 | 中 | 仅软取消，禁止中断运行阶段 |
| 世界生成结果变化 | 极高 | 中 | parity/语义测试、缓存纯函数限制 |
| 存档损坏 | 极高 | 低至中 | 故障注入、保存重启测试、不可逆格式禁令 |
| 模组线程安全问题 | 高 | 高 | 安全等级、保守回退、适配白名单 |
| 与 FastNoise/Bye-Pregen Mixin 冲突 | 高 | 中 | 所有权表、Mixin Gate、组合 CI |
| 调度控制器振荡 | 中高 | 中 | 滑动窗口、迟滞、冷却、AIMD |
| 吞吐量下降过多 | 中高 | 中 | 可见缺口保底、预设、独立服务器模式 |
| 主线程整合积压 | 高 | 中 | 依赖保底、最大等待、队列指标 |
| 区块发送过慢 | 中高 | 中 | 近处优先、速度感知、最低发送预算 |
| 缓存内存泄漏 | 高 | 中 | 维度/重载 generation ID、有界缓存、泄漏计数 |
| 结构缓存陈旧 | 高 | 中 | datapack reload 全失效 |
| 性能报告被测试噪声误导 | 中 | 高 | 多次运行、固定轨迹、原始数据发布 |
| 许可证不兼容 | 高 | 低至中 | Phase 0 审计、来源登记、clean-room |
| 维护成本过高 | 中高 | 高 | 模块边界、避免大范围 Overwrite、版本锁定 |

---

## 11. 每个 Phase 的停止条件

开发中必须允许停止或调整方向。

### 停止调度器重写

若 Phase 3 证明：

- 固定背压不能改善 P99；
- 卡顿主要完全来自客户端渲染器；
- 或原版 1.21.1 调度无法在不大规模覆盖的情况下安全接管；

则保留诊断与发送平滑，重新评估调度架构。

### 停止结构通用优化

若 Phase 2 表明：

- 长尾集中在少数第三方结构模组；
- 通用结构代码占比很低；

则优先向上游提交修复或开发适配模块，不重写通用 Jigsaw。

### 停止 I/O 重写

若 Bye-Pregen 已消除主要序列化与 I/O 峰值，SteadyChunks 只保留背压和生命周期管理。

### 停止客户端注入

若与 Embeddium 或其他渲染模组的兼容风险高于收益，客户端模块只提供反馈，不控制 Section 编译。

---

## 12. 推荐的实施顺序

```text
Phase 0  源码、许可证、功能边界
    ↓
Phase 1  工程与可重复基准
    ↓
Phase 2  端到端 Flight Recorder
    ↓
性能门槛冻结
    ↓
Phase 3  固定预算调度器
    ↓
Phase 4  自适应资源治理
    ↓
Phase 5  FULL、发送与客户端负载平滑
    ↓
Phase 6  结构规划与引用优化
    ↓
Phase 7  FEATURES 与结构放置优化
    ↓
Phase 8  光照与最终化
    ↓
Phase 9  I/O、保存与卸载
    ↓
Phase 10 FastNoise / Bye-Pregen 正式兼容
    ↓
Phase 11 正确性、压力和性能定型
    ↓
Phase 12 Alpha → Beta → RC → 1.0
```

兼容性工作不应真的等到 Phase 10 才开始。Phase 10 是正式冻结和承诺兼容；从 Phase 1 起，每次 CI 都应运行最小兼容测试。

---

## 13. 1.0 Definition of Done

只有同时满足以下条件，项目才可宣称“替代 C2ME”：

- 能在目标 1.21.1 NeoForge 整合包中移除 C2ME 后独立承担区块调度和资源治理；
- FastNoise 与 Bye-Pregen 同装通过完整测试；
- 跑图 P99 帧时间和严重长帧事件优于 C2ME 对照；
- P99 MSPT 不劣于目标门槛；
- 可见前沿区块延迟可接受；
- 无区块损坏、结构错误、光照错误、永久 Future 或死锁；
- 24 小时持续跑图稳定；
- 多玩家公平；
- 配置具有安全默认值；
- 每个高风险优化可独立关闭；
- 提供迁移、兼容、诊断和故障恢复文档；
- 发布性能原始数据，而不是只给单一平均值；
- 第三方许可证和来源完整。

---

## 14. 1.0 之后的可选方向

- 基于玩家或载具速度的通用方向预测；
- 面向飞艇的更长前向需求窗口，但保持通用 API；
- 结构模组适配 SDK；
- 自动识别世界生成线程安全能力；
- 对结构 Piece 成本进行在线学习；
- 更精细的客户端区块编译协同；
- 多维度全局资源配额；
- 服务器向客户端下发建议平滑参数；
- 面向新 Minecraft 版本的架构迁移；
- 在正确性证明充分后探索安全的 Piece 级并行。

---

## 15. 调查与实现参考

以下链接用于源码审计和功能边界确认；正式开发时应记录具体版本或提交哈希。

- [C2ME GitHub](https://github.com/RelativityMC/C2ME-fabric)
- [C2ME 1.21.1 backport branch](https://github.com/RelativityMC/C2ME-fabric/tree/backports/1.21.1)
- [FlowSched](https://github.com/RelativityMC/FlowSched)
- [Bye-Pregen](https://github.com/MoePus/Bye-Pregen)
- [FastNoise](https://codeberg.org/ZenXArch/FastNoise)
- [FastNoise Modrinth](https://modrinth.com/mod/zfastnoise)
- [NeoForge 1.21 migration primer](https://docs.neoforged.net/primer/docs/1.21/)
- [NeoForge 1.21.1 documentation](https://docs.neoforged.net/docs/1.21.1/)
- [NeoForge 1.21.x Javadocs](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/)
- [Java Flight Recorder](https://docs.oracle.com/en/java/javase/21/jfapi/)
- [G1 GC tuning guide](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html)

---

## 16. 最终开发策略

项目应从“可证明的系统级稳定性”出发，而不是从“尽可能多开线程”出发：

1. **用 Phase 2 证明卡顿来自哪里；**
2. **用 Phase 3–5 先治理资源争抢、任务波前和完成洪峰；**
3. **用 Phase 6–7 解决冒险包结构生成的长尾；**
4. **把 FastNoise 和 Bye-Pregen 视为下层阶段优化提供者；**
5. **只在数据证明必要时继续进入光照与 I/O 算法替换；**
6. **最终以端到端 P99、存档正确性和兼容性决定是否成功替代 C2ME。**
