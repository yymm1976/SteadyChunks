package com.mochi_753.steadychunks.telemetry;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * 区块阶段（{@link ChunkStatus}）计时工作项。
 * <p>
 * 由 Mixin 钩子推入 {@link ThreadInstrumentation}，记录从阶段开始到完成的耗时。
 * 对应开发计划 §2.2 的"逐阶段采集：排队时间、执行时间、Future 等待时间"。
 * <p>
 * NeoForge 1.21.1 机制：ChunkStatus 是区块生命周期的阶段标识，
 * 链路为 EMPTY→STRUCTURE_STARTS→STRUCTURE_REFERENCES→BIOMES→NOISE→SURFACE→CARVERS→FEATURES→INITIALIZE_LIGHT→LIGHT→SPAWN→FULL。
 * 区块需按此顺序推进，后一阶段依赖前一阶段的完成。
 */
public record StageWork(
        ChunkPos pos,
        ChunkStatus status,
        long queueEnterNanos
) implements RunningWork {
    @Override
    public String describe() {
        return String.format("chunk %s %s (queued@%d)", pos, status, queueEnterNanos);
    }
}
