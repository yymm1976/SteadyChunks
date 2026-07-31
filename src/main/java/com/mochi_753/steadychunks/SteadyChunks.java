package com.mochi_753.steadychunks;

import com.mochi_753.steadychunks.bootstrap.ModuleBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SteadyChunks 主入口。
 * <p>
 * 项目目标：以跑图过程的帧时间与 MSPT 稳定性为第一指标，替代 C2ME 的区块生成、调度、加载与完成链路；
 * 与 FastNoise、Bye-Pregen 共存并避免重复实现其优势模块。详见
 * {@code SteadyChunks_1.21.1_NeoForge_完整开发计划.md}。
 * <p>
 * 当前阶段：Phase 0 完成（源码审计 + ADR + 兼容性边界）；Phase 1 进行中（工程骨架 + bootstrap）。
 * 实际模块装配在 {@link ModuleBootstrap#bootstrap} 中完成。
 */
@Mod(SteadyChunks.MOD_ID)
public class SteadyChunks {
    public static final String MOD_ID = "steadychunks";
    public static final Logger LOGGER = LoggerFactory.getLogger("SteadyChunks");

    public SteadyChunks(IEventBus modEventBus, ModContainer modContainer) {
        // 启动引导：探测兼容性、初始化 MixinGate、注册配置、订阅 C2ME 互斥检查
        ModuleBootstrap.bootstrap(modEventBus, modContainer);
    }

    /**
     * P2-18 修复：版本来源统一。
     * <p>
     * 从 {@link ModList} 读取模组实际加载版本，与 gradle.properties 的
     * {@code mod_version}（当前 0.0.1-alpha.0）保持一致，避免硬编码。
     * 调用时机保证在 FML 完成模组元数据加载后（构造器之后）。
     *
     * @return 模组版本字符串，读取失败时返回 "unknown"
     */
    public static String version() {
        return ModList.get()
                .getModContainerById(MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }
}
