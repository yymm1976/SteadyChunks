package com.mochi_753.steadychunks.features;

/**
 * StructureProcessor 能力分类，对应开发计划 §7.3。
 * <p>
 * 为 {@link net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor}
 * 建立能力描述，决定是否允许强缓存其处理结果。
 * <p>
 * 风险缓解（计划 §7 风险表）：Processor 被错误判断为纯函数会导致缓存污染。
 * 因此默认值为 {@link #UNKNOWN}，只有白名单或明确接口标记才提升等级。
 */
public enum ProcessorCapability {
    /**
     * 纯静态：输出仅依赖输入方块信息与 Processor 自身配置，不依赖位置、世界、随机数。
     * <p>
     * 允许强缓存处理结果（按 palette 索引）。
     */
    PURE_STATIC,

    /**
     * 依赖位置：输出依赖 BlockPos（如基于坐标的伪随机或地形适配）。
     * <p>
     * 不允许强缓存，但可优化查找路径。
     */
    POSITION_DEPENDENT,

    /**
     * 依赖世界读取：需要查询世界状态（邻块、群系、高度等）。
     * <p>
     * 不允许缓存，需保证 WorldGenRegion 内合法访问。
     */
    WORLD_READ_DEPENDENT,

    /**
     * 依赖随机数：使用 WorldGenRandom 或 RandomSource。
     * <p>
     * 严禁缓存，缓存会破坏世界生成确定性。
     */
    RANDOM_DEPENDENT,

    /**
     * 依赖世界写入：在处理过程中修改世界（罕见，但某些模组会这样做）。
     * <p>
     * 严禁缓存，且需特别注意线程安全。
     */
    WORLD_WRITE_DEPENDENT,

    /**
     * 未知能力：默认值，走原版安全路径，不缓存不优化。
     * <p>
     * 所有非白名单 Processor 一律视为 UNKNOWN。
     */
    UNKNOWN;

    /**
     * 判断此能力是否允许强缓存处理结果。
     * <p>
     * 只有 {@link #PURE_STATIC} 允许强缓存。
     */
    public boolean allowStrongCache() {
        return this == PURE_STATIC;
    }

    /**
     * 判断此能力是否允许使用短生命周期缓存（单次 FEATURES 内）。
     * <p>
     * {@link #PURE_STATIC} 和 {@link #POSITION_DEPENDENT} 允许（POSITION_DEPENDENT 按位置键缓存）。
     */
    public boolean allowShortLivedCache() {
        return this == PURE_STATIC || this == POSITION_DEPENDENT;
    }

    /**
     * 判断此能力是否需要世界读取同步保护。
     */
    public boolean requiresWorldReadGuard() {
        return this == WORLD_READ_DEPENDENT || this == WORLD_WRITE_DEPENDENT || this == UNKNOWN;
    }
}
