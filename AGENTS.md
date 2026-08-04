# SteadyChunks — Agent 交接文档

> 最后更新：2026-08-04（第 14 轮 6 阶段 + 审查第 2/3 轮修复：分支 `overnight/round14-inflight-diagnostics`，分支 HEAD `56a0928`（含 docs 提交）、代码 HEAD `0f73ffa`，已推送 origin）。
> 本文档用于跨 Harness 迁移交接，接手后先读此文件再动手。

## 1. 项目速览

- **项目**：SteadyChunks —— NeoForge 1.21.1 区块调度优化模组（NOISE 阶段准入控制 + 生命周期管理）
- **modid**：`steadychunks`，包根：`com.mochi_753.steadychunks`
- **仓库**：本地 `c:\Users\杨铭\Desktop\SteadyChunks`
- **分支**：`main` = 第 13 轮（721e329，已推送 origin/main）；**`overnight/round14-inflight-diagnostics`** = 第 14 轮全部工作（cdce79d → 5fce083，11 个提交，**已推送 origin**；比较 721e329...5fce083）
- **构建工具**：Gradle 8.14 + NeoForge 21.1.218（`neo_version=21.1.218`），Java 21，moddev 2.0.139

## 2. 构建与测试环境（重要，违反会卡死或编译失败）

| 操作 | 路径/命令 | 说明 |
|---|---|---|
| 编译/单元测试 | **junction 路径** `C:\SteadyChunks` | 原路径含中文用户名 `杨铭`，编译会 ClassNotFoundException；junction 是目录联接，改的是同一份代码 |
| GameTest | **原路径** `c:\Users\杨铭\Desktop\SteadyChunks` | junction 路径跑 GameTest 会卡死 |
| GameTest 缓存 | 默认 `C:\Users\杨铭\.gradle` | `C:\ghome-proj-raw` 缺 `neoform-runtime:2.0.18` 且网络下载失败，**不要用** |
| 构建命令 | `$env:PATH += ";C:\Users\杨铭\bin"; rtk err gradlew compileJava` | rtk 工具链（压缩输出），junction 路径下执行 |
| GameTest（连过循环） | `bash tools/run-round1-loop.sh <最大轮数> <连过数>` | 原路径；每轮清 world/log、轮询 PASS/FAIL/STALL、证据入 `artifacts/round14-stalls/` |
| GameTest（整夜 soak） | `powershell -ExecutionPolicy Bypass -File tools/run-gametest-soak.ps1 -Rounds 50` | 50 轮、失败证据采集、卡死 jstack 分类；结果入 `artifacts/soak/`，解析 `python tools/parse-gametest-results.py artifacts/soak` |

**跑 GameTest 前的环境清理（必须）**：
（审查修正：按 PID 记录终止，禁止全局终止——与第 14 轮硬约束一致）
```powershell
# 记录并仅终止命令行含本仓库路径的 java（IDE/其他服务器/用户程序不动）
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match [regex]::Escape('C:\Users\杨铭\Desktop\SteadyChunks') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Remove-Item -Recurse -Force "run-server\world"; Remove-Item -Force "run-server\logs\latest.log"
```
跑完必须再次按上述方式清 java 进程（残留进程会空转烧 CPU）。

**注意**：分支已推送 origin；若需再次推送 `git push origin overnight/round14-inflight-diagnostics`。

## 3. 当前 Git 状态（接手第一步）

- **main**：`721e329`（第 13 轮，origin/main 已同步）
- **overnight/round14-inflight-diagnostics**（当前工作分支）：第 14 轮 6 阶段全部完成，11 个提交：

| 提交 | 内容 |
|---|---|
| cdce79d | 阶段 0：工作区保护（working-tree.patch/worktree-audit/test inventory）+ 恢复 30 测试集合 |
| b8d7df0 | 阶段 1：round 14 恢复监督状态机补完 + 停服隔离（30/30 三连过） |
| fe9fa59 | 阶段 2：测试拆分 4 类 + SchedulerGameTestFixture（统一清理/清洁断言/失败隔离）+ 5 连过（rounds 36-40） |
| 8db1a6a | 阶段 3：固定容量在途任务追踪（diagnostics/inflight，32768 环形缓冲，无强引用，每 taskId 唯一终态）+ 3 连过 |
| 1fd0655 | 阶段 4：在途停滞检测（独立 daemon）+ 事故快照（IncidentRecorder，只诊断不自愈）+ 3 连过 |
| 0010ab9 / d21d196 / 5fcddf7 / f872c7f | 阶段 5a：soak 三件套 + 3 个工具修复（UTF-8 BOM / `-Dfml.modFolders` 精确 PID / null 日志保护） |
| fbd2797 | 阶段 5b/5c：soak 50 轮结果摘要（21 PASS / 29 STALL / 0 FAIL，42%，全纯原版卡死栈） |
| 5fce083 | 阶段 5c：AGENTS.md 更新至第 14 轮完成态 |
| 0f73ffa | 审查第 2 轮修复（9 项，已推送）：P0-1 requeue offer 后二次生命周期校验+测试 requeueMustNotPublishAfterShutdownClear；P0-2 soak 脚本按仓库作用域落地（Test-IsRepoJava/Wait-ProcessGone/Stop-ProcessTree/旧日志前置）+ 两脚本判定串改 `All [0-9]+ required tests passed`（31 测试）；提交线程 phase 回退防护 + 滞留指标；submitRecoveryBatch 每任务 isDone 跳过；环形缓冲零分配 + publishedSequences 防撕裂；逐出 rememberTerminated + 有界 FIFO 记忆；IncidentRecorder 计数（Submitted/Written/Dropped/WriteFailed）|

