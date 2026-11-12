package com.mochi_753.steadychunks.mixin.server;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@link ChunkMap} 私有字段（审查 P0-2 修复）。
 * <p>
 * worldgenMailbox 是原版世界生成任务的调度入口（{@code ChunkMap.runGenerationTask}
 * 通过 {@code worldgenMailbox.tell(ChunkTaskPriorityQueueSorter.message(holder, runnable))}
 * 提交 ChunkGenerationTask）。延迟恢复时通过同一 mailbox 提交，
 * 可严格保留原执行器、原线程模型与原调用链时序。
 * <p>
 * level 字段用于维度级定向取消：NOISE applyStep 阶段 holder 尚未持有 chunk
 * （{@code GenerationChunkHolder.getLatestChunk()} 返回 null），无法从 holder 提取
 * 维度；ChunkMap 的 level 字段始终可用且正确。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Accessor("worldgenMailbox")
    ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> steady$worldgenMailbox();

    @Accessor("level")
    ServerLevel steady$level();
}
