package com.mochi_753.steadychunks.telemetry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 单线程的工作栈状态，借鉴 C2ME {@code ThreadState}。
 * <p>
 * 读写策略：
 * <ul>
 *   <li>正常 push/pop 由本线程调用，使用 {@link ArrayDeque} 即可（线程本地）</li>
 *   <li>crash report 时可能由 watchdog 线程读取，故用 {@link ReentrantReadWriteLock} 保护快照</li>
 *   <li>读路径（{@link #snapshotStack}）加读锁，写路径（{@link #begin}/{@link #end}）加写锁</li>
 * </ul>
 */
public final class ThreadState {
    private final Thread thread;
    private final ArrayDeque<RunningEntry> stack = new ArrayDeque<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    ThreadState(Thread thread) {
        this.thread = thread;
    }

    void begin(RunningWork work, long startNanos) {
        lock.writeLock().lock();
        try {
            stack.push(new RunningEntry(work, startNanos));
        } finally {
            lock.writeLock().unlock();
        }
    }

    void end() {
        long endNanos = System.nanoTime();
        lock.writeLock().lock();
        try {
            if (stack.isEmpty()) {
                return;
            }
            RunningEntry entry = stack.pop();
            long durationNanos = endNanos - entry.startNanos;
            // 转发到对应的指标收集器（由 ChunkFlightRecorder 注册的 listener 处理）
            MetricsDispatch.onWorkCompleted(entry.work, durationNanos, thread);
        } finally {
            lock.writeLock().unlock();
        }
    }

    List<String> snapshotStack() {
        lock.readLock().lock();
        try {
            List<String> out = new ArrayList<>(stack.size());
            for (RunningEntry e : stack) {
                out.add(e.work.describe());
            }
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    private record RunningEntry(RunningWork work, long startNanos) {}
}
