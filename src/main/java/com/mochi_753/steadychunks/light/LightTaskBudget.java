package com.mochi_753.steadychunks.light;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 光照任务预算控制器，对应开发计划 §8.2。
 * <p>
 * 按以下维度限制光照任务：
 * <ul>
 *   <li>在途光照区块总数</li>
 *   <li>每维度任务数</li>
 *   <li>边界传播任务数（跨区块光照传播）</li>
 *   <li>完成回调数</li>
 *   <li>近处与远处优先级（近处优先）</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §8 风险表）：光照依赖复杂导致饥饿。
 * 相邻依赖和近处传播保留专用预算，避免边界传播任务被远处任务挤占。
 * <p>
 * 当 {@link LightCompatProbe.LightCompatDecision#manageBudget()} 为 false 时，
 * 本控制器自动放行所有请求（让路模式）。
 */
public final class LightTaskBudget {
    private static LightTaskBudget instance;

    /** 全局在途光照区块上限 */
    private volatile int globalMaxInflight = 8;
    /** 每维度在途光照区块上限 */
    private volatile int perDimensionMaxInflight = 6;
    /** 边界传播任务上限（跨区块光照依赖） */
    private volatile int boundaryPropagationMax = 4;
    /** 近处传播保留预算（专用，避免饥饿） */
    private volatile int nearPropagationReserve = 2;
    /** 每 Tick 完成回调上限 */
    private volatile int maxCompletionCallbacksPerTick = 4;

    /** 全局在途计数 */
    private final AtomicInteger globalInflight = new AtomicInteger(0);
    /** 每维度在途计数 */
    private final ConcurrentHashMap<ResourceKey<Level>, AtomicInteger> dimensionInflight = new ConcurrentHashMap<>();
    /** 边界传播在途计数 */
    private final AtomicInteger boundaryPropagationInflight = new AtomicInteger(0);
    /** 每 Tick 完成回调计数 */
    private final AtomicInteger tickCallbacks = new AtomicInteger(0);

    /** 统计：累计放行 */
    private final AtomicLong totalAdmitted = new AtomicLong(0);
    /** 统计：累计拒绝 */
    private final AtomicLong totalRejected = new AtomicLong(0);
    /** 统计：近处优先放行 */
    private final AtomicLong totalNearPreferred = new AtomicLong(0);

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private volatile boolean manageBudget = false;

    private LightTaskBudget() {
    }

    public static synchronized LightTaskBudget getInstance() {
        if (instance == null) {
            instance = new LightTaskBudget();
        }
        return instance;
    }

    /**
     * 应用兼容决策。仅当 {@code decision.manageBudget()} 为 true 时启用预算管理。
     */
    public void applyDecision(LightCompatProbe.LightCompatDecision decision) {
        this.manageBudget = decision.manageBudget();
        setEnabled(decision.manageBudget());
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * 尝试获取光照任务许可。
     * <p>
     * 检查顺序：全局 → 维度 → 边界传播（若是边界任务）→ 近处优先。
     * 近处任务可使用保留预算，远处任务不能占用保留额度。
     *
     * @param dimension         目标维度
     * @param isBoundaryPropagation 是否为边界传播任务
     * @param isNear            是否为近处任务（可使用保留预算）
     * @return true 表示获取成功
     */
    public boolean tryAcquire(ResourceKey<Level> dimension, boolean isBoundaryPropagation, boolean isNear) {
        if (!enabled.get() || !manageBudget) {
            return true; // 让路模式
        }

        // 全局上限
        if (globalInflight.get() >= globalMaxInflight) {
            totalRejected.incrementAndGet();
            return false;
        }
        // 维度上限
        AtomicInteger dimCount = dimensionInflight.computeIfAbsent(dimension, k -> new AtomicInteger(0));
        if (dimCount.get() >= perDimensionMaxInflight) {
            totalRejected.incrementAndGet();
            return false;
        }
        // 边界传播上限
        if (isBoundaryPropagation) {
            int boundary = boundaryPropagationInflight.get();
            // 远处边界传播不能占用近处保留额度
            int effectiveMax = isNear ? boundaryPropagationMax : Math.max(0, boundaryPropagationMax - nearPropagationReserve);
            if (boundary >= effectiveMax) {
                totalRejected.incrementAndGet();
                return false;
            }
        }

        // 全部通过，原子递增
        globalInflight.incrementAndGet();
        dimCount.incrementAndGet();
        if (isBoundaryPropagation) {
            boundaryPropagationInflight.incrementAndGet();
        }
        if (isNear) {
            totalNearPreferred.incrementAndGet();
        }
        totalAdmitted.incrementAndGet();
        return true;
    }

    /**
     * 释放光照任务许可。
     */
    public void release(ResourceKey<Level> dimension, boolean isBoundaryPropagation) {
        if (!enabled.get() || !manageBudget) {
            return;
        }
        globalInflight.decrementAndGet();
        AtomicInteger dimCount = dimensionInflight.get(dimension);
        if (dimCount != null) {
            dimCount.decrementAndGet();
        }
        if (isBoundaryPropagation) {
            boundaryPropagationInflight.decrementAndGet();
        }
    }

    /**
     * 尝试获取完成回调许可（每 Tick 限制）。
     *
     * @return true 表示允许执行回调
     */
    public boolean tryAcquireCallback() {
        if (!enabled.get() || !manageBudget) {
            return true;
        }
        if (tickCallbacks.get() >= maxCompletionCallbacksPerTick) {
            return false;
        }
        tickCallbacks.incrementAndGet();
        return true;
    }

    /**
     * 每 Tick 重置回调计数（主线程调用）。
     */
    public void resetTick() {
        tickCallbacks.set(0);
    }

    /**
     * 维度卸载时清理该维度的计数。
     */
    public void clearDimension(ResourceKey<Level> dimension) {
        AtomicInteger count = dimensionInflight.remove(dimension);
        if (count != null) {
            int released = count.get();
            if (released > 0) {
                globalInflight.addAndGet(-released);
            }
        }
    }

    public void clearAll() {
        globalInflight.set(0);
        dimensionInflight.clear();
        boundaryPropagationInflight.set(0);
        tickCallbacks.set(0);
    }

    // 配置访问器
    public void setGlobalMaxInflight(int max) { this.globalMaxInflight = max; }
    public void setPerDimensionMaxInflight(int max) { this.perDimensionMaxInflight = max; }
    public void setBoundaryPropagationMax(int max) { this.boundaryPropagationMax = max; }
    public void setNearPropagationReserve(int reserve) { this.nearPropagationReserve = reserve; }
    public void setMaxCompletionCallbacksPerTick(int max) { this.maxCompletionCallbacksPerTick = max; }

    // 诊断访问器
    public int globalInflight() { return globalInflight.get(); }
    public int dimensionInflight(ResourceKey<Level> dim) {
        AtomicInteger c = dimensionInflight.get(dim);
        return c != null ? c.get() : 0;
    }
    public int boundaryPropagationInflight() { return boundaryPropagationInflight.get(); }
    public long totalAdmitted() { return totalAdmitted.get(); }
    public long totalRejected() { return totalRejected.get(); }
    public long totalNearPreferred() { return totalNearPreferred.get(); }
}
