package com.mochi_753.steadychunks.scheduler;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段并发限制器，对应开发计划 §3.3 与技术指导 §7.1。
 * <p>
 * 为每个 ChunkStatus 阶段维护一个 {@link ResourcePermit}，限制同时执行的任务数。
 * <p>
 * 技术指导 §7.1：依赖解锁任务必须有保留资源 permit，
 * 普通任务不能占用最后一个依赖 permit，避免依赖死锁。
 * <p>
 * 阶段与 {@link ResourceType} 的映射：
 * <ul>
 *   <li>STRUCTURE_STARTS / STRUCTURE_REFERENCES → STRUCTURE_PLANNING</li>
 *   <li>BIOMES / NOISE / SURFACE → NOISE_HEAVY</li>
 *   <li>CARVERS / FEATURES → FEATURES_WRITE</li>
 *   <li>INITIALIZE_LIGHT / LIGHT → LIGHT</li>
 *   <li>SPAWN / FULL → MAIN_THREAD_COMMIT</li>
 * </ul>
 */
public final class StageLimiter {
    /** 按 ChunkStatus 索引的 permit */
    private final ConcurrentHashMap<ChunkStatus, ResourcePermit> permits = new ConcurrentHashMap<>();

    public StageLimiter() {
        registerStage(ChunkStatus.STRUCTURE_STARTS, ResourceType.STRUCTURE_PLANNING, 2);
        registerStage(ChunkStatus.STRUCTURE_REFERENCES, ResourceType.STRUCTURE_PLANNING, 2);
        registerStage(ChunkStatus.BIOMES, ResourceType.NOISE_HEAVY, 3);
        registerStage(ChunkStatus.NOISE, ResourceType.NOISE_HEAVY, 3);
        registerStage(ChunkStatus.SURFACE, ResourceType.NOISE_HEAVY, 2);
        registerStage(ChunkStatus.CARVERS, ResourceType.FEATURES_WRITE, 2);
        registerStage(ChunkStatus.FEATURES, ResourceType.FEATURES_WRITE, 1);
        registerStage(ChunkStatus.INITIALIZE_LIGHT, ResourceType.LIGHT, 2);
        registerStage(ChunkStatus.LIGHT, ResourceType.LIGHT, 2);
        registerStage(ChunkStatus.SPAWN, ResourceType.MAIN_THREAD_COMMIT, 2);
        registerStage(ChunkStatus.FULL, ResourceType.MAIN_THREAD_COMMIT, 2);
    }

    private void registerStage(ChunkStatus status, ResourceType resource, int defaultPermits) {
        permits.put(status, new ResourcePermit(resource, defaultPermits));
    }

    /**
     * 尝试获取指定阶段的 permit（普通任务）。
     *
     * @return true 表示获取成功
     */
    public boolean tryAcquire(ChunkStatus status) {
        return tryAcquire(status, false);
    }

    /**
     * 尝试获取指定阶段的 permit。
     * <p>
     * 技术指导 §7.1：依赖解锁任务可使用保留 permit，普通任务不能占用保留额度。
     *
     * @param status 目标阶段
     * @param isDependencyUnlock 是否为依赖解锁任务（可使用保留 permit）
     * @return true 表示获取成功
     */
    public boolean tryAcquire(ChunkStatus status, boolean isDependencyUnlock) {
        ResourcePermit permit = permits.get(status);
        if (permit == null) {
            return false;
        }
        // 普通任务在 available <= reserve 时拒绝，保留给依赖解锁
        if (!isDependencyUnlock && permit.availablePermits() <= permit.dependencyReserve()) {
            return false;
        }
        return permit.tryAcquire();
    }

    /**
     * 释放指定阶段的 permit。
     */
    public void release(ChunkStatus status) {
        ResourcePermit permit = permits.get(status);
        if (permit != null) {
            permit.release();
        }
    }

    /**
     * 获取指定阶段的 permit 信息。
     */
    public ResourcePermit permit(ChunkStatus status) {
        return permits.get(status);
    }

    /**
     * 动态设置阶段 permit 上限（Phase 4 AIMD 调用）。
     */
    public void setStageLimit(ChunkStatus status, int max) {
        ResourcePermit permit = permits.get(status);
        if (permit != null) {
            permit.setMaxPermits(max);
        }
    }

    /**
     * 设置阶段依赖保留 permit 数量。
     */
    public void setStageDependencyReserve(ChunkStatus status, int reserve) {
        ResourcePermit permit = permits.get(status);
        if (permit != null) {
            permit.setDependencyReserve(reserve);
        }
    }

    /**
     * 返回所有阶段的当前状态快照，供诊断导出。
     */
    public Map<ChunkStatus, int[]> snapshot() {
        Map<ChunkStatus, int[]> out = new HashMap<>();
        for (var entry : permits.entrySet()) {
            ResourcePermit p = entry.getValue();
            out.put(entry.getKey(), new int[]{p.acquiredCount(), p.maxPermits(), p.availablePermits(), p.dependencyReserve()});
        }
        return out;
    }
}
