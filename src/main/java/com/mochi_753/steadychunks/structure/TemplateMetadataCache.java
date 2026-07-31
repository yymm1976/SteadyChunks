package com.mochi_753.steadychunks.structure;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jigsaw 模板元数据缓存，对应开发计划 §6.3 与技术指导 §11.3。
 * <p>
 * 对 {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate}
 * 预计算静态元数据，避免在每次 Jigsaw 展开时重复查找。
 * <p>
 * 缓存内容（仅静态只读元数据，不包含最终方块结果）：
 * <ul>
 *   <li>模板尺寸（sizeX/Y/Z）</li>
 *   <li>连接点信息（位置 + 朝向 +目标池）</li>
 *   <li>局部包围盒边界</li>
 *   <li>静态方块索引（palette 中的索引，非世界坐标）</li>
 *   <li>方块实体索引</li>
 * </ul>
 * <p>
 * 不得缓存（技术指导 §11.3）：
 * <ul>
 *   <li>经过 Rotation / Mirror / Processor / 随机数 / 世界查询后的最终方块</li>
 *   <li>依赖当前位置的 BlockPos</li>
 *   <li>依赖动态 Processor 的处理结果</li>
 *   <li>Loot 与方块实体 NBT</li>
 * </ul>
 * <p>
 * 缓存键包含 datapackGeneration，数据包重载时全量失效。
 * 线程安全：ConcurrentHashMap + 不可变 record 值。
 * <p>
 * §17.2 缓存失效统一管理：实现 {@link DatapackGenerationRegistry.InvalidationListener}
 * 并在构造时注册到 {@link DatapackGenerationRegistry}。
 */
public final class TemplateMetadataCache implements DatapackGenerationRegistry.InvalidationListener {
    private static final TemplateMetadataCache INSTANCE = new TemplateMetadataCache();

    private final ConcurrentHashMap<CacheKey, TemplateMetadata> cache = new ConcurrentHashMap<>();
    /**
     * §7.2 旋转/镜像变换表缓存。
     * 键为 (templateId, rotation, mirror, generation)，值为变换后的元数据。
     * 共 4×2=8 种组合，按需懒计算并缓存。
     */
    private final ConcurrentHashMap<TransformKey, TransformedMetadata> transformCache = new ConcurrentHashMap<>();
    private final AtomicLong datapackGeneration = new AtomicLong(0);
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong transformHits = new AtomicLong(0);
    private final AtomicLong transformMisses = new AtomicLong(0);

    private TemplateMetadataCache() {
        // §17.2 注册到统一缓存失效注册中心
        DatapackGenerationRegistry.getInstance().register(this);
    }

    public static TemplateMetadataCache getInstance() {
        return INSTANCE;
    }

