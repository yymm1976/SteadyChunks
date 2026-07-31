package com.mochi_753.steadychunks.structure;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 数据包重载监听器，对应技术指导 §17.2。
 * <p>
 * 实现 {@link PreparableReloadListener}，在数据包 reload 完成后触发
 * {@link DatapackGenerationRegistry#fireDatapackReload} 统一失效所有注册缓存。
 * <p>
 * 注册时机：通过 {@code AddReloadListenerEvent.addListener} 在服务器资源加载阶段注册。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>在 preparation barrier 完成后触发，确保资源已就绪</li>
 *   <li>在 game executor 上执行，避免线程安全问题</li>
 *   <li>不加载任何资源，仅作为失效触发点</li>
 * </ul>
 */
public final class CacheInvalidationReloadListener implements PreparableReloadListener {
    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager resources,
                                          ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
                                          Executor background, Executor game) {
        // 等待 preparation 完成（barrier.wait(null) 返回已完成的 future），然后在 game 线程触发缓存失效
        return barrier.wait(null).thenRunAsync(
                () -> DatapackGenerationRegistry.getInstance().fireDatapackReload(),
                game
        );
    }
}
