# Round 14 工作区审计（阶段 0 现场备份）

> 基线：`721e329`（第 13 轮）。未提交改动：3 个文件（+ 未跟踪 AGENTS.md/.reasonix/reasonix.toml，与项目无关）。

## 未提交改动清单

| 文件 | 改动性质 | 状态 |
|---|---|---|
| `scheduler/ChunkScheduler.java` | 第 14 轮生产代码：capture/submit 拆分（第 13 轮已提交）；本轮新增 `requeueRecoveryBatch`、`escalateRecoveryBatchAsync` | 正式改动，保留 |
| `scheduler/Watchdog.java` | 第 14 轮生产代码：recoverySubmitter 独立提交线程、emergencyExecutor（虚拟线程）停服分派、shutdownBatches、CAPTURING 阶段、preCaptureProbe、generation 传参恢复、MAILBOX_SUBMITTING 超时升级 | 正式改动，保留 |
| `gametest/SchedulerAdmissionGameTest.java` | 第 14 轮新测试 3 个 + 旧测试适配（stop 异步轮询）+ clear_all finally 防御 + 诊断日志 | 混合：正式 + 二分残留 |

## 二分残留（须恢复）

1. `SchedulerAdmissionGameTest.java:1486` — `// @GameTest(...steady_watchdog_blocking_submit...) // BISECT-DISABLED` → 恢复注解
2. `SchedulerAdmissionGameTest.java:1587` — `// @GameTest(...steady_watchdog_blocking_escalate...) // BISECT-DISABLED` → 恢复注解
3. `SchedulerAdmissionGameTest.java:1690` — `// @GameTest(...steady_watchdog_submit_escalate...) // BISECT-DISABLED` → 恢复注解
4. `SchedulerAdmissionGameTest.java:1832` — `// BISECT: 临时去掉 start`（stop_start_publish 的 startRecoveryThread 被注释）→ 恢复 start
5. `SchedulerAdmissionGameTest.java:1773` — `// BISECT-ENABLED` 标记 → 清理注释后缀

## 正式改动（保留）

- clear_all 测试：断言加实际值/noiseAvail/inflight 诊断 + finally 防御（防残留级联）——保留（诊断文本保留在断言信息中）
- shutdown_batch / blocking_submit 测试：stop 异步后轮询终态（awaitTrue 辅助）——保留
- 新增测试：blockingMailboxSubmissionMustEscalateWithoutShutdown / stoppedWatchdogMustNotPublishNewBatch / stopRecoveryThreadMustNotBlockOnCompletionCallback ——保留
- awaitTrue 轮询辅助 ——保留

## 阶段 0 期间发现的遗留问题（供阶段 1/2）

- 30 测试版 clear_all 失败：`noiseAvail=0 inflight=1`（实际=2）——残留 in-flight 任务占 NOISE permit（limit=1 时占满）
- 26 测试版（4 个新测试禁用）全过 → 残留与 stop_start_publish 相关（27 版复现、26 版消失）
- stop_start_publish 的 stop→start 新线程 1 秒后醒来可能触发第一级（override 永不运行时代理永不完成）
- 忙转卡死（processUnloads/saveChunkIfNeeded/tickChunks.shuffle）触发率随测试数上升（§8 环境竞态 + 负载正相关）