- **未跟踪**（不入库）：`.reasonix/`、`reasonix.toml`、`artifacts/tool-review/`；证据目录 `artifacts/round14-stalls/`（227MB）、`artifacts/soak/`（177MB）已 gitignore，磁盘保留
- **待办**：① 等待第 3 轮审查意见与验证（本轮修复提交 `0f73ffa` 后已有复核意见，下一小提交收尾中）；② 是否 merge 到 main 由用户决定（目标规定"不 merge main，仅推送分支"）。

## 4. 第 14 轮交付内容（阶段 2-4 新增，代码结构）

### 4.1 测试基础设施（阶段 2）
- `gametest/SchedulerGameTestFixture.java`：`resetGlobalState()`（统一清理：钩子→停恢复线程→clearAll→unpause→**重试风暴排空**→复位）、`assertCleanState()`（清洁断言）、辅助方法（obtainHolder/waitForQueueDrain/awaitTrue）。
- **关键机制（重试风暴）**：clearAll 的 error 完成会让原版立即批量重试真实生成任务（12ms 内 ~150 个重新入队）；若测试随后暂停准入，风暴任务涌入队列破坏精确计数断言。fixture 在清理末尾启用调度器并等待队列排空（先等到达再等排空，不用固定 sleep）。
- **失败隔离**：每个测试首语句注入 `resetGlobalState()`（GameTest 无 before/after 钩子；上一测试断言失败中途中止的残留由下一测试接管清理）。
- 测试拆分：`SchedulerAdmissionGameTest`（8）/ `SchedulerLifecycleGameTest`（9）/ `WatchdogRecoveryGameTest`（12+1=13，含 requeueMustNotPublishAfterShutdownClear）/ `WorldgenIntegrationGameTest`（1）= 31 测试。
- **结构文件按类名绑定**：NeoForge 结构名 = `{holder}:{类名小写}.{template}` → 每个测试类需要 `data/steadychunks/structure/{类名小写}.empty.nbt`。
- 拆分工具：`tools/split-gametests.py`（javadoc 回溯只收集紧邻块，防越界吞前方法）+ `tools/append-fixture-delegates.py`（幂等检查必须用委托块标记，不能用 resetGlobalState 字样——注入语句也含该字符串）。

### 4.2 在途任务追踪（阶段 3，`diagnostics/inflight/`）
- `TaskEventType`：CREATED→ADMITTED→DEQUEUED→SUBMITTED→MAILBOX_STARTED→EXECUTING→ORIGINAL_RETURNED→ORIGINAL_COMPLETED→PROXY_COMPLETED→RECOVERY_CAPTURED→RECOVERY_COMPLETED→REJECTED→CANCELLED→TASK_TERMINAL。
- `TaskTraceRingBuffer`：固定 32768 事件，值类型（无 Holder/Future 强引用），满时覆盖最旧。
- `InflightTaskRegistry`：taskId 分配、活动表（ACTIVE_CAP=8192 逐出保底）、**每 taskId 只允许一次 TASK_TERMINAL**（CAS；重复计 terminalAnomalyCount）。
- `InflightDiagnostics`：门面（enabled 开关、维度 id 压缩、快照）。
- 接线：ChunkScheduler 全部生命周期路径（enqueue/drain/submit/execute/取消/恢复）发射事件；executeOriginal 终态事件在 proxy.complete **之前**落账（清洁断言确定性）。

### 4.3 在途停滞检测 + 事故快照（阶段 4，`diagnostics/`）
- `InflightStallDetector`：**独立 daemon 线程**（每 1 秒；活动任务最后状态变化 >10 秒 → 写快照）。必须独立于 Watchdog 恢复线程——恢复线程在 GameTest 中被测试停启，忙转期间唯一可靠执行者是独立 daemon。判定按"活动任务驻留时长"而非"事件总数冻结"（重试风暴让事件持续流动，冻结信号不可靠）。
- `IncidentRecorder`：`run-server/steadychunks-incidents/<时间戳>-<类型>/`（incident.txt + active-tasks.txt + ring-events.txt + threads.txt），同类 5 分钟限流，只诊断不自愈。
- Watchdog 恢复路径（drain-stall 第一级 / UNSAFE 第二级）也写事故快照。
- **诚实边界**：soak 实测的卡死轮（processUnloads 忙转）全部是 holder 滞留类（任务已终态、无活动任务），在途检测未触发——该类由 jstack 分类覆盖（详见 §5）。

## 5. 第 14 轮 soak 实测结论（50 轮，2026-08-04）

