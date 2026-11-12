package com.mochi_753.steadychunks.mixin.telemetry;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.SystemResourceMetrics;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端主循环 MSPT 采样，对应开发计划 §2.6。
 * <p>
 * 在 {@link MinecraftServer#tickServer} 返回时记录本 tick 耗时，作为 MSPT 样本。
 * NeoForge 1.21.1：{@code tickServer} 是主 tick 方法，每秒 20 tick。
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer {

    private long steadychunks$tickStartNanos;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void steadychunks$onTickStart(CallbackInfo ci) {
        steadychunks$tickStartNanos = System.nanoTime();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void steadychunks$onTickReturn(CallbackInfo ci) {
        if (!ChunkFlightRecorder.isEnabled()) {
            return;
        }
        try {
            long durationNanos = System.nanoTime() - steadychunks$tickStartNanos;
            SystemResourceMetrics sys = ChunkFlightRecorder.system();
            sys.recordMspt(durationNanos / 1_000_000L);
            // 采样堆与 GC 状态（原计划由 ChunkMap Mixin 承担，1.21.1 无 ServerChunkLoadingManager，合并到此处）
            sys.sampleHeap();
            // 超过 100ms 的 MSPT 视为尖峰
            if (durationNanos > 100_000_000L) {
                ChunkFlightRecorder.recordSpike(System.nanoTime(), 1L);
            }
        } catch (Throwable t) {
            SteadyChunks.LOGGER.debug("SteadyChunks MSPT 采样失败", t);
        }
    }
}
