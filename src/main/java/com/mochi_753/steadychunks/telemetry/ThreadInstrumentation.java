package com.mochi_753.steadychunks.telemetry;

import com.mochi_753.steadychunks.SteadyChunks;

import java.io.Closeable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 线程级计时仪表盘，借鉴 C2ME {@code ThreadInstrumentation} 的设计：
 * <ul>
 *   <li>{@link ConcurrentHashMap} 持有线程→状态映射，{@link ThreadLocal} 加速本线程读取</li>
 *   <li>守护线程定期清理死亡线程的条目</li>
 *   <li>{@link #begin(RunningWork)} 返回 {@link Closeable}，支持 try-with-resources 作用域计时</li>
 *   <li>读路径无锁（{@link ThreadLocal#get}），写路径仅线程本地 {@link ArrayDeque}</li>
 * </ul>
 * <p>
 * 调用约定：诊断关闭时调用方应通过 {@code if (ThreadInstrumentation.ENABLED)} 短路，
 * 避免构造 {@link RunningWork} 对象的开销。{@link #ENABLED} 为 volatile 全局开关。
 */
public final class ThreadInstrumentation {
    /** 全局开关，由 {@code ChunkFlightRecorder#setEnabled} 设置。读取无锁。 */
    public static volatile boolean ENABLED = false;

    private static final ConcurrentHashMap<Thread, ThreadState> THREAD_STATES = new ConcurrentHashMap<>();
    private static final ThreadLocal<ThreadState> LOCAL = ThreadLocal.withInitial(ThreadInstrumentation::createState);
    private static final ScheduledExecutorService CLEANER;

    static {
        CLEANER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SteadyChunks-ThreadInstrumentation-Cleaner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        // 每 30 秒清理一次死亡线程
        CLEANER.scheduleAtFixedRate(ThreadInstrumentation::cleanDeadThreads, 30, 30, TimeUnit.SECONDS);
    }

    private ThreadInstrumentation() {
    }

    private static ThreadState createState() {
        ThreadState state = new ThreadState(Thread.currentThread());
        THREAD_STATES.put(Thread.currentThread(), state);
        return state;
    }

    /**
     * 开始一项工作，返回 {@link Closeable}，应在 try-with-resources 中使用。
     * <pre>{@code
     * try (var ignored = ThreadInstrumentation.begin(new StageWork(pos, status))) {
     *     // 被计时的代码
     * }
     * }</pre>
     */
    public static Closeable begin(RunningWork work) {
        if (!ENABLED) {
            return () -> {};
        }
        ThreadState state = LOCAL.get();
        state.begin(work, System.nanoTime());
        return state::end;
    }

    /**
     * 获取当前线程的工作栈快照（最内层在最后），供 crash report 输出。
     */
    public static List<String> currentWorkStack(Thread thread) {
        ThreadState state = THREAD_STATES.get(thread);
        return state == null ? List.of() : state.snapshotStack();
    }

    /**
     * 输出指定线程的当前工作栈，用于 watchdog/crash report。
     */
    public static String printState(Thread thread) {
        List<String> stack = currentWorkStack(thread);
        if (stack.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[SteadyChunks] Thread ").append(thread.getName()).append(" current work:\n");
        for (int i = stack.size() - 1; i >= 0; i--) {
            sb.append("  - ").append(stack.get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 返回所有线程的状态映射快照，用于导出报告。
     */
    public static Map<Thread, ThreadState> entrySet() {
        return Map.copyOf(THREAD_STATES);
    }

    private static void cleanDeadThreads() {
        try {
            var it = THREAD_STATES.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                if (!entry.getKey().isAlive()) {
                    it.remove();
                }
            }
        } catch (Throwable t) {
            SteadyChunks.LOGGER.warn("SteadyChunks 线程状态清理失败", t);
        }
    }
}