- **结果**：50 轮 = 21 PASS / 29 STALL / **0 FAIL** / 0 TIMEOUT；过率 42%；最佳连过 4；事故快照 78 个（39 drain-stall + 39 unsafe-escalation，全为恢复测试刻意触发）。
- **0 FAIL 意义**：30 个测试 50 轮零断言失败——失败隔离 + 重试风暴排空 + 恢复状态机经受住整夜考验（此前 FAIL 类是最大风险）。
- **卡死分类**（按 Server thread 忙转点）：`ChunkMap.processUnloads` 20 / `tickChunks`(shuffle) 2 / 其他纯原版 7（Level.getChunk、ChunkHolder.isReadyForSaving、BitRandomSource.nextInt、resetStatusCache 等）。**全部为纯原版栈，与 SteadyChunks 代码无交集**。
- **过率波动**：run 间 21%-46% 波动（环境噪声）；5 连过在 200 轮内达成（阶段 2c rounds 36-40），符合 p≈0.4 的几何分布预期。
- **累计统计**：阶段 2c 前 5 个 run 共 200 轮（8+15+17+13+16 PASS）；soak 50 轮 21 PASS。

## 6. 遗留与下轮建议（未做，供参考）

1. **holder 滞留类忙转（soak 29/29 轮）无在途快照**：卡死轮全部是"任务已终态但 holder refCount 滞留"（processUnloads/tickChunks churn），在途任务检测（活动任务驻留）覆盖不到。下轮方向：chunk-map 级信号（holder/refCount 采样，不改原版）或 IncidentRecorder 在 STALL 时补采 Server thread 栈。
2. **soak 过率提升**：忙转触发率与并发生成负载正相关；结构位置/批次顺序不可控。若需提升，可评估预生成/结构距离（改动测试基础设施，需评审）。
3. **工具已知噪音**：soak 轮询的 `Get-Content` null 竞态已在 f872c7f 修复（当前运行实例不受影响）；wrapper 死亡后服务器残留时 `rm latest.log` 偶发 Device busy（下轮可在轮间增加进程回收等待）。

## 7. 关键设计语义（改代码前必读）

- **生命周期两层**：全局（`acceptingTasks` + `lifecycleGeneration`）+ 每维度（`accepting` + `generation`）；ServerLifecycle 对象化（第 11 轮起），迟到 lease 不跨生命周期串改。
- **单一 drainer**：`requestDrain` CAS（drainWip 0→1 者持有），poll 出的任务绝不丢弃。
- **组合 permit**：获取顺序 global → stage，释放顺序相反；lease 在正常/异常/取消路径统一关闭。
- **恢复状态机**：RecoveryBatch（保留任务引用）+ ActiveRecovery（CAPTURING→CAPTURED→MAILBOX_SUBMITTING→WAITING_MAILBOX→ESCALATING→DONE）；提交在独立 recoverySubmitter，第二级 escalate 直接 complete 遗留任务；紧急处置走 emergencyExecutor（虚拟线程）。
- **追踪终态唯一性**：任何路径完成代理 Future 都必须发射 TASK_TERMINAL（executeOriginal 终态在 proxy.complete 前、恢复路径 complete 成功后、取消/拒绝路径同点）；重复终态 = terminalAnomalyCount 异常信号。
- **维度提取**：真实生成路径 `map instanceof ChunkMap → steady$level().dimension()`；NOISE applyStep 时 holder 无 chunk，不可作维度来源。

## 8. 已提交轮次回溯（git log 简读）

- `5fce083`（AGENTS.md 完成态）→ `fbd2797`（soak 结果）→ `0010ab9`（soak 三件套）→ `1fd0655`（在途检测+事故快照）→ `8db1a6a`（环形缓冲追踪）→ `fe9fa59`（测试隔离拆分）→ `b8d7df0`（恢复监督补完）→ `cdce79d`（工作区保护）→ 分支起点
- `721e329` 第 13 轮（main）：恢复状态机窗口 A/B、注册门顺序、27 测试
- 更早轮次见 git log（第 1-12 轮已推送 main）

## 9. 用户偏好与协作约定（摘要）

- 语言：中文；代码注释中文、简洁不省略。
- 每次只做审查清单列出的项，不扩展功能、不接入新模块。
- 完成标准：`compileJava`（junction）→ GameTest 全过（原路径）→ 自动 commit + push（无需确认；commit 信息中文，`fix: 第 N 轮审查修复——…`）。
- 修复偏好：最小侵入、patch 式修改，不整类重写；先读再改。
- 硬性约束（第 14 轮 /goal 延续）：禁止 `taskkill /F /IM java.exe`（记录 PID，仅终止本轮进程树；超时后按日志→jstack→PID/命令行→state→再终止）；不得丢弃工作区；禁止提高生产 NOISE 默认值、用 sleep 修并发测试、注释 @GameTest 获取通过、调高 pending 阈值、关闭断言、修改世界生成、替换 worldgen executor/mailbox、修改 generationRefCount、伪造 releaseClaim、为测试接入新 ChunkStatus、跳过失败后清理、未完整验证就推送 main。
