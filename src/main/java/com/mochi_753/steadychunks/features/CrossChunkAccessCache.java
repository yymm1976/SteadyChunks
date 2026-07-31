package com.mochi_753.steadychunks.features;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.concurrent.ConcurrentHashMap;

/**
 * FEATURES 跨区块访问局部缓存，对应开发计划 §7.5。
 * <p>
 * 在单次 FEATURES 执行（单线程内）缓存 {@code WorldGenRegion} 内合法的区块引用，
 * 避免对同一 Piece 的重复区块定位与同步 {@code getChunk} 调用。
 * <p>
 * 与 {@link com.mochi_753.steadychunks.structure.SyncChunkRequestAuditor} 配合：
 * <ul>
 *   <li>本类提供局部缓存，减少合法跨区块访问的查询成本</li>
 *   <li>Auditor 检测非法同步请求与依赖环</li>
 * </ul>
 * <p>
 * 生命周期：单次 FEATURES 执行（线程内），执行结束后必须调用 {@link #clear()}。
 * 不跨 Tick、不跨任务共享。
 * <p>
 * 线程安全：使用 ThreadLocal，每线程独立缓存。
 * <p>
 * 不扩大原版允许的写入边界（计划 §7.5）：本缓存仅缓存读取引用，
 * 不缓存写入结果，不绕过原版边界检查。
 */
public final class CrossChunkAccessCache {

    /** 每线程独立的缓存实例 */
    private static final ThreadLocal<CrossChunkAccessCache> INSTANCE =
            ThreadLocal.withInitial(CrossChunkAccessCache::new);

    /** 按 packed ChunkPos 索引的区块引用缓存 */
    private final ConcurrentHashMap<Long, CachedChunkRef> cache = new ConcurrentHashMap<>(16);
    /** 当前 WorldGenRegion 的中心 ChunkPos（用于判断是否在合法范围内） */
    private volatile long centerChunkPos = ChunkPos.INVALID_CHUNK_POS;
    /** 当前 WorldGenRegion 的半径（区块数） */
    private volatile int regionRadius = 0;
    /** 命中计数 */
    private long hits = 0;
    /** 未命中计数 */
    private long misses = 0;
    /** 越界访问计数（诊断用） */
    private long outOfBounds = 0;

    private CrossChunkAccessCache() {
    }

    /**
     * 获取当前线程的缓存实例。
     */
    public static CrossChunkAccessCache current() {
        return INSTANCE.get();
    }

    /**
     * 设置当前 WorldGenRegion 的中心与半径（每次 FEATURES 开始时调用）。
     *
     * @param centerChunkX 中心区块 X
     * @param centerChunkZ 中心区块 Z
     * @param radius       区域半径（区块数）
     */
    public void setRegion(int centerChunkX, int centerChunkZ, int radius) {
        this.centerChunkPos = ChunkPos.asLong(centerChunkX, centerChunkZ);
        this.regionRadius = radius;
        this.cache.clear();
        this.hits = 0;
        this.misses = 0;
        this.outOfBounds = 0;
    }

    /**
     * 查询缓存的区块引用。
     * <p>
     * 先检查目标区块是否在当前 WorldGenRegion 合法范围内，
     * 越界访问记录到 {@link #outOfBounds} 并返回 null（不缓存越界访问）。
     *
     * @param packedChunkPos 目标区块 packed long
     * @return 缓存的区块引用，null 表示未缓存或越界
     */
    public CachedChunkRef lookup(long packedChunkPos) {
        if (!isInRegion(packedChunkPos)) {
            outOfBounds++;
            return null;
        }
        CachedChunkRef cached = cache.get(packedChunkPos);
        if (cached != null) {
            hits++;
        } else {
            misses++;
        }
        return cached;
    }

    /**
     * 存储区块引用到缓存。
     *
     * @param packedChunkPos 目标区块 packed long
     * @param ref            区块引用（不可为 null）
     * @param status         区块当前阶段
     */
    public void store(long packedChunkPos, Object ref, ChunkStatus status) {
        if (!isInRegion(packedChunkPos)) {
            // 越界访问不缓存，但仍记录
            outOfBounds++;
            return;
        }
        cache.put(packedChunkPos, new CachedChunkRef(ref, status, System.nanoTime()));
    }

    /**
     * 判断目标区块是否在当前 WorldGenRegion 合法范围内。
     */
    private boolean isInRegion(long packedChunkPos) {
        if (regionRadius <= 0) {
            return false;
        }
        int dx = ChunkPos.getX(packedChunkPos) - ChunkPos.getX(centerChunkPos);
        int dz = ChunkPos.getZ(packedChunkPos) - ChunkPos.getZ(centerChunkPos);
        return Math.abs(dx) <= regionRadius && Math.abs(dz) <= regionRadius;
    }

    /**
     * 清空缓存（每次 FEATURES 执行结束后必须调用）。
     */
    public void clear() {
        cache.clear();
        centerChunkPos = ChunkPos.INVALID_CHUNK_POS;
        regionRadius = 0;
        hits = 0;
        misses = 0;
        outOfBounds = 0;
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long outOfBounds() {
        return outOfBounds;
    }

    public int size() {
        return cache.size();
    }

    /**
     * 缓存的区块引用（不可变）。
     * <p>
     * ref 字段为 Object 类型，避免直接依赖 ChunkAccess 等内部类。
     * 调用方按需强制转换。
     */
    public record CachedChunkRef(
            Object ref,
            ChunkStatus status,
            long cachedAtNanos
    ) {
    }
}
