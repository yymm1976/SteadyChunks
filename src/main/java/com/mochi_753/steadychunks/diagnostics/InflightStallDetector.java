package com.mochi_753.steadychunks.diagnostics;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightDiagnostics;
import com.mochi_753.steadychunks.diagnostics.inflight.InflightTaskRecord;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;

/**
 * 阶段 4：在途停滞检测线程（独立 daemon，<b>只诊断，不自动修复</b>）。
 * <p>
 * 与 Watchdog 恢复线程解耦：恢复线程在 GameTest 中被测试停启、且两级恢复只
 * 处理队列停滞；在途型卡死（§8：忙转时 Server thread 不 tick、任务滞留）期间，
 * 唯一仍在周期性执行的 JVM 侧线程是独立 daemon。
 * <p>
 * 判定信号：追踪活动任务中存在<b>最后状态变化超过 {@link #STALL_AGE_NANOS}（10 秒）</b>
 * 的任务 → 写事故快照（{@link IncidentRecorder} 内部限流）。按驻留时长而非
 * "事件总数冻结"判定——重试风暴会让事件持续流动，冻结信号不可靠；单个 NOISE
 * 任务正常耗时毫秒级，卡住 10 秒即停滞。只读调度器状态，不参与任何恢复/修复；
 * 服务器启动时经 ModuleBootstrap 启动，测试不停止它。
 */
public final class InflightStallDetector {
    /** 任务最后状态变化超过该时长（纳秒）即判定停滞 */
    private static final long STALL_AGE_NANOS = 10_000_000_000L;
    private static final long TICK_MILLIS = 1000L;

    private static volatile Thread thread;
    private static volatile ChunkScheduler schedulerRef;

    private InflightStallDetector() {
    }

    /** 服务器启动时启动（ServerStarting；调度器单例已就绪）。 */
    public static synchronized void start(ChunkScheduler scheduler) {
        if (thread != null && thread.isAlive()) {
            return;
        }
        schedulerRef = scheduler;
        Thread t = new Thread(InflightStallDetector::loop, "SteadyChunks-InflightStallDetector");
        t.setDaemon(true);
        thread = t;
        t.start();
        SteadyChunks.LOGGER.info("SteadyChunks 在途停滞检测线程已启动（{} 秒驻留判定，只诊断）",
                STALL_AGE_NANOS / 1_000_000_000L);
    }

    /** 服务器停止时停止（ServerStopped）。 */
    public static synchronized void stop() {
        Thread t = thread;
        thread = null;
        schedulerRef = null;
        if (t != null) {
            t.interrupt();
        }
    }

    /** 检测线程是否存活（诊断/测试可见性）。 */
    public static boolean isAlive() {
        Thread t = thread;
        return t != null && t.isAlive();
    }

    private static void loop() {
        boolean stallLogged = false;
        while (thread == Thread.currentThread()) {
            try {
                Thread.sleep(TICK_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ChunkScheduler scheduler = schedulerRef;
            if (scheduler == null || !scheduler.isEnabled() || InflightDiagnostics.activeTaskCount() == 0) {
                stallLogged = false;
                continue;
            }
            long now = System.nanoTime();
            long maxAge = 0;
            for (InflightTaskRecord r : InflightDiagnostics.activeSnapshot()) {
                maxAge = Math.max(maxAge, now - r.lastNanos);
            }
            if (maxAge > STALL_AGE_NANOS) {
                if (!stallLogged) {
                    stallLogged = true;
                    SteadyChunks.LOGGER.warn(
                            "SteadyChunks 在途停滞检测：活动任务最后状态变化 {} 秒（pending={} traceActive={}），"
                                    + "写事故快照（只诊断，不自动修复）",
                            maxAge / 1_000_000_000L, scheduler.pendingCount(),
                            InflightDiagnostics.activeTaskCount());
                    IncidentRecorder.recordInflightStall(scheduler);
                }
            } else {
                stallLogged = false;
            }
        }
    }
}
