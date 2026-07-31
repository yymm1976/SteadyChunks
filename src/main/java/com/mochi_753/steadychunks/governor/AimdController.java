package com.mochi_753.steadychunks.governor;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AIMD（Additive Increase, Multiplicative Decrease）控制器，对应开发计划 §4.3。
 * <p>
 * 规则：
 * <pre>
 * 健康窗口持续存在：
 *   缓慢增加一个阶段 permit 或提高小幅预算
 *
 * 出现 CPU、MSPT、帧时间或 GC 超标：
 *   立即减少高成本阶段 permit
 *   暂停远处早期阶段
 *   保留近处完成任务
 * </pre>
 * <p>
 * 防振荡（§4.5）：
 * <ul>
 *   <li>控制周期不短于任务典型完成时间</li>
 *   <li>增加与减少采用不同阈值</li>
 *   <li>使用冷却时间</li>
 *   <li>对每个阶段设置最小和最大 permit</li>
 * </ul>
 */
public final class AimdController {
    /** 每个阶段的 AIMD 状态（ChunkStatus 在 1.21.1 非枚举，用 ConcurrentHashMap） */
    private final ConcurrentHashMap<ChunkStatus, StageControl> controls = new ConcurrentHashMap<>();
    /** 增加冷却（tick） */
    private volatile int increaseCooldownTicks = 100;
    /** 减少冷却（tick） */
    private volatile int decreaseCooldownTicks = 20;
    /** 距上次增加的 tick 数 */
    private int ticksSinceIncrease = 0;
    /** 距上次减少的 tick 数 */
    private int ticksSinceDecrease = 0;
    /** 健康窗口连续计数（需持续 N tick 才增加） */
    private int consecutiveHealthyTicks = 0;
    /** 触发增加所需连续健康 tick 数 */
    private static final int HEALTHY_WINDOW_FOR_INCREASE = 200;

    /**
     * §9.1 轮流提升顺序：NOISE → STRUCTURE_STARTS → LIGHT → FEATURES。
     * 不要同时提高所有阶段，每次只提升一个阶段，观察一个窗口再决定下一步。
     */
    private static final ChunkStatus[] INCREASE_ROTATION = {
            ChunkStatus.NOISE,
            ChunkStatus.STRUCTURE_STARTS,
            ChunkStatus.LIGHT,
            ChunkStatus.FEATURES
    };
    /** 当前轮流提升索引 */
    private int increaseRotationIndex = 0;

    public AimdController() {
        // 初始化各阶段控制参数（min/max/default）
        register(ChunkStatus.STRUCTURE_STARTS, 1, 2, 1);
        register(ChunkStatus.BIOMES, 1, 3, 2);
        register(ChunkStatus.NOISE, 1, 3, 2);
        register(ChunkStatus.SURFACE, 1, 2, 2);
        register(ChunkStatus.CARVERS, 1, 2, 2);
        register(ChunkStatus.FEATURES, 1, 2, 1);
        register(ChunkStatus.INITIALIZE_LIGHT, 1, 2, 2);
        register(ChunkStatus.LIGHT, 1, 2, 2);
        register(ChunkStatus.SPAWN, 1, 2, 2);
        register(ChunkStatus.FULL, 1, 2, 2);
    }

    private void register(ChunkStatus status, int min, int max, int initial) {
        controls.put(status, new StageControl(min, max, initial));
    }

