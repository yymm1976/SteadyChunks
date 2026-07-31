package com.mochi_753.steadychunks.mixin.server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mochi_753.steadychunks.scheduler.ChunkScheduler;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * NOISE 阶段准入控制 Mixin（审查 P0 修复：消除 @Invoker 递归设计）。
 * <p>
 * <b>为什么替换旧设计</b>：旧实现用 {@code @Inject(HEAD)} + {@code @Invoker} 在
 * {@code GenerationChunkHolder.applyStep} 的 HEAD 拦截后调用同一方法。但 Mixin 的
 * Invoker 并不会保留"未经注入的原始方法"，再次调用 applyStep 会重新进入 HEAD 注入，
 * 导致递归排队、无限递归或栈溢出。
 * <p>
 * <b>新设计</b>：使用 MixinExtras 的 {@link WrapOperation}，在调用点包装
 * {@code ChunkGenerationTask.scheduleChunkInLayer} 内对 {@code holder.applyStep(...)}
 * 的调用。{@code original.call(...)} 是原调用指令的替身，不会重新进入包装点，无递归。
 * <p>
 * 接入路径（审查建议的最小 NOISE 门控）：
 * <ol>
 *   <li>原版在 worldgen worker 线程调用 scheduleChunkInLayer 调度 NOISE 层</li>
 *   <li>包装点拦截 applyStep 调用：调度器启用且目标为 NOISE 时交予调度器</li>
 *   <li>调度器获得 permit 后调用 {@code original.call(...)}（原版 applyStep，线程语义不变）</li>
 *   <li>permit 不足时返回代理 Future，任务进入调度器等待队列</li>
 * </ol>
 * <p>
 * 行为：
 * <ul>
 *   <li>调度器未启用 → 原样透传（不修改任何行为，验收标准 §3）</li>
 *   <li>非 NOISE 阶段 → 原样透传（PR1 仅门控 NOISE）</li>
 *   <li>NOISE 阶段 + 调度器启用 → 交由调度器准入控制</li>
 * </ul>
 */
@Mixin(ChunkGenerationTask.class)
abstract class MixinChunkGenerationTask {

    /**
     * 包装 {@code GenerationChunkHolder.applyStep} 调用点，进行准入控制。
     * <p>
     * handler 参数顺序：被调用方法的 receiver（holder）+ 被调用方法参数
     * （step / map / cache）+ Operation（原调用替身）。
     * 签名已按 1.21.1 映射确认（{@code scheduleChunkInLayer(ChunkStatus, boolean,
     * GenerationChunkHolder)} 内调用 {@code applyStep(ChunkStep, GeneratingChunkMap,
     * StaticCache2D)}）。
     */
    @WrapOperation(
            method = "scheduleChunkInLayer",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/GenerationChunkHolder;applyStep("
                            + "Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                            + "Lnet/minecraft/server/level/GeneratingChunkMap;"
                            + "Lnet/minecraft/util/StaticCache2D;"
                            + ")Ljava/util/concurrent/CompletableFuture;"
            )
    )
    private CompletableFuture<?> steady$gateApplyStep(
            GenerationChunkHolder holder,
            ChunkStep step,
            GeneratingChunkMap map,
            StaticCache2D<GenerationChunkHolder> cache,
            Operation<CompletableFuture<?>> original) {

        ChunkScheduler scheduler = ChunkScheduler.getInstance();

        // 调度器未启用：透传原版路径（验收标准 §3）
        if (!scheduler.isEnabled()) {
            return original.call(holder, step, map, cache);
        }

        // PR1：仅门控 NOISE，其他阶段透传
        if (step.targetStatus() != ChunkStatus.NOISE) {
            return original.call(holder, step, map, cache);
        }

        // 交由调度器准入控制。
        // isDependencyUnlock=false：PR1 简化，不区分依赖解锁任务（保留额度已设为 0）。
        // 传入 map/holder 供调度器延迟恢复时通过原 worldgen mailbox 提交（P0-2 修复）。
        // @WrapOperation 返回 CompletableFuture<?>（泛型擦除），经 unchecked cast 适配。
        @SuppressWarnings("unchecked")
        CompletableFuture<ChunkResult<ChunkAccess>> gated = scheduler.controlAdmission(
                step.targetStatus(),
                false,
                map,
                holder,
                () -> (CompletableFuture<ChunkResult<ChunkAccess>>) (CompletableFuture<?>) original.call(holder, step, map, cache));
        return gated;
    }
}
