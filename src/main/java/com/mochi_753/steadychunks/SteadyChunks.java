package com.mochi_753.steadychunks;

import com.mochi_753.steadychunks.bootstrap.ModuleBootstrap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
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
    public static final String VERSION = "0.1.0";
    public static final Logger LOGGER = LoggerFactory.getLogger("SteadyChunks");

    public SteadyChunks(IEventBus modEventBus, ModContainer modContainer) {
        // 启动引导：探测兼容性、初始化 MixinGate、注册配置、订阅 C2ME 互斥检查
        ModuleBootstrap.bootstrap(modEventBus, modContainer);
    }
}
