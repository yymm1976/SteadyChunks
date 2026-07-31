package com.mochi_753.steadychunks.scheduler;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 区块任务依赖图，对应开发计划 §3.1 与 §3.4。
 * <p>
 * 管理任务间的依赖关系：一个区块的后期阶段依赖相邻区块的早期阶段完成。
 * <p>
 * 风险缓解（§3 风险表）：
 * <ul>
 *   <li>依赖任务被限流导致死锁 → 依赖解锁任务拥有保底优先级和保留 permit</li>
 *   <li>与原版任务系统重复调度 → 明确唯一所有者</li>
 * </ul>
 */
public final class ChunkTaskGraph {
    /** 按区块位置索引的任务映射 */
    private final ConcurrentHashMap<ChunkPos, ChunkTask> tasks = new ConcurrentHashMap<>();
    /** 反向依赖：被依赖的区块 → 依赖它的区块集合 */
    private final ConcurrentHashMap<ChunkPos, Set<ChunkPos>> reverseDeps = new ConcurrentHashMap<>();

    /**
     * 注册任务到图中。
     */
    public void register(ChunkTask task) {
        tasks.put(task.pos(), task);
        // 建立反向依赖索引
        for (ChunkPos dep : task.dependencies()) {
            reverseDeps.computeIfAbsent(dep, k -> ConcurrentHashMap.newKeySet()).add(task.pos());
        }
    }

    /**
     * 从图中移除任务（任务完成或取消后调用）。
     */
    public void remove(ChunkPos pos) {
        ChunkTask task = tasks.remove(pos);
        if (task != null) {
            for (ChunkPos dep : task.dependencies()) {
                Set<ChunkPos> dependents = reverseDeps.get(dep);
                if (dependents != null) {
                    dependents.remove(pos);
                }
            }
        }
        reverseDeps.remove(pos);
    }

    /**
     * §9.4 从图中移除任务（按 packed long）。
     */
    public void remove(long packedPos) {
        remove(new ChunkPos(packedPos));
    }

    /**
     * 获取指定位置的任务。
     */
    public ChunkTask get(ChunkPos pos) {
        return tasks.get(pos);
    }

    /**
     * 检查任务的所有依赖是否已完成。
     */
    public boolean areDependenciesMet(ChunkTask task) {
        for (ChunkPos dep : task.dependencies()) {
            ChunkTask depTask = tasks.get(dep);
            if (depTask == null || depTask.state() != TaskState.DONE) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取依赖指定区块的任务集合（用于依赖完成时通知）。
     */
    public Set<ChunkPos> getDependents(ChunkPos pos) {
        return reverseDeps.getOrDefault(pos, Set.of());
    }

    /**
     * 获取所有已注册的任务。
     */
    public java.util.Collection<ChunkTask> allTasks() {
        return tasks.values();
    }

    /**
     * 当前图中的任务总数。
     */
    public int size() {
        return tasks.size();
    }

    /**
     * 清空图（如世界卸载时）。
     */
    public void clear() {
        tasks.clear();
        reverseDeps.clear();
    }
}
