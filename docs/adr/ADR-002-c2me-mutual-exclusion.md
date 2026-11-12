# ADR-002: 与 C2ME 互斥而非共存

- **状态**：Accepted
- **日期**：2026-07-31
- **决策者**：SteadyChunks 项目

## 上下文

C2ME 是 Fabric 生态下成熟的多线程区块生成模组，1.21.1 backport 分支活跃维护（Bye-Pregen vendored 0.4.0-alpha.0.116）。其覆盖范围包括：

- `c2me-rewrites-chunk-system`：区块系统重写
- `c2me-rewrites-chunkio`：chunkio 重写
- `c2me-rewrites-chunk-serializer`：序列化重写
- `c2me-threading-lighting`：光照线程化
- `c2me-opts-scheduling`：调度优化（基于 FlowSched）
- `c2me-opts-dfc`：不动点密度函数
- `c2me-opts-natives-math`：native 数学
- `c2me-notickvd` / `c2me-client-uncapvd`：视距解耦
- `c2me-opts-accel-opencl`：OpenCL 加速（ARR）

SteadyChunks 的目标范围与 C2ME 在**调度、chunk-system、I/O 背压、光照调度、FULL 整合**等关键路径高度重叠。两套系统同时注入同一组原版类（`ServerChunkManager` / `ThreadedAnvilChunkStorage` / `ChunkHolder` / `IOWorker` / `LevelLightEngine`）会导致：

1. **任务双调度**：同一区块被两套执行器同时推进，状态机错乱；
2. **permit 双重计数**：资源令牌语义不一致，背压失效；
3. **完成回调链断裂**：FULL 整合与发送配额被两层包装，结果不可预测；
4. **Mixin 顺序冲突**：两边都 `@Inject` 同一方法的 `RETURN` 或 `HEAD`，行为取决于加载顺序；
5. **诊断失真**：性能数据被两层调度器各自归因，无法定位真实长尾。

## 决策

SteadyChunks 与 C2ME **互斥**，不允许同时启用：

1. **默认行为**：检测到 C2ME（modid `c2me` 或 vendored 端口类存在性）即阻止游戏启动，输出清晰错误与迁移指引。
2. **开发模式**：配置 `[compatibility] c2me = "analyzer_only"` 允许仅启用 SteadyChunks 诊断器，不启用冲突模块，用于对照基准采集。
3. **强制共存**：`c2me = "force_coexist"` 仅供开发对照测试，启动时打印 `WARN`，不承诺正确性。
4. **替代关系**：SteadyChunks 替代 C2ME 的 `chunk-system` / `scheduling` / `lighting-threading` / `chunkio` 调度链路；不复用 C2ME ARR 段（`opts-accel-opencl`）。
5. **不替代的 C2ME 模块**：`c2me-notickvd` / `c2me-client-uncapvd` / `c2me-opts-dfc` / `c2me-opts-natives-math` 不在 SteadyChunks 1.0 范围内，用户从 C2ME 迁移时需自行评估是否需要替代方案。

## 后果

### 正面

- 避免双调度、双 permit、Mixin 冲突等不可预测行为；
- 启动时明确告知用户，而非运行时崩溃；
- 简化 SteadyChunks 的兼容矩阵：不需维护"与 C2ME 共存"的测试组合；
- 性能对照基准清晰（SteadyChunks vs C2ME，而非 SteadyChunks + C2ME）。

### 负面

- 用户必须二选一，无法叠加两者的优势模块；
- 已使用 C2ME 的整合包迁移成本高，需提供迁移文档；
- Bye-Pregen vendored C2ME 端口在 NeoForge 上有传播，SteadyChunks 必须同时检测 `modid == "c2me"` 与 vendored 类存在性，避免漏检。

## 备选方案

### A. 与 C2ME 共存，仅实现 C2ME 不覆盖的模块

被否决：SteadyChunks 的核心价值（调度、背压、完成整形、自适应治理）正是 C2ME 的核心覆盖范围。共存等于不实现核心，退化为诊断器与发送平滑模块，不符合项目定位。

### B. 与 C2ME 共存，通过 Mixin Gate 自动让路

被否决：C2ME 没有公开的模块状态 API，且其内部 Mixin 顺序对 SteadyChunks 不透明；让路逻辑会非常脆弱，C2ME 任何更新都可能破坏 SteadyChunks。同时 Bye-Pregen vendored 的 C2ME 端口进一步增加变体。维护成本不可接受。

### C. 仅与 C2ME Fabric 版互斥，与 Bye-Pregen vendored 端口共存

被否决：Bye-Pregen vendored C2ME 端口包含 `rewrites-chunk-system` / `rewrites-chunkio` / `opts-scheduling`，正是 SteadyChunks 替代范围。若允许共存，Bye-Pregen 用户会同时跑两套调度系统，违反本 ADR 的核心论据。
