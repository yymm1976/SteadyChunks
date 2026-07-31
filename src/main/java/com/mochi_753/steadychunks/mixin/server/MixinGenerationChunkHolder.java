package com.mochi_753.steadychunks.mixin.server;

import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * NOISE 阶段准入控制 Mixin（审查建议的最小接入 PR）。
 * <p>
 * 拦截 {@code GenerationChunkHolder.applyStep}，在 NOISE 阶段调用
 * {@link ChunkScheduler#controlAdmission} 进行准入控制。
 * <p>
 * 审查建议的接入路径：
 * <ol>
 *   <li>原版准备提交 NOISE</li>
 *   <li>SteadyChunks 拦截 applyStep，调用 controlAdmission</li>
 *   <li>没有 permit 时留在自身等待队列（返回代理 Future）</li>
 *   <li>获得 permit 后调用原版 applyStep（通过 @Invoker）</li>
 *   <li>原 Future 完成 → 释放 PermitLease → 完成代理 Future</li>
 * </ol>
 * <p>
 * 行为：
 * <ul>
 *   <li>调度器未启用 → 透传原版路径（不修改任何行为，验收标准 §3）</li>
 *   <li>非 NOISE 阶段 → 透传原版路径（PR1 仅门控 NOISE）</li>
 *   <li>NOISE 阶段 + 调度器启用 → 交由调度器准入控制</li>
 * </ul>
 */
@Mixin(GenerationChunkHolder.class)
abstract class MixinGenerationChunkHolder {

    /**
     * 调用原版 applyStep 方法（绕过 Mixin 拦截链）。
     * <p>
     * {@code @Invoker} 生成的方法直接调用原方法体，不会触发 {@code @Inject} 拦截，
     * 避免递归调用。
     */
    @Invoker("applyStep")
    abstract CompletableFuture<ChunkResult<ChunkAccess>> steady$invokeApplyStep(
            ChunkStep step, GeneratingChunkMap map,
            StaticCache2D<GenerationChunkHolder> cache);

    /**
     * HEAD 注入：在 applyStep 执行前进行准入控制。
     * <p>
     * 调度器启用且目标为 NOISE 时，取消原版调用，交给调度器。
     * 其他情况不取消（return），走原版路径。
     */
    @Inject(method = "applyStep", at = @At("HEAD"), cancellable = true)
    private void steady$controlAdmission(
            ChunkStep step, GeneratingChunkMap map,
            StaticCache2D<GenerationChunkHolder> cache,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {

        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 调度器未启用：透传原版路径（验收标准 §3）
        if (!scheduler.isEnabled()) {
            return;
        }

        // PR1：仅门控 NOISE，其他阶段透传
        if (step.targetStatus() != ChunkStatus.NOISE) {
            return;
        }

        // 交由调度器准入控制
        // isDependencyUnlock=false：PR1 简化，不区分依赖解锁任务
        cir.setReturnValue(scheduler.controlAdmission(
                step.targetStatus(),
                false,
                () -> steady$invokeApplyStep(step, map, cache)
        ));
    }
}