    /**
     * 每 tick 调用，根据压力快照调整各阶段 permit。
     *
     * @param level 当前压力等级
     * @return 各阶段新的 permit 上限
     */
    public Map<ChunkStatus, Integer> tick(PressureSnapshot.PressureLevel level) {
        ticksSinceIncrease++;
        ticksSinceDecrease++;

        switch (level) {
            case HEALTHY -> {
                consecutiveHealthyTicks++;
                // 持续健康窗口后，缓慢增加
                if (consecutiveHealthyTicks >= HEALTHY_WINDOW_FOR_INCREASE
                        && ticksSinceIncrease >= increaseCooldownTicks) {
                    additiveIncrease();
                    consecutiveHealthyTicks = 0;
                    ticksSinceIncrease = 0;
                }
            }
            case ELEVATED -> {
                // 升高：保持不变，重置健康计数
                consecutiveHealthyTicks = 0;
            }
            case CRITICAL -> {
                // 临界：立即减少
                consecutiveHealthyTicks = 0;
                if (ticksSinceDecrease >= decreaseCooldownTicks) {
                    multiplicativeDecrease();
                    ticksSinceDecrease = 0;
                }
            }
        }

        // 返回当前各阶段 permit 快照
        Map<ChunkStatus, Integer> result = new ConcurrentHashMap<>();
        for (var entry : controls.entrySet()) {
            result.put(entry.getKey(), entry.getValue().currentPermits);
        }
        return result;
    }

    /**
     * §9.1 加性增加：每次只提升轮流顺序中的一个阶段（+1，不超过 max）。
     * <p>
     * 不要同时提高所有阶段。建议轮流：NOISE → STRUCTURE → LIGHT → FEATURES。
     * 提升后观察一个窗口，再决定下一步。若当前阶段已达 max，跳过到下一个可提升阶段。
     */
    private void additiveIncrease() {
        // 尝试在轮流顺序中找到一个可提升的阶段
        for (int i = 0; i < INCREASE_ROTATION.length; i++) {
            ChunkStatus target = INCREASE_ROTATION[increaseRotationIndex];
            StageControl c = controls.get(target);
            // 推进索引，为下次提升做准备
            increaseRotationIndex = (increaseRotationIndex + 1) % INCREASE_ROTATION.length;
            if (c != null && c.currentPermits < c.maxPermits) {
                c.currentPermits++;
                return;
            }
        }
        // 所有阶段均达 max，不提升
    }

    /**
     * 乘性减少：高成本阶段减半，低成本阶段 -1（不低于 min）。
     */
    private void multiplicativeDecrease() {
        // 高成本阶段：NOISE / FEATURES / STRUCTURE_STARTS 减半
        reduceStage(ChunkStatus.NOISE, 0.5);
        reduceStage(ChunkStatus.FEATURES, 0.5);
        reduceStage(ChunkStatus.STRUCTURE_STARTS, 0.5);
        // 其他阶段 -1
        for (var entry : controls.entrySet()) {
            StageControl c = entry.getValue();
            if (entry.getKey() != ChunkStatus.NOISE
                    && entry.getKey() != ChunkStatus.FEATURES
                    && entry.getKey() != ChunkStatus.STRUCTURE_STARTS) {
                if (c.currentPermits > c.minPermits) {
                    c.currentPermits--;
                }
            }
        }
    }

    private void reduceStage(ChunkStatus status, double factor) {
        StageControl c = controls.get(status);
        if (c != null) {
            int newPermits = (int) Math.max(c.minPermits, c.currentPermits * factor);
            c.currentPermits = newPermits;
        }
    }

    public int getCurrentPermits(ChunkStatus status) {
        StageControl c = controls.get(status);
        return c != null ? c.currentPermits : 1;
    }

    public void setStageRange(ChunkStatus status, int min, int max) {
        StageControl c = controls.get(status);
        if (c != null) {
            c.minPermits = min;
            c.maxPermits = max;
            c.currentPermits = Math.max(min, Math.min(max, c.currentPermits));
        }
    }

    public void setIncreaseCooldownTicks(int ticks) {
        this.increaseCooldownTicks = ticks;
    }

    public void setDecreaseCooldownTicks(int ticks) {
        this.decreaseCooldownTicks = ticks;
    }

    /** 单个阶段的 AIMD 控制状态 */
    private static final class StageControl {
        volatile int minPermits;
        volatile int maxPermits;
        volatile int currentPermits;

        StageControl(int min, int max, int initial) {
            this.minPermits = min;
            this.maxPermits = max;
            this.currentPermits = Math.max(min, Math.min(max, initial));
        }
    }
}