    /**
     * 查询缓存的模板元数据。
     *
     * @param templateId 模板 ResourceLocation
     * @return 元数据，null 表示未缓存
     */
    public TemplateMetadata lookup(ResourceLocation templateId) {
        CacheKey key = new CacheKey(templateId, datapackGeneration.get());
        TemplateMetadata cached = cache.get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return cached;
        }
        misses.incrementAndGet();
        return null;
    }

    /**
     * 存储模板元数据到缓存。
     */
    public void store(ResourceLocation templateId, TemplateMetadata metadata) {
        CacheKey key = new CacheKey(templateId, datapackGeneration.get());
        cache.put(key, metadata);
    }

    /**
     * §7.2 查询变换后的模板元数据。
     * <p>
     * 调用方先查询基础 {@link #lookup}，若需要变换后的连接点坐标和边界，再调用此方法。
     * 命中时返回缓存的变换结果，避免每次 Jigsaw 展开重复计算。
     *
     * @param templateId 模板 ResourceLocation
     * @param rotation   旋转索引（0=NONE, 1=CW_90, 2=CW_180, 3=CCW_90）
     * @param mirror     镜像索引（0=NONE, 1=LEFT_RIGHT, 2=FRONT_BACK）
     * @return 变换后的元数据，null 表示未缓存
     */
    public TransformedMetadata lookupTransform(ResourceLocation templateId, int rotation, int mirror) {
        TransformKey key = new TransformKey(templateId, (byte) rotation, (byte) mirror, datapackGeneration.get());
        TransformedMetadata cached = transformCache.get(key);
        if (cached != null) {
            transformHits.incrementAndGet();
            return cached;
        }
        transformMisses.incrementAndGet();
        return null;
    }

    /**
     * §7.2 存储变换后的模板元数据。
     */
    public void storeTransform(ResourceLocation templateId, int rotation, int mirror, TransformedMetadata metadata) {
        TransformKey key = new TransformKey(templateId, (byte) rotation, (byte) mirror, datapackGeneration.get());
        transformCache.put(key, metadata);
    }

    /**
     * 数据包重载时调用，递增 generation 使旧缓存自动失效。
     */
    public void onDatapackReload() {
        datapackGeneration.incrementAndGet();
        cache.clear();
        transformCache.clear();
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 由 {@link DatapackGenerationRegistry#fireDatapackReload} 统一触发，
     * 委托到现有 {@link #onDatapackReload} 完成实际失效。
     */
    @Override
    public void onDatapackReload(long newGeneration) {
        onDatapackReload();
    }

    /**
     * §17.2 实现 {@link DatapackGenerationRegistry.InvalidationListener}。
     * <p>
     * 模板元数据不按维度区分（模板是全局资源），维度卸载时不清空。
     * 模板元数据只依赖数据包，不依赖维度状态。
     */
    @Override
    public void onDimensionUnload(ResourceKey<Level> dimension) {
        // 模板元数据为全局资源，维度卸载无需失效
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public int size() {
        return cache.size();
    }

    public int transformSize() {
        return transformCache.size();
    }

    public long transformHits() {
        return transformHits.get();
    }

    public long transformMisses() {
        return transformMisses.get();
    }

    public void clear() {
        cache.clear();
        transformCache.clear();
        hits.set(0);
        misses.set(0);
        transformHits.set(0);
        transformMisses.set(0);
    }

    /**
     * 缓存键：模板 ID + 数据包 generation。
     */
    private record CacheKey(
            ResourceLocation templateId,
            long datapackGeneration
    ) {
    }

    /**
     * 模板静态元数据（不可变）。
     * <p>
     * 所有坐标均为局部坐标（相对于模板原点），不包含世界坐标转换。
     */
    public record TemplateMetadata(
            ResourceLocation templateId,
            int sizeX,
            int sizeY,
            int sizeZ,
            ConnectorInfo[] connectors,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            int[] staticBlockIndices,
            int[] blockEntityIndices
    ) {
    }

    /**
     * Jigsaw 连接点信息（局部坐标 + 朝向 + 目标池）。
     */
    public record ConnectorInfo(
            int localX,
            int localY,
            int localZ,
            String facing,
            ResourceLocation targetPool,
            String name
    ) {
    }

    /**
     * §7.2 变换表缓存键。
     */
    private record TransformKey(
            ResourceLocation templateId,
            byte rotation,
            byte mirror,
            long datapackGeneration
    ) {
    }

    /**
     * §7.2 变换后的模板元数据（不可变）。
     * <p>
     * 包含应用 Rotation + Mirror 后的：
     * <ul>
     *   <li>变换后尺寸（旋转 90°时 X/Z 互换）</li>
     *   <li>变换后包围盒边界</li>
     *   <li>变换后连接点坐标与朝向</li>
     * </ul>
     * 不包含方块状态变换（palette 仍为原始索引，由调用方按需变换）。
     */
    public record TransformedMetadata(
            ResourceLocation templateId,
            byte rotation,
            byte mirror,
            int transformedSizeX,
            int transformedSizeY,
            int transformedSizeZ,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            TransformedConnector[] connectors
    ) {
    }

    /**
     * §7.2 变换后的连接点。
     */
    public record TransformedConnector(
            int localX,
            int localY,
            int localZ,
            String transformedFacing,
            ResourceLocation targetPool,
            String name
    ) {
    }
}
