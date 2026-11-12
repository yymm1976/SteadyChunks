package com.mochi_753.steadychunks.features;

import com.mochi_753.steadychunks.SteadyChunks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StructureProcessor 能力注册表，对应开发计划 §7.3。
 * <p>
 * 维护 Processor 类（或 registry key）到 {@link ProcessorCapability} 的映射。
 * <p>
 * 判定优先级（高到低）：
 * <ol>
 *   <li>白名单显式注册（本类 {@link #register}）</li>
 *   <li>原版 Processor 默认分类（见下方静态初始化）</li>
 *   <li>默认 {@link ProcessorCapability#UNKNOWN}</li>
 * </ol>
 * <p>
 * 风险缓解（计划 §7 风险表）：Processor 被错误判断为纯函数会导致缓存污染。
 * 因此所有未注册的 Processor 一律视为 UNKNOWN，走原版安全路径。
 * <p>
 * 模组可在自己的初始化阶段调用 {@link #register} 显式声明自家 Processor 能力。
 */
public final class ProcessorCapabilityRegistry {
    private static final ProcessorCapabilityRegistry INSTANCE = new ProcessorCapabilityRegistry();

    /** 按 Processor 类名索引（处理无 registry key 的直接实例化场景） */
    private final ConcurrentHashMap<String, ProcessorCapability> byClassName = new ConcurrentHashMap<>();
    /** 按 registry key 索引（处理通过 Datapack 注册的 Processor） */
    private final ConcurrentHashMap<ResourceLocation, ProcessorCapability> byRegistryKey = new ConcurrentHashMap<>();

    private ProcessorCapabilityRegistry() {
        registerVanillaDefaults();
    }

    public static ProcessorCapabilityRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * 注册原版 Processor 的默认能力分类。
     * <p>
     * 仅注册确定无副作用的原版 Processor，其他默认 UNKNOWN。
     */
    private void registerVanillaDefaults() {
        // 原版 BlockAgeProcessor：基于随机数，依赖位置
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.BlockAgeProcessor",
                ProcessorCapability.RANDOM_DEPENDENT);
        // 原版 BlockIgnoreProcessor：根据输入方块状态过滤，纯静态
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor",
                ProcessorCapability.PURE_STATIC);
        // 原版 GravityProcessor：依赖位置（高度查询）
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.GravityProcessor",
                ProcessorCapability.POSITION_DEPENDENT);
        // 原版 BlockRotProcessor：依赖随机数
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.BlockRotProcessor",
                ProcessorCapability.RANDOM_DEPENDENT);
        // 原版 LavaSubmergingProcessor：依赖世界读取（液位查询）
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.LavaSubmergingProcessor",
                ProcessorCapability.WORLD_READ_DEPENDENT);
        // 原版 ProtectedBlockProcessor：根据方块标签过滤，纯静态
        registerByClassName("net.minecraft.world.level.levelgen.structure.templatesystem.ProtectedBlockProcessor",
                ProcessorCapability.PURE_STATIC);
    }

    /**
     * 按 Processor 类名注册能力。
     *
     * @param className Processor 完整类名
     * @param capability 能力分类
     */
    public void registerByClassName(String className, ProcessorCapability capability) {
        byClassName.put(className, capability);
        SteadyChunks.LOGGER.debug("SteadyChunks 注册 Processor 能力: {} = {}", className, capability);
    }

    /**
     * 按 registry key 注册能力（用于 Datapack 注册的 Processor）。
     *
     * @param key        Processor registry key
     * @param capability 能力分类
     */
    public void registerByRegistryKey(ResourceLocation key, ProcessorCapability capability) {
        byRegistryKey.put(key, capability);
        SteadyChunks.LOGGER.debug("SteadyChunks 注册 Processor 能力: {} = {}", key, capability);
    }

    /**
     * 查询 Processor 的能力分类。
     * <p>
     * 判定顺序：registry key 白名单 → 类名白名单 → 默认 UNKNOWN。
     *
     * @param processor 待查询的 Processor 实例
     * @param registryKey 可选的 registry key（null 表示无）
     * @return 能力分类，未注册返回 {@link ProcessorCapability#UNKNOWN}
     */
    public ProcessorCapability capabilityOf(StructureProcessor processor, ResourceLocation registryKey) {
        if (registryKey != null) {
            ProcessorCapability cap = byRegistryKey.get(registryKey);
            if (cap != null) {
                return cap;
            }
        }
        ProcessorCapability cap = byClassName.get(processor.getClass().getName());
        return cap != null ? cap : ProcessorCapability.UNKNOWN;
    }

    /**
     * 查询 Processor 的能力分类（仅按类名）。
     */
    public ProcessorCapability capabilityOf(StructureProcessor processor) {
        return capabilityOf(processor, null);
    }

    /**
     * 返回所有已注册条目（诊断导出用）。
     */
    public Map<String, ProcessorCapability> registeredByClassName() {
        return Map.copyOf(byClassName);
    }

    public Map<ResourceLocation, ProcessorCapability> registeredByRegistryKey() {
        return Map.copyOf(byRegistryKey);
    }

    /**
     * 数据包重载时调用，清除 registry key 注册（保留原版类名注册）。
     */
    public void onDatapackReload() {
        byRegistryKey.clear();
    }
}
