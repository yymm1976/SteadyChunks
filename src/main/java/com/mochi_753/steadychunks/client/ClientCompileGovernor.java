package com.mochi_753.steadychunks.client;

import com.mochi_753.steadychunks.SteadyChunks;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可选客户端编译治理，对应开发计划 §5.5。
 * <p>
 * 仅在不与 Embeddium 等渲染优化模组冲突的前提下：
 * <ul>
 *   <li>限制同一帧提交的区块重建数</li>
 *   <li>近处和屏幕内 Section 优先</li>
 *   <li>禁止替换完整渲染器</li>
 *   <li>检测到不支持的渲染器时仅保留诊断</li>
 * </ul>
 * <p>
 * 技术指导 §5.5 风险：与渲染优化模组冲突——兼容探测和模块关闭，不注入其私有实现。
 */
public final class ClientCompileGovernor {
    private static ClientCompileGovernor instance;

    /** 检测到的渲染器类型 */
    public enum RendererType {
        /** 原版渲染器，可安全治理 */
        VANILLA,
        /** Embeddium/Rubidium，不治理 */
        EMBEDDIUM,
        /** Sodium（Fabric 兼容层），不治理 */
        SODIUM,
        /** 未知渲染器，保守不治理 */
        UNKNOWN
    }

    private volatile RendererType detectedRenderer = RendererType.VANILLA;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean governanceActive = new AtomicBoolean(false);

    /** 每帧最大 Section 重建提交数 */
    private volatile int maxSectionRebuildsPerFrame = 8;
    /** 当前帧已提交数 */
    private final AtomicInteger frameRebuildCount = new AtomicInteger(0);

    private ClientCompileGovernor() {
    }

    public static synchronized ClientCompileGovernor getInstance() {
        if (instance == null) {
            instance = new ClientCompileGovernor();
        }
        return instance;
    }

    /**
     * 检测当前渲染器类型。
     * <p>
     * 通过检查类加载器中是否存在 Embeddium/Sodium 的关键类来判断。
     */
    public void detectRenderer() {
        try {
            if (classExists("me.jellysquid.mods.sodium.client.SodiumClientMod")
                    || classExists("net.caffeinemc.mods.sodium.client.SodiumClientMod")) {
                detectedRenderer = RendererType.SODIUM;
            } else if (classExists("me.jellysquid.mods.lithium.client.LithiumClientMod")
                    || classExists("org.embeddedt.embeddium.Embeddium")
                    || classExists("me.jellysquid.mods.rubidium.client.RubidiumClientMod")) {
                detectedRenderer = RendererType.EMBEDDIUM;
            } else {
                detectedRenderer = RendererType.VANILLA;
            }
        } catch (Throwable t) {
            detectedRenderer = RendererType.UNKNOWN;
        }

        // 仅原版渲染器启用治理
        governanceActive.set(enabled.get() && detectedRenderer == RendererType.VANILLA);

        SteadyChunks.LOGGER.info("SteadyChunks 渲染器检测: {} governance={}",
                detectedRenderer, governanceActive.get());
    }

    private boolean classExists(String className) {
        try {
            Class.forName(className, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        governanceActive.set(on && detectedRenderer == RendererType.VANILLA);
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public boolean isGovernanceActive() {
        return governanceActive.get();
    }

    public RendererType detectedRenderer() {
        return detectedRenderer;
    }

    /**
     * 尝试获取 Section 重建许可（每帧调用）。
     *
     * @return true 表示允许提交重建
     */
    public boolean tryAcquireRebuild() {
        if (!governanceActive.get()) {
            return true;
        }
        return frameRebuildCount.incrementAndGet() <= maxSectionRebuildsPerFrame;
    }

    /**
     * 每帧开始时重置计数。
     */
    public void resetFrame() {
        frameRebuildCount.set(0);
    }

    public void setMaxSectionRebuildsPerFrame(int max) {
        this.maxSectionRebuildsPerFrame = max;
    }

    public int maxSectionRebuildsPerFrame() {
        return maxSectionRebuildsPerFrame;
    }

    public int currentFrameRebuilds() {
        return frameRebuildCount.get();
    }
}
