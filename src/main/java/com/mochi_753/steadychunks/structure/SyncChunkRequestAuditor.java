package com.mochi_753.steadychunks.structure;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步区块请求审计器，对应开发计划 §6.5。
 * <p>
 * 检测结构或模组代码在世界生成线程中调用同步区块加载，可能导致死锁或依赖环。
 * <p>
 * 记录：
 * <ul>
 *   <li>当前生成区块（调用方所在的任务）</li>
 *   <li>被请求区块</li>
 *   <li>目标 ChunkStatus</li>
 *   <li>调用方 modid（从堆栈推断）</li>
 *   <li>是否等待（阻塞 vs 非阻塞）</li>
 *   <li>嵌套深度（同步请求中再发同步请求）</li>
 *   <li>是否形成依赖环（A 等 B，B 又等 A）</li>
 * </ul>
 * <p>
 * 默认先报告，不自动改变模组行为。经过验证后再提供适配修复（§6.5）。
 * <p>
 * 使用 ThreadLocal 跟踪当前线程的生成上下文和嵌套深度。
 */
public final class SyncChunkRequestAuditor {
    private static final SyncChunkRequestAuditor INSTANCE = new SyncChunkRequestAuditor();

    /** 最大保留事件数（环形缓冲，避免内存膨胀） */
    private static final int MAX_EVENTS = 256;

    /** 当前线程的生成上下文（正在生成的区块位置） */
    private static final ThreadLocal<GenerationContext> currentContext = new ThreadLocal<>();
    /** 当前线程的嵌套深度 */
    private static final ThreadLocal<AtomicInteger> nestingDepth = ThreadLocal.withInitial(AtomicInteger::new);

    private final ConcurrentLinkedQueue<SyncRequestEvent> events = new ConcurrentLinkedQueue<>();
    /** 按调用方 modid 统计请求次数 */
    private final ConcurrentHashMap<String, AtomicInteger> modidCounts = new ConcurrentHashMap<>();
    private volatile boolean enabled = false;

    private SyncChunkRequestAuditor() {
    }

    public static SyncChunkRequestAuditor getInstance() {
        return INSTANCE;
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 进入生成上下文时调用（Mixin 钩子在阶段开始时设置）。
     *
     * @param generatingChunkPos 当前正在生成的区块
     * @param targetStatus       目标阶段
     */
    public void enterGeneration(ChunkPos generatingChunkPos, ChunkStatus targetStatus) {
        if (!enabled) {
            return;
        }
        currentContext.set(new GenerationContext(generatingChunkPos, targetStatus));
        nestingDepth.get().set(0);
    }

    /**
     * 退出生成上下文时调用。
     */
    public void exitGeneration() {
        if (!enabled) {
            return;
        }
        currentContext.remove();
        nestingDepth.get().set(0);
    }

    /**
     * 记录一次同步区块请求。
     * <p>
     * 由 Mixin 钩子在 {@code getChunk} / {@code getChunkForLighting} 等同步入口调用。
     *
     * @param requestedChunkPos 被请求的区块
     * @param requestedStatus   目标 ChunkStatus
     * @param blocking          是否阻塞等待
     * @param callerStack       调用方堆栈（用于推断 modid）
     */
    public void recordSyncRequest(ChunkPos requestedChunkPos, ChunkStatus requestedStatus,
                                  boolean blocking, StackTraceElement[] callerStack) {
        if (!enabled) {
            return;
        }
        GenerationContext ctx = currentContext.get();
        if (ctx == null) {
            // 非生成线程中的同步请求，不记录
            return;
        }
        int depth = nestingDepth.get().incrementAndGet();
        String modid = inferModid(callerStack);

        // 检测依赖环：被请求区块等于当前生成区块
        boolean cycle = ctx.generatingChunkPos().equals(requestedChunkPos);

        SyncRequestEvent event = new SyncRequestEvent(
                ctx.generatingChunkPos(), ctx.targetStatus(),
                requestedChunkPos, requestedStatus,
                blocking, depth, modid, cycle,
                System.nanoTime()
        );

        // 环形缓冲：超过上限移除最旧
        events.add(event);
        while (events.size() > MAX_EVENTS) {
            events.poll();
        }

        modidCounts.computeIfAbsent(modid, k -> new AtomicInteger(0)).incrementAndGet();

        // 依赖环或深度嵌套立即告警
        if (cycle || depth > 3) {
            SteadyChunks.LOGGER.warn("SteadyChunks 同步区块请求告警: {} (深度={}, 环={})",
                    event, depth, cycle);
        }

        nestingDepth.get().decrementAndGet();
    }

    /**
     * 从堆栈推断调用方 modid。
     * 找到第一个非 minecraft / neoforged / steadychunks 包的类。
     */
    private String inferModid(StackTraceElement[] stack) {
        if (stack == null) {
            return "unknown";
        }
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (cls.startsWith("net.minecraft.") || cls.startsWith("net.neoforged.")
                    || cls.startsWith("com.mochi_753.steadychunks.")
                    || cls.startsWith("java.")) {
                continue;
            }
            // 提取包路径的第一段作为 modid 候选
            int firstDot = cls.indexOf('.');
            if (firstDot > 0) {
                return cls.substring(0, firstDot);
            }
            return cls;
        }
        return "minecraft";
    }

    /**
     * 获取所有审计事件（诊断导出用）。
     */
    public List<SyncRequestEvent> events() {
        return List.copyOf(events);
    }

    /**
     * 按 modid 统计同步请求次数。
     */
    public ConcurrentHashMap<String, Integer> modidCounts() {
        ConcurrentHashMap<String, Integer> out = new ConcurrentHashMap<>();
        modidCounts.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public void clear() {
        events.clear();
        modidCounts.clear();
    }

    /** 当前线程的生成上下文 */
    private record GenerationContext(
            ChunkPos generatingChunkPos,
            ChunkStatus targetStatus
    ) {
    }

    /** 同步区块请求事件（不可变） */
    public record SyncRequestEvent(
            ChunkPos generatingChunkPos,
            ChunkStatus generatingStatus,
            ChunkPos requestedChunkPos,
            ChunkStatus requestedStatus,
            boolean blocking,
            int nestingDepth,
            String callerModid,
            boolean dependencyCycle,
            long timestampNanos
    ) {
    }
}
