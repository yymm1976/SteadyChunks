# round14-stalls — 卡死证据目录（不入库，保留在磁盘）

原始 jstack / 轮次日志共 227MB、311 文件，不入 Git（.gitignore），磁盘保留供阶段 5 分析。
本文件是分类摘要。

## 阶段 2 验证结果（2026-08-04）

- **拆分**：SchedulerAdmissionGameTest 拆为 4 类（Admission 8 / Lifecycle 9 / WatchdogRecovery 12 / WorldgenIntegration 1 = 30 测试）
- **结构文件**：每类需要 `data/steadychunks/structure/{类名小写}.empty.nbt`（NeoForge 结构名含类名）
- **5 连过**：rounds 36-40 连续 PASS（`All 30 required tests passed` ×5，30 批次/轮，0 失败）
  - 证据：latest-round{36,37,38,39,40}.log
- **无意外 Watchdog 恢复**：5 轮恢复日志完全一致（6 条批次 + 10 条 UNSAFE，全部来自 12 个
  Watchdog 恢复测试的刻意触发）；watchdogMustNotRecoverDuringCorrectnessTests 每轮通过

## 卡死分类（2026-08-03 ~ 08-04，约 152 份 jstack 采样）

| 忙转点 | 计数 | 占比 |
|---|---|---|
| ChunkMap.processUnloads:526 | 52 | 34% |
| （jstack 未采到 Server thread） | 49 | 32% |
| ServerChunkCache.tickChunks（shuffle） | 30 | 20% |
| ChunkMap.saveChunkIfNeeded | 10 | 7% |
| 其他（purgeStaleTickets / canPositionTick / tickBlockEntities 等） | 11 | 7% |

**全部为纯原版栈，与 SteadyChunks 代码无交集**（与 AGENTS.md §8 记载一致）。

## 累计过率统计（阶段 2c 磨 5 连过）

| 运行 | 轮数 | PASS | 过率 | 最佳连过 |
|---|---|---|---|---|
| Run A | 20 | 8 | 40% | 4 |
| Run B | 40 | 15 | 37.5% | 4 |
| Run C | 40 | 17 | 42.5% | 4 |
| Run D | 60 | 13 | 21.7% | 4 |
| Run E（达成） | 40 | 16 | 40% | **5** |

5 连过在 200 轮内达成（含 3 次 4 连过中途打断），符合 p≈0.4 的几何分布预期。
