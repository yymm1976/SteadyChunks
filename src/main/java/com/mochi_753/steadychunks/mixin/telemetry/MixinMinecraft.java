package com.mochi_753.steadychunks.mixin.telemetry;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.ThreadInstrumentation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端帧时间采样，对应开发计划 §2.5（客户端部分）与 §2.6。
 * <p>
 * 在 {@link Minecraft#tick} 或渲染循环中采样帧时间。
 * NeoForge 1.21.1：{@code Minecraft.runTick} 是主循环入口。
 * <p>
 * 仅客户端应用，混入配置在 {@code steadychunks.mixins.json} 的 client 数组声明。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    private long steadychunks$frameStartNanos;

    @Inject(method = "runTick", at = @At("HEAD"))
    private void steadychunks$onRunTickStart(CallbackInfo ci) {
        steadychunks$frameStartNanos = System.nanoTime();
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void steadychunks$onRunTickReturn(CallbackInfo ci) {
        if (!ChunkFlightRecorder.isEnabled()) {
            return;
        }
        try {
            long frameNanos = System.nanoTime() - steadychunks$frameStartNanos;
            ChunkFlightRecorder.clientFrames().recordFrame(frameNanos);
            // 超过 50ms 的帧视为尖峰
            if (frameNanos > 50_000_000L) {
                ChunkFlightRecorder.recordSpike(System.nanoTime(), 2L);
            }
        } catch (Throwable t) {
            SteadyChunks.LOGGER.debug("SteadyChunks 帧时间采样失败", t);
        }
    }
}
