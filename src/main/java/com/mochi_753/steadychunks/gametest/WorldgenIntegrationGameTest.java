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
 * 真实区块生成集成测试（经 Mixin 拦截链）。
 * <p>
 * 阶段 2：测试拆分自 SchedulerAdmissionGameTest——共享
 * {@link SchedulerGameTestFixture}（统一清理/清洁断言/辅助方法），
 * 不再复制 reset 逻辑。
 */
@GameTestHolder("steadychunks")
public class WorldgenIntegrationGameTest {

    /**
     * 真实新区块生成测试（第 4 轮审查建议第 3 项）：
     * 完整经过 {@code ChunkGenerationTask.scheduleChunkInLayer} → {@code @WrapOperation}
     * 的 Mixin 拦截链，而非直接调用 {@code controlAdmission}。
     * <p>
     * 断言：
     * <ul>
     *   <li>Mixin 拦截计数 > 0（真实 NOISE 任务确实进入调度器）</li>
     *   <li>NOISE permit=1 时真实最大并发 ≤ 1（{@code maxActiveNoise}）</li>
     *   <li>区块达到 FULL；等待队列清空；在途归零；全局 permit 全部释放</li>
     * </ul>
     * 注意：所有断言放在 succeedWhen 回调内，用 GameTestAssertException 驱动逐 Tick
     * 轮询，未完成时不会提前 succeed，也不会阻塞服务器线程。
     */
    @GameTest(template = "empty", batch = "steady_real_gen", timeoutTicks = 1200)
    public void realGenerationShouldCapNoiseConcurrency(GameTestHelper helper) {
        SchedulerGameTestFixture.resetGlobalState();
        GenerationChunkHolder holder = obtainHolder(helper);
        ChunkMap map = helper.getLevel().getChunkSource().chunkMap;

        ChunkScheduler scheduler = ChunkScheduler.getInstance();
        scheduler.setEnabled(true);
        scheduler.setAdmissionPaused(false);
        scheduler.stageLimiter().setResourceLimit(ResourceType.NOISE_HEAVY, 1);
        scheduler.resetDiagnostics();

        // 请求远处未生成的新区块（异步，不阻塞服务器线程）。
        // getChunkFuture 触发完整生成管线，NOISE 阶段经 Mixin 的 @WrapOperation 进入调度器。
        ChunkPos target = new ChunkPos(32, 32);
        CompletableFuture<ChunkResult<ChunkAccess>> future =
                helper.getLevel().getChunkSource().getChunkFuture(target.x, target.z, ChunkStatus.FULL, true);

        helper.succeedWhen(() -> {
            helper.assertTrue(future.isDone(), "远处新区块应完成生成");
            ChunkResult<ChunkAccess> result = future.join();
            helper.assertTrue(result.isSuccess(), "远处新区块应生成成功");
            ChunkAccess chunk = result.orElse(null);
            helper.assertTrue(chunk != null, "区块结果不应为空");
            helper.assertTrue(chunk.getHighestGeneratedStatus().isOrAfter(ChunkStatus.FULL), "区块应达到 FULL");
            // Mixin 应拦截到真实 NOISE 任务（拦截计数 > 0）
            helper.assertTrue(scheduler.mixinInterceptCount() > 0, "Mixin 应拦截真实 NOISE 任务");
            // NOISE permit=1：真实最大并发不得超过 1
            helper.assertTrue(scheduler.maxActiveNoise() <= 1,
                    "NOISE 最大并发应 <= 1，实际: " + scheduler.maxActiveNoise());
            // 生成完成后：等待队列清空、在途归零、全局 permit 全部释放
            helper.assertTrue(scheduler.pendingCount() == 0, "生成完成后等待队列应清空");
            helper.assertTrue(scheduler.inflightCount() == 0, "生成完成后在途任务应归零");
            helper.assertTrue(scheduler.cpuPermitsAvailable() == scheduler.cpuPermitsMax(),
                    "生成完成后全局 permit 应全部释放");
            resetScheduler(scheduler);
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

    /** 重置调度器全局状态（统一清理顺序，见 SchedulerGameTestFixture）。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.resetGlobalState();
    }
}
