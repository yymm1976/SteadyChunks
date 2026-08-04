package com.mochi_753.steadychunks.gametest;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.io.LifecycleCleanupCoordinator;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import com.mochi_753.steadychunks.scheduler.ResourceType;
import com.mochi_753.steadychunks.scheduler.StageLimiter;
import com.mochi_753.steadychunks.scheduler.Watchdog;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Watchdog 两级恢复状态机、停服处置与阻塞场景测试。
 * <p>
 * 阶段 2：测试拆分自 SchedulerAdmissionGameTest——共享
 * {@link SchedulerGameTestFixture}（统一清理/清洁断言/辅助方法），
 * 不再复制 reset 逻辑。
 */
@GameTestHolder("steadychunks")
public class WatchdogRecoveryGameTest {

    /**
     * 第 11 轮 P0 修复验证：两级恢复的第二级（UNSAFE_EMERGENCY）必须能强制完成
     * 第一级遗留任务——第一级把任务移出队列并形成 RecoveryBatch（保留引用），
     * mailbox 停滞（override executor 接收但不运行）时批次任务 proxy 未完成，
     * escalateRecoveryBatch 直接 complete（旧实现第二级对已清空队列找不到任务）。
     */
    @GameTest(template = "empty", batch = "steady_mailbox_escalate", timeoutTicks = 600)
    public void mailboxRecoveryMustEscalateToUnsafe(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务：admissionPaused 保证入队且 drainOwnedPass 跳过（不依赖 permit 状态）。
        // paused 保持到 beginMailboxRecovery 之后——防止并发 drain（worldgen 完成回调
        // 触发 requestDrain）在批次形成前 poll 走任务导致空批次。
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：接收但不运行（模拟 mailbox 停滞）
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });

        // 第一级：capture（任务移出队列）→ 提交（override 接收但不运行）。
        // 第 13 轮起 beginMailboxRecovery 拆为 capture + submit（批次发布先于提交）
        var batch = scheduler.captureRecoveryBatch();
        int rejected = scheduler.submitRecoveryBatch(batch);
        scheduler.setAdmissionPaused(false);
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(rejected == 0, "override 接收不拒绝，不应有拒绝计数");

        // 第二级：对批次内未完成任务直接 complete（UNSAFE_EMERGENCY）
        int unsafe = scheduler.escalateRecoveryBatch(batch);
        helper.assertTrue(unsafe == 1, "第二级应强制完成 1 个遗留任务");
        helper.assertTrue(queued.isDone(), "批次任务应被强制完成");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        helper.succeed();
        });
    }


    /**
     * 第 11 轮 P1 修复验证：mailbox 立即拒绝（execute 抛异常）的任务直接完成，
     * 必须计入 rejectedAndCompletedUnsafely（旧实现漏计 unsafe 指标）。
     */
    @GameTest(template = "empty", batch = "steady_mailbox_reject", timeoutTicks = 600)
    public void mailboxRejectionMustCountUnsafeRecovery(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（admissionPaused 保证入队且不被 drain；paused 保持到
        // beginMailboxRecovery 之后，防并发 drain 提前 poll 走任务）
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：立即拒绝（mailbox 停滞/关闭）
        scheduler.setResumeExecutorOverride(command -> {
            throw new java.util.concurrent.RejectedExecutionException("mailbox closed");
        });

        var batch = scheduler.captureRecoveryBatch();
        int rejected = scheduler.submitRecoveryBatch(batch);
        scheduler.setAdmissionPaused(false);
        helper.assertTrue(rejected == 1,
                "mailbox 拒绝的任务应计入拒绝计数（实际=" + rejected + "）");
        helper.assertTrue(queued.isDone(), "被拒绝的任务应直接完成（不永久挂起）");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        helper.succeed();
        });
    }


    /**
     * 第 12 轮 P1 修复验证：Watchdog 自动恢复状态机端到端——不再直接调用
     * Scheduler API，而是经 {@link Watchdog#checkDrainStallForTest}（注入时钟）
     * 驱动完整状态机：
     * 停滞 3 次 → 第一级形成批次（activeRecoveryBatch 保存、pending 归零）
     * → deadline 前不升级 → deadline 后第二级直接完成（unsafe +1）→ 批次清空。
     * <p>
     * 手法：任务全程保持 paused（drain 永不抢走任务，队列快照稳定——取消暂停会
     * 同步触发 requestDrain 立即抢走任务，第 1 版因此失败）；停滞检测的 paused
     * 排除项经 {@code setStallCheckIgnorePausedForTest} 临时忽略，先验证排除项
     * 本身（不置开关时 3 次检测不得触发），再验证触发路径。
     * <p>
     * 指标隔离：测试前后 {@code resetRecoveryMetrics()}——刻意触发的状态机恢复
     * 不污染跨批次累计值（正确性硬门槛 steady_watchdog_disabled 断言指标为 0）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_state_machine", timeoutTicks = 600)
    public void watchdogStateMachineMustRecoverEndToEnd(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务并保持 paused（drain 永不抢走任务）
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1,
                "paused 时任务应入队等待（实际=" + scheduler.pendingCount() + "）");

        // override：接收但不运行（模拟 mailbox 停滞）
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        // 真实线程先睡 1 秒，测试在其睡眠窗口内驱动状态机（不竞争）
        wd.startRecoveryThread(scheduler);

        // 排除项验证：未置测试开关时，paused 队列 3 次检测不得触发恢复
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "paused 排除项生效：3 次检测不得触发恢复");
        helper.assertTrue(!queued.isDone(), "排除项阶段任务不应被完成");
        helper.assertTrue(scheduler.pendingCount() == 1, "排除项阶段任务应仍在队列");

        // 置测试开关（忽略 paused 排除项）→ 停滞 3 次 → 第一级触发
        wd.setStallCheckIgnorePausedForTest(true);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);

        helper.assertTrue(wd.hasActiveRecoveryBatch(), "停滞 3 次后第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(wd.totalRecoveries() == 1, "第一级应计入 totalRecoveries");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "deadline 前不得升级 unsafe");

        // deadline 前：批次保持，不升级
        wd.checkDrainStallForTest(scheduler, wd.activeRecoveryBatchDeadlineNanos() - 1);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "deadline 前批次应保持活动");
        helper.assertTrue(!queued.isDone(), "deadline 前任务不应被强制完成");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "deadline 前 unsafe 不得增加");

        // deadline 后：第二级异步强制完成（UNSAFE_EMERGENCY）
        // 审查 P0-3 适配：升级已异步化（emergency 虚拟线程 per-task），
        // 终态与指标断言必须轮询
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        awaitTrue(() -> queued.isDone(), "deadline 后批次任务应被强制完成");
        helper.assertTrue(!queued.join().isSuccess(), "强制完成应为 error result");
        awaitTrue(() -> wd.totalUnsafeRecoveries() == 1,
                "第二级应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");
        awaitTrue(() -> !wd.hasActiveRecoveryBatch(), "批次终态后应清空");
        helper.assertTrue(scheduler.pendingCount() == 0, "批次处理后队列应为空");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }


    /**
     * 第 10 轮评审要求：正确性测试禁用 Watchdog 并断言恢复数为零。
     * <p>
     * 本测试停止恢复线程（后续批次在无恢复保护下运行——硬门槛：调度链必须
     * 不依赖 Watchdog 自愈也能通过），并断言此前从未发生过自动恢复
     * （totalRecoveries==0 且 totalUnsafeRecoveries==0），防止 Watchdog
     * 掩盖核心调度链竞态。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_disabled", timeoutTicks = 600)
    public void watchdogMustNotRecoverDuringCorrectnessTests(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        Watchdog wd = Watchdog.getInstance();
        // 此前任何批次的自动恢复都会使此断言失败（暴露调度链竞态）
        helper.assertTrue(wd.totalRecoveries() == 0,
                "正确性测试期间不得发生任何自动恢复（Watchdog 掩盖竞态）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "正确性测试期间不得发生 UNSAFE_EMERGENCY 恢复");
        // 禁用恢复线程：后续批次在无 Watchdog 保护下运行（硬门槛）
        wd.stopRecoveryThread();
        helper.succeed();
        });
    }


    /**
     * 第 11 轮 P1 修复验证：stop 后立即 start（不等待旧线程退出）也必须留下
     * 一条存活恢复线程——线程代数让旧线程退出、新线程立即接管（旧实现 start
     * 看到旧线程仍 alive 直接 return，新服务器无线程）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_immediate", timeoutTicks = 600)
    public void watchdogImmediateStopStartMustLeaveLiveThread(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        Watchdog wd = Watchdog.getInstance();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 连续 stop/start，不等待旧线程退出（模拟生产事件中停服后立即开新世界）
        wd.stopRecoveryThread();
        wd.startRecoveryThread(scheduler);
        wd.stopRecoveryThread();
        wd.startRecoveryThread(scheduler);

        helper.assertTrue(wd.isRecoveryThreadAlive(),
                "连续 stop/start 后必须存在一条存活恢复线程（P1 竞态）");

        // 收尾：停止
        wd.stopRecoveryThread();
        helper.succeed();
        });
    }


    /**
     * 第 10 轮 P0-4 修复验证：Watchdog 恢复线程支持多服务器生命周期——
     * stop 后再次 start 必须创建新线程（旧实现 recoveryStarted 永久为 true，
     * 集成服务器第二个世界起恢复线程不再启动）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_lifecycle", timeoutTicks = 600)
    public void watchdogRestartsAcrossServerLifecycle(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        Watchdog wd = Watchdog.getInstance();
        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 先确保停止（ServerStarting 可能已启动恢复线程）
        wd.stopRecoveryThread();
        for (int i = 0; i < 100 && wd.isRecoveryThreadAlive(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        helper.assertTrue(!wd.isRecoveryThreadAlive(), "stop 后线程应退出");

        // 重启（模拟第二个服务器生命周期）
        wd.startRecoveryThread(scheduler);
        helper.assertTrue(wd.isRecoveryThreadAlive(), "重启后线程应存活（P0-4）");

        // 再次停止收尾
        wd.stopRecoveryThread();
        for (int i = 0; i < 100 && wd.isRecoveryThreadAlive(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        helper.assertTrue(!wd.isRecoveryThreadAlive(), "再次 stop 后线程应退出");
        helper.succeed();
        });
    }


    /**
     * 第 12 轮 P0 修复验证：停服必须处置活动恢复批次，禁止静默遗弃。
     * <p>
     * 旧实现 stopRecoveryThread 直接 {@code activeRecoveryBatch = null}：批次任务已
     * 不在 pending 队列，随后 closeForShutdown 看不到它们 → proxy 永不终态 →
     * TaskRegistration 不关闭 → 旧 ServerLifecycle 永久残留计数。
     * <p>
     * 新实现：stop 锁内脱离批次、锁外同步强制完成（UNSAFE，计入独立的
     * {@code totalUnsafeShutdownRecoveries}，不混入运行期指标）；随后
     * onServerShutdown/onServerStopped 无残留可见。
     * 批次形成手法同 {@link #watchdogStateMachineMustRecoverEndToEnd}（paused +
     * 测试开关）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_shutdown_batch", timeoutTicks = 600)
    public void shutdownMustTerminateActiveRecoveryBatch(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持，drain 不抢）→ mailbox 停滞 override → 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.startRecoveryThread(scheduler);

        // 测试开关 + 停滞 3 次 → 第一级形成活动批次（mailbox 停滞，proxy 未完成）
        wd.setStallCheckIgnorePausedForTest(true);
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时批次任务 proxy 不应完成");
        helper.assertTrue(coordinator.globalTaskCount() == 1, "批次任务仍持有注册 lease");

        // P0 修复验证：停服（stopRecoveryThread）必须处置活动批次——第 14 轮起
        // 为异步 emergency 分派（停服不被回调阻塞），终态/指标轮询确认
        wd.stopRecoveryThread();
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "停服后活动批次应已脱离（同步）");
        awaitTrue(() -> queued.isDone(), "停服后批次任务 proxy 必须终态");
        helper.assertTrue(!queued.join().isSuccess(), "停服处置应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "停服处置后 registration 应归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置应计入独立指标 totalUnsafeShutdownRecoveries（实际="
                        + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "停服处置不得混入运行期 UNSAFE_EMERGENCY 指标");

        // 后续停服流程：closeForShutdown 看不到批次任务也不得泄漏
        wd.setStallCheckIgnorePausedForTest(false);
        coordinator.onServerShutdown();
        helper.assertTrue(scheduler.pendingCount() == 0, "停服清理后等待队列应清空");
        helper.assertTrue(coordinator.globalTaskCount() == 0, "停服清理后无残留任务计数");
        coordinator.onServerStopped();
        coordinator.onServerStart(); // 恢复接收，避免污染后续批次
        helper.assertTrue(!coordinator.isShutdownMode(), "恢复后应退出停服模式");

        // 清理
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }


    /**
     * 第 13 轮 P0 修复验证（窗口 A）：mailbox 提交调用本身阻塞时，批次必须已对
     * Watchdog 可见——捕获与发布零间隙，提交在批次发布之后锁外执行。
     * <p>
     * 旧实现 beginMailboxRecovery 先逐个 execute 全部返回后才形成批次：
     * executor 阻塞时任务已离队但批次未发布，停服/第二级都看不到（任务所有权
     * 不可见窗口）。
     * <p>
     * 流程：提交线程驱动停滞 3 次 → 锁内捕获+发布 → 锁外 submit → execute 阻塞
     * → 断言批次可见 → stopRecoveryThread 处置 → proxy 终态 + 计数归零。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_blocking_submit", timeoutTicks = 600)
    public void blockingMailboxSubmissionMustRemainRecoverable(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持，drain 不抢）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);

        // override：execute 进入后阻塞（模拟 mailbox 提交调用本身停滞，
        // 而非"接收但不运行"——第 12 轮测试未覆盖此形态）
        CountDownLatch enteredExecute = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        scheduler.setResumeExecutorOverride(command -> {
            enteredExecute.countDown();
            try {
                releaseExecute.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // 放行后不再运行 command（任务已由停服处置，幂等）
        });
        wd.setStallCheckIgnorePausedForTest(true);

        // 提交线程驱动第一级：capture+发布（锁内）→ submit（锁外 execute 阻塞）
        ExecutorService pool = Executors.newSingleThreadExecutor();
        long now = System.nanoTime();
        pool.submit(() -> {
            wd.checkDrainStallForTest(scheduler, now);
            wd.checkDrainStallForTest(scheduler, now);
            wd.checkDrainStallForTest(scheduler, now);
        });

        // 等 execute 被调用（提交线程已进入阻塞）——窗口 A 修复验证：
        // 批次必须已在 execute 之前发布
        try {
            helper.assertTrue(enteredExecute.await(5, TimeUnit.SECONDS),
                    "execute 应被调用（提交线程阻塞在 override 内）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("等待 execute 被中断");
        }
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "提交阻塞期间批次必须已发布（窗口 A）");
        helper.assertTrue(scheduler.pendingCount() == 0, "任务已移出队列");
        helper.assertTrue(!queued.isDone(), "proxy 未完成（execute 尚未放行）");
        helper.assertTrue(coordinator.globalTaskCount() == 1, "批次任务仍持有注册 lease");

        // 停服：stopRecoveryThread 必须能看到批次并处置（任意 phase 幂等；
        // 第 14 轮起异步 emergency 分派——轮询终态）
        wd.stopRecoveryThread();
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "批次应已脱离（同步）");
        awaitTrue(() -> queued.isDone(), "停服后 proxy 必须终态");
        helper.assertTrue(!queued.join().isSuccess(), "应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "registration 归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置计入 shutdown 指标（实际=" + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0,
                "停服处置不得混入运行期指标");

        // 放行阻塞的 execute：提交线程完成阶段 5（批次已被停服处置 → 仅补指标）
        releaseExecute.countDown();
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }


    /**
     * 第 13 轮 P0 修复验证（窗口 B）：第二级升级期间批次必须保持可见——第一个
     * proxy 的同步回调阻塞时，activeRecovery 不得清空，其他线程可幂等完成剩余
     * 任务（旧实现先 {@code activeRecoveryBatch = null} 再锁外 escalate：
     * 第一个 complete 的回调阻塞时，剩余任务只存在于 Watchdog 线程局部变量，
     * 停服/第二线程都找不到）。
     * <p>
     * 流程：形成 2 任务批次（WAITING_MAILBOX）→ 驱动线程 deadline 升级
     * （任务 1 complete 触发阻塞回调，驱动线程卡住）→ 断言批次仍可见 →
     * 第二线程幂等完成任务 2 → 全部终态后清空 → 放行回调 → 指标归并。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_blocking_escalate", timeoutTicks = 600)
    public void blockingCompletionCallbackMustKeepBatchVisible(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 2 个任务（paused 保持）→ 启动恢复线程 → override 接收但不运行
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> t1 = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        CompletableFuture<ChunkResult<ChunkAccess>> t2 = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 2, "paused 时 2 个任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);

        // 任务 1 的同步回调阻塞（模拟下游回调卡住）
        CountDownLatch callbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        t1.whenComplete((result, ex) -> {
            callbackBlocked.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 第一级：停滞 3 次 → 批次形成（2 任务，WAITING_MAILBOX）
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        helper.assertTrue(!t1.isDone() && !t2.isDone(), "mailbox 停滞时任务 proxy 不应完成");

        // 驱动线程 deadline 升级：任务 1 的 complete 触发阻塞回调 → 驱动线程卡住
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.submit(() -> wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L));
        try {
            helper.assertTrue(callbackBlocked.await(5, TimeUnit.SECONDS),
                    "任务 1 的同步回调应被触发（驱动线程卡在 escalate 内）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.fail("等待回调被中断");
        }

        // 窗口 B 修复验证：升级期间批次必须仍可见（未清空）
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "升级期间批次必须保持发布（窗口 B）");

        // 第二线程幂等完成剩余任务（评审：第二线程可以继续幂等完成其余任务）
        // 审查 P0-3 适配：运行期升级已异步化——完成发生在 emergency 虚拟线程，
        // 终态断言必须轮询（同步断言与异步分派存在竞态）
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        awaitTrue(() -> t2.isDone(), "第二线程应完成剩余任务（幂等）");
        helper.assertTrue(!t2.join().isSuccess(), "应为 error result");
        awaitTrue(() -> !wd.hasActiveRecoveryBatch(), "全部终态后批次应清空");

        // 放行任务 1 回调 → 驱动线程 escalate 返回（complete 幂等，指标归并）
        releaseCallback.countDown();
        pool.shutdown();
        try {
            pool.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(t1.isDone(), "任务 1 应终态");
        helper.assertTrue(coordinator.globalTaskCount() == 0, "registration 全部归零");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 2,
                "两个任务都应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }


    /**
     * 第 14 轮 P0-1 修复验证：阻塞的 mailbox 提交不得阻断运行期自动二级恢复。
     * <p>
     * 旧实现提交由唯一监督线程同步执行——execute 阻塞时监督线程被卡，
     * MAILBOX_SUBMITTING 分支"不推进"，两秒 deadline 永远不检查 → 运行期
     * 自动 UNSAFE_EMERGENCY 永不发生（只能在停服回收）。
     * <p>
     * 新实现：提交移出监督线程（独立 recoverySubmitter），CAPTURED/
     * MAILBOX_SUBMITTING/WAITING_MAILBOX 统一允许 deadline 超时升级。
     * 本测试<b>不调用 stopRecoveryThread</b>：execute 永久阻塞 → deadline 后
     * 第二级必须自动强制完成（unsafe==1）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_submit_escalate", timeoutTicks = 600)
    public void blockingMailboxSubmissionMustEscalateWithoutShutdown(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);

        // override：execute 永久阻塞（提交在独立线程——监督线程不受影响）
        CountDownLatch enteredExecute = new CountDownLatch(1);
        CountDownLatch releaseExecute = new CountDownLatch(1);
        scheduler.setResumeExecutorOverride(command -> {
            enteredExecute.countDown();
            try {
                releaseExecute.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        wd.setStallCheckIgnorePausedForTest(true);

        // 形成批次：提交分派到独立线程并阻塞在 execute
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(scheduler.pendingCount() == 0, "第一级后队列应清空");
        try {
            helper.assertTrue(enteredExecute.await(5, TimeUnit.SECONDS),
                    "execute 应由独立提交线程调用并阻塞");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(!queued.isDone(), "提交阻塞时 proxy 不应完成");

        // P0-1 修复验证：不调用 stopRecoveryThread——deadline 后运行期自动第二级
        wd.checkDrainStallForTest(scheduler, System.nanoTime() + 3_000_000_000L);
        awaitTrue(() -> queued.isDone(), "deadline 后批次任务应被第二级强制完成（不依赖提交完成）");
        helper.assertTrue(!queued.join().isSuccess(), "强制完成应为 error result");
        awaitTrue(() -> wd.totalUnsafeRecoveries() == 1,
                "第二级应计入 totalUnsafeRecoveries（实际=" + wd.totalUnsafeRecoveries() + "）");
        awaitTrue(() -> !wd.hasActiveRecoveryBatch(), "批次终态后应清空");

        // 放行提交线程（幂等：任务已由第二级完成）
        releaseExecute.countDown();

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        wd.stopRecoveryThread();
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }


    /**
     * 第 14 轮 P0-2 修复验证：停服仍可发生在"判定恢复"与"捕获发布"之间——
     * 旧线程此时不得捕获并发布新批次。
     * <p>
     * 旧实现阶段 1 锁内判定 startFirstLevel 后释放锁，阶段 3 重新取锁无条件
     * 捕获发布：两个锁区间之间 stopRecoveryThread 可能先执行（看不到批次），
     * 旧线程随后仍发布 → 停服后任务被捕获而无人处置；快速 stop/start 时旧线程
     * 还可能把旧服务器批次发布到新 Watchdog。
     * <p>
     * 新实现：阶段 3 捕获前重新验证 stopRecovery + 恢复代数 + 已有批次。
     * 探针（preCaptureProbe）在"判定与捕获之间"暂停驱动线程，主线程执行
     * stop→start 后放行——旧线程必须放弃（任务仍在队列）。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_stop_start_publish", timeoutTicks = 600)
    public void stoppedWatchdogMustNotPublishNewBatch(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);
        ExecutorService pool = null;
        try {
            // 探针：阶段 1 判定后、阶段 3 重新取锁前阻塞驱动线程（仅触发时调用一次）
            CountDownLatch probeReached = new CountDownLatch(1);
            CountDownLatch releaseProbe = new CountDownLatch(1);
            wd.setPreCaptureProbe(() -> {
                probeReached.countDown();
                try {
                    releaseProbe.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            // 驱动线程：第 3 次调用触发第一级判定 → 停在探针
            pool = Executors.newSingleThreadExecutor();
            long now = System.nanoTime();
            AtomicReference<Throwable> driverFailure = new AtomicReference<>();
            pool.submit(() -> {
                try {
                    wd.checkDrainStallForTest(scheduler, now);
                    wd.checkDrainStallForTest(scheduler, now);
                    wd.checkDrainStallForTest(scheduler, now);
                } catch (Throwable t) {
                    driverFailure.set(t);
                }
            });
            try {
                helper.assertTrue(probeReached.await(5, TimeUnit.SECONDS),
                        "探针应被触发（判定与捕获之间）");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // 停服 + 立即重启（评审场景：快速 stop/start——旧线程代数已过期）
            wd.stopRecoveryThread();
            wd.startRecoveryThread(scheduler);
            // 第 14 轮修复：stop→start 的新线程 1 秒后醒来——立即清 paused 排除
            // 开关，新线程检测时恢复"paused 不触发"语义（探针等待可能使测试体
            // 超过 1 秒，否则新线程会触发第一级 capture 并提交到永不运行的
            // override，代理永不完成残留——实测引发 processUnloads 忙转卡死）
            wd.setStallCheckIgnorePausedForTest(false);

            // 放行 → 旧驱动线程阶段 3 重新验证：代数不匹配 → 不得捕获发布
            releaseProbe.countDown();
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            helper.assertTrue(driverFailure.get() == null, "驱动线程不应异常");
            helper.assertTrue(!wd.hasActiveRecoveryBatch(), "stop/start 后旧线程不得发布新批次（P0-2）");
            helper.assertTrue(scheduler.pendingCount() == 1, "任务应仍在队列（未被捕获）");
        } finally {
            // 第 14 轮防御：断言失败中止也不得残留 override/开关/探针/恢复线程
            if (pool != null) {
                pool.shutdownNow();
            }
            wd.setStallCheckIgnorePausedForTest(false);
            scheduler.setResumeExecutorOverride(null);
            wd.stopRecoveryThread();
            // 第 14 轮根因修复：先清队列再复位——resetScheduler 的取消暂停会同步
            // 触发 requestDrain，队列残留任务（本测试故意保留的验证任务）会被
            // drain 抢走并以永不完成的原操作执行（inflight++ + 占 NOISE permit
            // 永久残留——实测 clear_all noiseAvail=0 inflight=1，级联 10 连挂）
            scheduler.clearAll(new IllegalStateException("GameTest cleanup"));
            resetScheduler(scheduler);
            wd.resetRecoveryMetrics();
        }
        helper.succeed();
        });
    }


    /**
     * 第 14 轮 P0-3 修复验证：停服不得被同步 Future 回调无限阻塞。
     * <p>
     * 旧实现 stopRecoveryThread 在 ServerStopping 调用线程同步 escalate——
     * proxy.complete 同步运行挂载的回调，阻塞回调会卡住 ServerStopping，
     * finishServerShutdown 无法执行（与"不在 Server Thread 等待"停服目标冲突）。
     * <p>
     * 新实现：批次交给 emergency 分派器（每任务独立虚拟线程），stop 立即返回。
     * 本测试：任务回调阻塞 → stop 后流程必须能继续（finishServerShutdown 等价物）
     * → 放行回调 → 终态/指标最终归零。
     */
    @GameTest(template = "empty", batch = "steady_watchdog_stop_nonblocking", timeoutTicks = 600)
    public void stopRecoveryThreadMustNotBlockOnCompletionCallback(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        Watchdog wd = Watchdog.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        wd.stopRecoveryThread();
        wd.resetRecoveryMetrics();

        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 保持）→ 启动恢复线程
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        wd.startRecoveryThread(scheduler);
        scheduler.setResumeExecutorOverride(command -> { /* 永不运行 */ });
        wd.setStallCheckIgnorePausedForTest(true);

        // 任务回调阻塞（模拟下游回调卡住）
        CountDownLatch callbackBlocked = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        queued.whenComplete((result, ex) -> {
            callbackBlocked.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 形成批次
        long now = System.nanoTime();
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        wd.checkDrainStallForTest(scheduler, now);
        helper.assertTrue(wd.hasActiveRecoveryBatch(), "第一级应形成活动批次");
        helper.assertTrue(!queued.isDone(), "mailbox 停滞时 proxy 不应完成");

        // P0-3 修复验证：stop 必须立即返回（异步 emergency 分派）——
        // 若同步 complete，测试线程会卡在回调 await 而无法执行后续断言
        wd.stopRecoveryThread();
        try {
            helper.assertTrue(callbackBlocked.await(5, TimeUnit.SECONDS),
                    "emergency 虚拟线程应已触发回调（stop 不阻塞，回调在独立线程）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        helper.assertTrue(!wd.hasActiveRecoveryBatch(), "批次应已脱离（同步）");

        // 回调仍阻塞期间：停服后续流程必须能继续（finishServerShutdown 等价物）
        coordinator.onServerShutdown();
        helper.assertTrue(scheduler.pendingCount() == 0, "停服清理后等待队列应清空");

        // 放行回调 → 虚拟线程完成 → 终态/指标归零
        releaseCallback.countDown();
        awaitTrue(() -> queued.isDone(), "任务应终态");
        helper.assertTrue(!queued.join().isSuccess(), "停服处置应为 error result");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "registration 应归零");
        awaitTrue(() -> wd.totalUnsafeShutdownRecoveries() == 1,
                "停服处置应计入 shutdown 指标（实际=" + wd.totalUnsafeShutdownRecoveries() + "）");
        helper.assertTrue(wd.totalUnsafeRecoveries() == 0, "不得混入运行期指标");
        coordinator.onServerStopped();
        coordinator.onServerStart(); // 恢复接收，避免污染后续批次
        helper.assertTrue(!coordinator.isShutdownMode(), "恢复后应退出停服模式");

        // 清理
        wd.setStallCheckIgnorePausedForTest(false);
        scheduler.setResumeExecutorOverride(null);
        resetScheduler(scheduler);
        wd.resetRecoveryMetrics();
        helper.succeed();
        });
    }

    /**
     * 审查 P0-1（第 2 轮）验证：requeueRecoveryBatch 的 check-then-offer 竞态修复。
     * <p>
     * 时序（确定性，经 requeueProbeHook 暂停）：requeue 完成第一次生命周期校验
     * （通过）→ 探针执行 closeForShutdown（清队列 + 停止接收 + 代数递增；任务尚
     * 未入队、清队列扫不到）→ 释放 requeue 的 offer → 任务以旧生命周期入队。
     * 若无 offer 后二次校验，任务将驻留无人消费的队列成为孤儿（pending 恒为 1、
     * proxy 永不终态、registration 不归零）。修复后：二次校验捕获 → remove 成功
     * → error 完成 → 终态绑定自动关闭 lease。
     */
    @GameTest(template = "empty", batch = "steady_requeue_race", timeoutTicks = 600)
    public void requeueMustNotPublishAfterShutdownClear(GameTestHelper helper) {
        SchedulerGameTestFixture.runIsolated(helper, () -> {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;
        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        LifecycleCleanupCoordinator coordinator = LifecycleCleanupCoordinator.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 8);
        waitForQueueDrain(scheduler);

        // 入队 1 个任务（paused 防并发 drain 抢走）→ 捕获进批次
        scheduler.setAdmissionPaused(true);
        CompletableFuture<ChunkResult<ChunkAccess>> queued = scheduler.controlAdmission(
                ChunkStatus.NOISE, false, map, holder,
                () -> new CompletableFuture<ChunkResult<ChunkAccess>>());
        helper.assertTrue(scheduler.pendingCount() == 1, "paused 时任务应入队等待");
        helper.assertTrue(coordinator.globalTaskCount() == 1, "任务应持有注册 lease");
        var batch = scheduler.captureRecoveryBatch();
        helper.assertTrue(scheduler.pendingCount() == 0, "捕获后队列应清空");
        helper.assertTrue(!queued.isDone(), "捕获不得完成 proxy");

        // 探针精确复现竞态窗口：requeue 第一次校验通过后、offer 前停服
        scheduler.setRequeueProbeHook(() ->
                scheduler.closeForShutdown(new IllegalStateException("test shutdown")));
        scheduler.requeueRecoveryBatch(batch);
        scheduler.setRequeueProbeHook(null);

        // 断言：任务不得成为孤儿——pending 归零、proxy 终态、registration 归零
        helper.assertTrue(scheduler.pendingCount() == 0,
                "requeue 后 pending 必须为 0（不得驻留无人消费的队列，实际=" + scheduler.pendingCount() + "）");
        awaitTrue(() -> queued.isDone(), "任务 proxy 必须终态");
        awaitTrue(() -> coordinator.globalTaskCount() == 0, "registration 必须归零");

        // 清理
        resetScheduler(scheduler);
        helper.succeed();
        });
    }

    // ---- 阶段 2：统一 fixture 委托（辅助方法与清理由 SchedulerGameTestFixture 提供） ----
    private static GenerationChunkHolder obtainHolder(GameTestHelper helper) {
        return SchedulerGameTestFixture.obtainHolder(helper);
    }

    private static GenerationChunkHolder obtainHolderForLevel(ServerLevel level) {
        return SchedulerGameTestFixture.obtainHolderForLevel(level);
    }

    private static void waitForQueueDrain(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.waitForQueueDrain(scheduler);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        SchedulerGameTestFixture.awaitTrue(condition, message);
    }

    /** 重置调度器全局状态（统一清理 + 清洁硬断言 + 追踪复位，见 SchedulerGameTestFixture）。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.forceCleanupAfterFailure();
    }
}
