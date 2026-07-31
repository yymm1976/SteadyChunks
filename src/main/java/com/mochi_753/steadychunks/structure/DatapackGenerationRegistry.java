package com.mochi_753.steadychunks.structure;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数据包 generation 统一注册中心，对应技术指导 §17.2。
 * <p>
 * 集中管理数据包重载和维度卸载时的缓存失效通知，避免失效逻辑分散到各 Mixin 或事件处理器。
 * <p>
 * 设计要点（§17.2）：
 * <ul>
 *   <li>每次 reload 递增全局 generation，旧缓存即使未立即清理也不再命中</li>
 *   <li>使用 Listener 模式解耦，各缓存自实现失效逻辑</li>
 *   <li>reload 完成后异步回收旧 generation 数据（当前版本同步清空，未来可扩展异步）</li>
 *   <li>维度卸载按维度精确失效，避免全量清空影响其他维度</li>
 * </ul>
 * <p>
 * 线程安全：listeners 使用 {@link CopyOnWriteArrayList}，generation 使用 {@link AtomicLong}。
 * 失效通知在主线程调用（数据包重载事件线程），避免并发问题。
 */
public final class DatapackGenerationRegistry {
    private static final DatapackGenerationRegistry INSTANCE = new DatapackGenerationRegistry();

    /** 全局数据包 generation，每次 reload 递增 */
    private final AtomicLong datapackGeneration = new AtomicLong(0);
    /** 注册的缓存失效监听器列表（CopyOnWriteArrayList 保证遍历安全且支持 addIfAbsent） */
    private final CopyOnWriteArrayList<InvalidationListener> listeners = new CopyOnWriteArrayList<>();
    /** 累计触发 reload 次数（诊断用） */
    private final AtomicLong reloadCount = new AtomicLong(0);
    /** 累计触发维度卸载次数（诊断用） */
    private final AtomicLong dimensionUnloadCount = new AtomicLong(0);

    private DatapackGenerationRegistry() {
    }

    public static DatapackGenerationRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册缓存失效监听器。
     * <p>
     * 应在缓存单例构造时调用，确保后续 reload 事件能被通知。
     *
     * @param listener 监听器实例
     */
    public void register(InvalidationListener listener) {
        if (listener == null) {
            return;
        }
        listeners.addIfAbsent(listener);
    }

    /**
     * 取消注册监听器（主要用于测试或模块卸载）。
     */
    public void unregister(InvalidationListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取当前数据包 generation。
     * <p>
     * 缓存键应包含此值，旧 generation 的缓存条目不再被命中。
     */
    public long currentGeneration() {
        return datapackGeneration.get();
    }

    /**
     * §17.2 数据包重载时调用：递增 generation 并触发所有注册缓存的失效。
     * <p>
     * 单个监听器异常不影响其他监听器（本地 catch + log，避免级联失败）。
     */
    public void fireDatapackReload() {
        long newGen = datapackGeneration.incrementAndGet();
        reloadCount.incrementAndGet();
        SteadyChunks.LOGGER.info(
                "SteadyChunks 缓存失效：datapack reload generation={} listeners={}",
                newGen, listeners.size());
        for (InvalidationListener listener : listeners) {
            try {
                listener.onDatapackReload(newGen);
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks 缓存失效监听器异常 listener={} cause={}",
                        listener.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    /**
     * §17.1 维度卸载时调用：触发该维度相关缓存的按维度失效。
     * <p>
     * 各缓存根据自身能力决定是否支持按维度精确失效：
     * <ul>
     *   <li>支持：清除该维度条目，保留其他维度</li>
     *   <li>不支持：可选择全量清空或忽略（由缓存实现决定）</li>
     * </ul>
     */
    public void fireDimensionUnload(ResourceKey<Level> dimension) {
        if (dimension == null) {
            return;
        }
        dimensionUnloadCount.incrementAndGet();
        SteadyChunks.LOGGER.info(
                "SteadyChunks 缓存失效：维度卸载 dim={} listeners={}",
                dimension.location(), listeners.size());
        for (InvalidationListener listener : listeners) {
            try {
                listener.onDimensionUnload(dimension);
            } catch (Throwable t) {
                SteadyChunks.LOGGER.warn(
                        "SteadyChunks 维度卸载监听器异常 listener={} cause={}",
                        listener.getClass().getSimpleName(), t.getMessage());
            }
        }
    }

    // 诊断访问器
    public int listenerCount() { return listeners.size(); }
    public long reloadCount() { return reloadCount.get(); }
    public long dimensionUnloadCount() { return dimensionUnloadCount.get(); }

    /**
     * 缓存失效监听器接口。
     * <p>
     * 各缓存实现此接口并在 {@link DatapackGenerationRegistry#register} 注册，
     * 由 Registry 在数据包重载或维度卸载时统一通知。
     */
    public interface InvalidationListener {
        /**
         * 数据包重载时调用。
         *
         * @param newGeneration 新的 generation 值，缓存键应使用此值
         */
        void onDatapackReload(long newGeneration);

        /**
         * 维度卸载时调用。
         *
         * @param dimension 已卸载的维度 key
         */
        void onDimensionUnload(ResourceKey<Level> dimension);
    }
}
