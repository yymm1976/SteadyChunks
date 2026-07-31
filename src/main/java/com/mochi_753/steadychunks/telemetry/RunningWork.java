package com.mochi_753.steadychunks.telemetry;

/**
 * 单条计时工作项，由 {@link ThreadInstrumentation#begin(RunningWork)} 推入线程状态栈。
 * <p>
 * 借鉴 C2ME 的 {@code RunningWork} 接口：实现类用 record 承载维度信息，
 * {@link #describe()} 用于 crash report / 导出报告的人类可读输出。
 */
public interface RunningWork {
    /**
     * 人类可读描述，写入 crash report 与导出报告。
     */
    String describe();
}
