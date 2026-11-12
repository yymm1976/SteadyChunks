package com.mochi_753.steadychunks.scheduler;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段并发限制器，对应开发计划 §3.3 与技术指导 §7.1，P1-09 修复。
 * <p>
 * <b>共享资源修复</b>：同一 {@link ResourceType} 的多个 ChunkStatus 阶段
 * 共享同一个 {@link ResourceBucket}，真正实现资源约束。
 * 例如 BIOMES / NOISE / SURFACE 共享 NOISE_HEAVY 桶，三者总并发受限于桶上限。
 * <p>
 * 技术指导 §7.1：依赖解锁任务必须有保留资源 permit，
 * 普通任务不能占用最后一个依赖 permit，避免依赖死锁。
 * <p>
 * 阶段与 {@link ResourceType} 的映射：
 * <ul>
 *   <li>STRUCTURE_STARTS / STRUCTURE_REFERENCES → STRUCTURE_PLANNING</li>
 *   <li>BIOMES / NOISE / SURFACE → NOISE_HEAVY（共享）</li>
 *   <li>CARVERS / FEATURES → FEATURES_WRITE</li>
 *   <li>INITIALIZE_LIGHT / LIGHT → LIGHT</li>
 *   <li>SPAWN / FULL → MAIN_THREAD_COMMIT</li>
 * </ul>
 */
public final class StageLimiter {
    /** 按 ResourceType 共享的资源桶（P1-09：同类型阶段共享） */
    private final EnumMap<ResourceType, ResourceBucket> buckets = new EnumMap<>(ResourceType.class);
    /** 按 ChunkStatus 索引的阶段策略（引用共享桶 + 每阶段保留额度） */
    private final ConcurrentHashMap<ChunkStatus, StagePolicy> policies = new ConcurrentHashMap<>();
    /** P0 修复（类加载时机）：是否已完成阶段注册（惰性初始化，见 {@link #ensureInitialized}） */
    private volatile boolean initialized = false;

    public StageLimiter() {
        // 构造器不引用 ChunkStatus：CrashReport 可能在注册表 bootstrap 前触发
        // ChunkScheduler.getInstance()，此时访问 ChunkStatus 会触发 BuiltInRegistries
        // 类初始化并抛 "Not bootstrapped"，导致整个 Minecraft 启动失败。
        // 阶段注册惰性延迟到首次实际使用（ensureInitialized）。
    }

    /**
     * P0 修复（类加载时机）：惰性注册阶段映射。
     * <p>
     * 仅在首次需要 ChunkStatus 映射时初始化。初始化后 ChunkStatus 类已加载
     * （运行期必然在 bootstrap 之后），因此不会触发类初始化错误。
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            // 初始化共享桶（每种 ResourceType 一个桶）
            // 审查修复：dependencyReserve 全部设为 0。
            // 在真正实现"依赖关键任务识别"之前，任何任务都不会标记 isDependencyUnlock=true，
            // 若 reserve>0 且 limit 降至 1，effectiveLimit=0 会导致所有普通任务永久拿不到 permit。
            registerStage(ChunkStatus.STRUCTURE_STARTS, ResourceType.STRUCTURE_PLANNING, 2, 0);
            registerStage(ChunkStatus.STRUCTURE_REFERENCES, ResourceType.STRUCTURE_PLANNING, 2, 0);
            registerStage(ChunkStatus.BIOMES, ResourceType.NOISE_HEAVY, 3, 0);
            registerStage(ChunkStatus.NOISE, ResourceType.NOISE_HEAVY, 3, 0);
            registerStage(ChunkStatus.SURFACE, ResourceType.NOISE_HEAVY, 3, 0);
            registerStage(ChunkStatus.CARVERS, ResourceType.FEATURES_WRITE, 2, 0);
            registerStage(ChunkStatus.FEATURES, ResourceType.FEATURES_WRITE, 2, 0);
            registerStage(ChunkStatus.INITIALIZE_LIGHT, ResourceType.LIGHT, 2, 0);
            registerStage(ChunkStatus.LIGHT, ResourceType.LIGHT, 2, 0);
            registerStage(ChunkStatus.SPAWN, ResourceType.MAIN_THREAD_COMMIT, 2, 0);
            registerStage(ChunkStatus.FULL, ResourceType.MAIN_THREAD_COMMIT, 2, 0);
            initialized = true;
        }
    }

    /**
     * 注册阶段，共享同 ResourceType 的 ResourceBucket。
     *
     * @param status           目标阶段
     * @param resource         资源类型
     * @param defaultPermits   桶 permit 上限（仅在该 ResourceType 首次注册时生效）
     * @param dependencyReserve 该阶段的依赖解锁保留额度
     */
    private void registerStage(ChunkStatus status, ResourceType resource, int defaultPermits, int dependencyReserve) {
        ResourceBucket bucket = buckets.computeIfAbsent(resource, k -> new ResourceBucket(k, defaultPermits));
        policies.put(status, new StagePolicy(status, resource, bucket, dependencyReserve));
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
     * <p>
     * P1-09 修复：同 ResourceType 的多个阶段共享桶，可用 permit 是共享值。
     * 普通任务检查时使用所有阶段中最小的保留额度作为门槛，避免任一阶段的保留额度被普通任务占用。
     * <p>
     * 审查新发现 #4 修复：原实现"先检查 reserve 再 tryAcquire"是两步操作，存在 check-then-act 竞态，
     * 两个普通任务可同时通过检查后各自 CAS 获取，挤占依赖解锁保留额度。
     * 改为调用 {@link ResourceBucket#tryAcquireWithReserve} 单 CAS 同时判断 limit 与 reserve。
     *
     * @param status 目标阶段
     * @param isDependencyUnlock 是否为依赖解锁任务（可使用保留 permit）
     * @return true 表示获取成功
     */
    public boolean tryAcquire(ChunkStatus status, boolean isDependencyUnlock) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy == null) {
            return false;
        }
        ResourceBucket bucket = policy.bucket;
        if (isDependencyUnlock) {
            // 依赖解锁任务可使用保留 permit
            return bucket.tryAcquire();
        }
        // 普通任务：单 CAS 同时校验 reserve，消除 check-then-act 竞态
        return bucket.tryAcquireWithReserve(policy.dependencyReserve);
    }

