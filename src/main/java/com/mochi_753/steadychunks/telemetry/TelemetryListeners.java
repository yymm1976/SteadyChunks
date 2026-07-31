package com.mochi_753.steadychunks.telemetry;

/**
 * 诊断监听器注册中心。
 * <p>
 * 在静态初始化时把各类 {@link RunningWork} 完成回调注册到 {@link MetricsDispatch}，
 * 转发到 {@link ChunkFlightRecorder} 对应的指标模块。
 * <p>
 * 由 {@code ModuleBootstrap} 在启动时触发类加载（{@code Class.forName}）完成注册。
 */
public final class TelemetryListeners {
    private static volatile boolean registered = false;

    private TelemetryListeners() {
    }

    /**
     * 注册所有监听器。幂等，仅注册一次。
     */
    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        // StageWork → StageMetrics（按 ChunkStatus 分发）
        MetricsDispatch.register(StageWork.class, (work, durationNanos) ->
                ChunkFlightRecorder.stages().recordExecution(work.status(), durationNanos));
    }
}
