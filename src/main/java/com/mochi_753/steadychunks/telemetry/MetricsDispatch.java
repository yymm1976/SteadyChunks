package com.mochi_753.steadychunks.telemetry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/**
 * 指标分发枢纽：{@link ThreadState#end()} 完成计时后，把 (work, durationNanos) 派发给已注册的 listener。
 * <p>
 * 用 {@link ConcurrentMap} + Class 作为 key，避免 if-else 链与 instanceof 检查。
 * 每个 work 类型对应一个 listener，listener 内部更新对应的 {@code LongAdder} 或分桶。
 */
public final class MetricsDispatch {
    private static final ConcurrentMap<Class<? extends RunningWork>, BiConsumer<RunningWork, Long>> LISTENERS = new ConcurrentHashMap<>();

    private MetricsDispatch() {
    }

    /**
     * 注册某 work 类型完成时的回调，接收 work 实例与耗时（纳秒）。
     */
    public static <T extends RunningWork> void register(Class<T> workType, BiConsumer<T, Long> onCompleted) {
        @SuppressWarnings("unchecked")
        BiConsumer<RunningWork, Long> cast = (w, d) -> onCompleted.accept((T) w, d);
        LISTENERS.put(workType, cast);
    }

    /**
     * 取消注册。
     */
    public static void unregister(Class<? extends RunningWork> workType) {
        LISTENERS.remove(workType);
    }

    /**
     * 由 {@link ThreadState#end()} 调用，按 work 类型分发耗时。
     */
    static void onWorkCompleted(RunningWork work, long durationNanos, Thread thread) {
        BiConsumer<RunningWork, Long> listener = LISTENERS.get(work.getClass());
        if (listener != null) {
            listener.accept(work, durationNanos);
        }
    }
}