    /**
     * 尝试获取指定阶段的 permit，返回 {@link PermitLease}（AutoCloseable）。
     * <p>
     * 配合 try-with-resources 保证 permit 在正常、异常、取消路径均被释放。
     * 保留额度语义与 {@link #tryAcquire(ChunkStatus, boolean)} 一致。
     */
    public PermitLease tryAcquireLease(ChunkStatus status, boolean isDependencyUnlock) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy == null) {
            return PermitLease.empty();
        }
        if (isDependencyUnlock) {
            return policy.bucket.tryAcquireLease(0);
        }
        return policy.bucket.tryAcquireLease(policy.dependencyReserve);
    }

    /**
     * 释放指定阶段的 permit。
     */
    public void release(ChunkStatus status) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy != null) {
            policy.bucket.release();
        }
    }

    /**
     * 获取指定阶段的 permit 信息（诊断用，返回包装 ResourcePermit）。
     */
    public ResourcePermit permit(ChunkStatus status) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy == null) {
            return null;
        }
        return new ResourcePermit(policy.resource, policy.bucket, policy.dependencyReserve);
    }

    /**
     * 动态设置阶段 permit 上限（Phase 4 AIMD 调用）。
     * <p>
     * P1-09：调整的是共享桶的上限，同 ResourceType 的所有阶段同时受影响。
     * <p>
     * P0-3 修复：此方法仍保留供 PresetApplier 使用，但 Governor 应优先使用
     * {@link #setResourceLimit} 直接按 ResourceType 设置，避免同资源组的多个
     * ChunkStatus 连续写入同一桶导致最终值依赖遍历顺序。
     */
    public void setStageLimit(ChunkStatus status, int max) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy != null) {
            policy.bucket.setMaxPermits(max);
        }
    }

    /**
     * P0-3 修复：直接按 ResourceType 设置共享桶上限。
     * <p>
     * Governor 应使用此方法而非 {@link #setStageLimit}，避免同资源组的多个
     * ChunkStatus（如 BIOMES/NOISE/SURFACE 共享 NOISE_HEAVY）连续写入同一桶
     * 导致最终值依赖遍历顺序。
     */
    public void setResourceLimit(ResourceType resource, int max) {
        ensureInitialized();
        ResourceBucket bucket = buckets.get(resource);
        if (bucket != null) {
            bucket.setMaxPermits(max);
        }
    }

    /**
     * P0-3 修复：查询 ChunkStatus 对应的 ResourceType（Governor 聚合用）。
     */
    public ResourceType resourceTypeOf(ChunkStatus status) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        return policy != null ? policy.resource : null;
    }

    /**
     * 设置阶段依赖保留 permit 数量。
     */
    public void setStageDependencyReserve(ChunkStatus status, int reserve) {
        ensureInitialized();
        StagePolicy policy = policies.get(status);
        if (policy != null) {
            policy.dependencyReserve = reserve;
        }
    }

    /**
     * 返回所有阶段的当前状态快照，供诊断导出。
     */
    public Map<ChunkStatus, int[]> snapshot() {
        ensureInitialized();
        Map<ChunkStatus, int[]> out = new HashMap<>();
        for (var entry : policies.entrySet()) {
            StagePolicy p = entry.getValue();
            out.put(entry.getKey(), new int[]{p.bucket.acquiredCount(), p.bucket.maxPermits(), p.bucket.availablePermits(), p.dependencyReserve});
        }
        return out;
    }

    /**
     * 阶段策略：绑定 ChunkStatus 到共享 ResourceBucket + 每阶段保留额度。
     */
    private static final class StagePolicy {
        final ChunkStatus status;
        final ResourceType resource;
        final ResourceBucket bucket;
        volatile int dependencyReserve;

        StagePolicy(ChunkStatus status, ResourceType resource, ResourceBucket bucket, int dependencyReserve) {
            this.status = status;
            this.resource = resource;
            this.bucket = bucket;
            this.dependencyReserve = dependencyReserve;
        }
    }
}
