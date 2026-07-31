package com.mochi_753.steadychunks.scheduler;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 区块任务模型，对应开发计划 §3.1 与技术指导 §4/§6.2/§7.1。
 * <p>
 * 封装单个区块从当前阶段推进到目标阶段所需的全部调度维度：
 * 位置、维度、阶段、依赖、需求玩家、距离、运动方向、排队年龄、完成进度、
 * 安全等级、取消状态、依赖关键性、优先级版本（惰性更新）。
 * <p>
 * 线程安全：所有可变状态使用 volatile 或 Atomic 字段，确保调度线程与工作线程并发访问安全。
 */
public final class ChunkTask {
    private final ChunkPos pos;
    private final ResourceKey<Level> dimension;
    private final ChunkStatus targetStatus;
    private final ChunkStatus currentStatus;
    /** 依赖的区块位置集合（必须先完成才能推进本任务） */
    private final Set<ChunkPos> dependencies;
    /** 请求该区块的玩家集合 */
    private final Set<UUID> requestingPlayers;
    /** 到最近需求玩家的棋盘距离 */
    private volatile double distance;
    /** 运动方向匹配度 [0,1]，1 表示完全匹配玩家运动方向 */
    private volatile double directionMatch;
    /** 入队时间（纳秒），用于计算排队年龄 */
    private final long queueEnterNanos;
    /** 完成进度 [0,1] */
    private volatile double progress;
    /** 安全等级：LOW（可取消）/ MEDIUM（谨慎取消）/ HIGH（禁止取消） */
    private final AtomicReference<SafetyLevel> safety = new AtomicReference<>(SafetyLevel.LOW);
    /** 任务状态 */
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
    /** 优先级评分缓存（long，避免浮点不稳定，技术指导 §6.1），调度器每次重排时更新 */
    private volatile long priorityScore;
    /**
     * 优先级版本（技术指导 §6.2 惰性更新）。
     * 当 demandTracker 版本变化时，标记此任务的 cachedPriority 过期，
     * 下次从队列 poll 时重算。
     */
    private volatile long priorityVersion;
    /** 是否为依赖解锁任务（技术指导 §7.1，可使用保留 permit） */
    private volatile boolean requiredForDependency;
    /** §17.3 进入 RUNNING 状态的时间戳，供 Watchdog 检测执行超时 */
    private volatile long stageStartNanos;
    /** 全局任务 ID，用于日志与诊断 */
    private final long taskId;
    private static final AtomicLong NEXT_ID = new AtomicLong(0);

    public ChunkTask(ChunkPos pos, ResourceKey<Level> dimension,
                     ChunkStatus currentStatus, ChunkStatus targetStatus,
                     Set<ChunkPos> dependencies, Set<UUID> requestingPlayers,
                     double distance, double directionMatch) {
        this.pos = pos;
        this.dimension = dimension;
        this.currentStatus = currentStatus;
        this.targetStatus = targetStatus;
        this.dependencies = ConcurrentHashMap.newKeySet();
        this.dependencies.addAll(dependencies);
        this.requestingPlayers = ConcurrentHashMap.newKeySet();
        this.requestingPlayers.addAll(requestingPlayers);
        this.distance = distance;
        this.directionMatch = directionMatch;
        this.queueEnterNanos = System.nanoTime();
        this.progress = 0.0;
        this.taskId = NEXT_ID.incrementAndGet();
    }

    public ChunkPos pos() {
        return pos;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public ChunkStatus targetStatus() {
        return targetStatus;
    }

    public ChunkStatus currentStatus() {
        return currentStatus;
    }

    public Set<ChunkPos> dependencies() {
        return dependencies;
    }

    public Set<UUID> requestingPlayers() {
        return requestingPlayers;
    }

    public double distance() {
        return distance;
    }

    public void setDistance(double distance) {
        this.distance = distance;
    }

    public double directionMatch() {
        return directionMatch;
    }

    public void setDirectionMatch(double match) {
        this.directionMatch = match;
    }

    /** 排队年龄（毫秒） */
    public long queueAgeMs() {
        return (System.nanoTime() - queueEnterNanos) / 1_000_000L;
    }

    /** §17.3 入队时间戳，供 Watchdog 检测排队超时 */
    public long queueEnterNanos() {
        return queueEnterNanos;
    }

    public double progress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = Math.max(0, Math.min(1, progress));
    }

    public SafetyLevel safety() {
        return safety.get();
    }

    public void setSafety(SafetyLevel level) {
        safety.set(level);
    }

    public TaskState state() {
        return state.get();
    }

    public boolean compareAndSetState(TaskState expect, TaskState update) {
        return state.compareAndSet(expect, update);
    }

    /**
     * 设置任务状态（仅限单线程调用，如调度器 tick）。
     * 多线程场景应使用 {@link #compareAndSetState}。
     * <p>
     * §17.3 进入 RUNNING 时记录 stageStartNanos，供 Watchdog 检测执行超时。
     */
    public void setState(TaskState newState) {
        if (newState == TaskState.RUNNING) {
            this.stageStartNanos = System.nanoTime();
        }
        state.set(newState);
    }

    public long priorityScore() {
        return priorityScore;
    }

    public void setPriorityScore(long score) {
        this.priorityScore = score;
    }

    public long priorityVersion() {
        return priorityVersion;
    }

    /** §17.3 进入 RUNNING 的时间戳，供 Watchdog 检测执行超时 */
    public long stageStartNanos() {
        return stageStartNanos;
    }

    public void setPriorityVersion(long version) {
        this.priorityVersion = version;
    }

    public boolean requiredForDependency() {
        return requiredForDependency;
    }

    public void setRequiredForDependency(boolean required) {
        this.requiredForDependency = required;
    }

    public long taskId() {
        return taskId;
    }

    /** 安全等级，对应 §3.6 软取消策略 */
    public enum SafetyLevel {
        /** 早期阶段（QUEUED/WAITING_DEPS），可安全取消 */
        LOW,
        /** 中期阶段（READY），谨慎取消 */
        MEDIUM,
        /** 后期阶段（RUNNING FEATURES/LIGHT/SAVE），禁止取消 */
        HIGH
    }
}
