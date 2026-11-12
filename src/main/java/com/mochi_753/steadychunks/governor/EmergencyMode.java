package com.mochi_753.steadychunks.governor;

/**
 * 紧急平滑模式，对应开发计划 §4.4。
 * <p>
 * 触发条件：
 * <ul>
 *   <li>连续出现长帧</li>
 *   <li>MSPT 超过硬上限</li>
 *   <li>GC 暂停</li>
 *   <li>客户端编译队列暴涨</li>
 *   <li>区块发送队列暴涨</li>
 * </ul>
 * 响应：
 * <ul>
 *   <li>暂停启动新的 NOISE / FEATURES</li>
 *   <li>仅允许依赖解锁和近处完成任务</li>
 *   <li>降低发送速率</li>
 *   <li>限制 FULL 整合数量</li>
 *   <li>在恢复窗口内逐步提升，而不是立即回满负载</li>
 * </ul>
 */
public final class EmergencyMode {
    /** 是否处于紧急模式 */
    private volatile boolean active = false;
    /** 进入紧急模式的时间戳（纳秒） */
    private volatile long activatedNanos = 0;
    /** 连续紧急 tick 计数 */
    private int consecutiveEmergencyTicks = 0;
    /** 触发紧急模式所需的连续 tick 数 */
    private static final int ACTIVATION_THRESHOLD = 3;
    /** 恢复所需的连续健康 tick 数 */
    private static final int RECOVERY_THRESHOLD = 100;
    /** 恢复窗口内的逐步提升步数 */
    private int recoverySteps = 0;
    private static final int MAX_RECOVERY_STEPS = 10;

    /**
     * 每 tick 评估是否应进入或退出紧急模式。
     *
     * @param level 当前压力等级
     * @return 当前模式状态
     */
    public ModeState tick(PressureSnapshot.PressureLevel level) {
        if (level == PressureSnapshot.PressureLevel.CRITICAL) {
            consecutiveEmergencyTicks++;
            if (!active && consecutiveEmergencyTicks >= ACTIVATION_THRESHOLD) {
                activate();
            }
        } else if (level == PressureSnapshot.PressureLevel.HEALTHY) {
            if (active) {
                recoverySteps++;
                if (recoverySteps >= MAX_RECOVERY_STEPS) {
                    deactivate();
                }
            } else {
                consecutiveEmergencyTicks = 0;
            }
        }
        // ELEVATED 时不改变状态

        return currentModeState();
    }

    private void activate() {
        active = true;
        activatedNanos = System.nanoTime();
        consecutiveEmergencyTicks = 0;
        recoverySteps = 0;
    }

    private void deactivate() {
        active = false;
        consecutiveEmergencyTicks = 0;
        recoverySteps = 0;
    }

    /**
     * 返回当前模式状态，供调度器查询。
     */
    public ModeState currentModeState() {
        if (!active) {
            return ModeState.NORMAL;
        }
        if (recoverySteps > 0) {
            // 恢复窗口：逐步提升负载
            double recoveryRatio = (double) recoverySteps / MAX_RECOVERY_STEPS;
            return new ModeState(true, true, recoveryRatio);
        }
        // 完全紧急：最大限制
        return new ModeState(true, false, 0.0);
    }

    public boolean isActive() {
        return active;
    }

    public long activeDurationMs() {
        if (!active) {
            return 0;
        }
        return (System.nanoTime() - activatedNanos) / 1_000_000L;
    }

    /**
     * 模式状态快照。
     *
     * @param emergency 是否处于紧急模式
     * @param recovering 是否处于恢复窗口
     * @param recoveryRatio 恢复比例 [0,1]，0 表示完全紧急，1 表示完全恢复
     */
    public record ModeState(boolean emergency, boolean recovering, double recoveryRatio) {
        /** 正常模式 */
        public static final ModeState NORMAL = new ModeState(false, false, 1.0);
    }
}
