package com.mochi_753.steadychunks.mixin.server;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ChunkMap} 私有字段（审查 P0-2 / P1-1 修复）。
 * <p>
 * worldgenMailbox 是原版世界生成任务的调度入口（{@code ChunkMap.runGenerationTask}
 * 通过 {@code worldgenMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable))}
 * 提交 ChunkGenerationTask）。延迟恢复时通过同一 mailbox 提交，
 * 可严格保留原执行器、原线程模型与原调用链时序。
 * <p>
 * level 字段（包私有）用于 P1-1 维度级定向取消：从生成上下文提取
 * {@code ServerLevel.dimension()} 作为任务所属维度。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("worldgenMailbox")
    ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> steady$worldgenMailbox();

    /** P1-1（第 6 轮）：暴露包私有的 level 字段，用于提取任务所属维度 */
    @Accessor("level")
    ServerLevel steady$level();
}
