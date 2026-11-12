package com.mochi_753.steadychunks.compat;

import com.mochi_753.steadychunks.SteadyChunks;
import net.neoforged.fml.ModList;

/**
 * C2ME 互斥策略，对应开发计划 §10.5 与 ADR-002。
 * <p>
 * 检测到 C2ME：
 * <ul>
 *   <li>默认阻止游戏启动并给出清晰错误</li>
 *   <li>开发模式可允许只启用分析器，但不得同时启用调度和区块系统替换</li>
 *   <li>文档提供迁移步骤和配置替代关系</li>
 * </ul>
 * <p>
 * 当前实现复用 {@code ModuleBootstrap.onCommonSetup} 中的互斥逻辑，
 * 本类提供独立策略查询与迁移文档输出，便于后续扩展与测试。
 */
public final class C2meMutexPolicy {
    private static final C2meMutexPolicy INSTANCE = new C2meMutexPolicy();

    /** C2ME 已测试版本范围（用于版本门控，超出范围进入保守模式） */
    private static final String C2ME_KNOWN_VERSION_MIN = "0.2.0";
    private static final String C2ME_KNOWN_VERSION_MAX = "0.5.0";

    private C2meMutexPolicy() {
    }

    public static C2meMutexPolicy getInstance() {
        return INSTANCE;
    }

    /**
     * 评估 C2ME 互斥策略。
     * <p>
     * 由 {@code ModuleBootstrap.onCommonSetup} 调用，返回应采取的决策。
     *
     * @param mode 用户配置的 C2ME 互斥模式
     * @return 互斥决策
     */
    public C2meDecision evaluate(C2meMode mode) {
        boolean c2mePresent = ModList.get().isLoaded("c2me");
        if (!c2mePresent) {
            return new C2meDecision(Action.CONTINUE, "未检测到 C2ME，正常启动");
        }

        String c2meVersion = getModVersion("c2me");
        boolean versionKnown = isVersionInRange(c2meVersion, C2ME_KNOWN_VERSION_MIN, C2ME_KNOWN_VERSION_MAX);

        return switch (mode) {
            case REJECT -> {
                String msg = buildRejectMessage(c2meVersion, versionKnown);
                yield new C2meDecision(Action.BLOCK_STARTUP, msg);
            }
            case ANALYZER_ONLY -> {
                String msg = "SteadyChunks 检测到 C2ME（v" + c2meVersion + "），配置为 analyzer_only。" +
                        "仅启用诊断器，不启用调度与区块系统替换。" +
                        (versionKnown ? "" : " 注意：C2ME 版本超出已测试范围，可能不稳定。");
                yield new C2meDecision(Action.ANALYZER_ONLY, msg);
            }
            case FORCE_COEXIST -> {
                String msg = "SteadyChunks 检测到 C2ME（v" + c2meVersion + "），配置为 force_coexist。" +
                        "此模式仅供开发对照测试，不承诺正确性。" +
                        (versionKnown ? "" : " 警告：C2ME 版本超出已测试范围，风险极高。");
                yield new C2meDecision(Action.FORCE_COEXIST, msg);
            }
        };
    }

    /**
     * 构建 REJECT 模式的错误消息。
     */
    private String buildRejectMessage(String c2meVersion, boolean versionKnown) {
        StringBuilder sb = new StringBuilder();
        sb.append("SteadyChunks 检测到 C2ME（v").append(c2meVersion).append("）。");
        sb.append("SteadyChunks 替代 C2ME 的区块调度与区块系统，不支持共存。");
        sb.append("请移除 C2ME 或 SteadyChunks。");
        sb.append("若需强制共存（开发测试用），配置 [compatibility] c2me = \"force_coexist\"。");
        sb.append("若只需诊断器，配置 [compatibility] c2me = \"analyzer_only\"。");
        sb.append("\n\n迁移指南：");
        sb.append("\n  1. 移除 C2ME（推荐）：SteadyChunks 提供等效调度与稳定性优化");
        sb.append("\n  2. C2ME 调度 → SteadyChunks 调度器（[scheduler] enabled = true）");
        sb.append("\n  3. C2ME 并发管理 → SteadyChunks 阶段限制器（[stage_limits]）");
        sb.append("\n  4. C2ME I/O 优化 → SteadyChunks I/O 队列（Phase 9）");
        sb.append("\n  5. C2ME 诊断 → SteadyChunks Flight Recorder（[telemetry]）");
        if (!versionKnown) {
            sb.append("\n\n注意：检测到的 C2ME 版本超出已测试范围（").append(C2ME_KNOWN_VERSION_MIN)
                    .append("-").append(C2ME_KNOWN_VERSION_MAX).append("），行为可能异常。");
        }
        return sb.toString();
    }

    /**
     * 输出迁移文档到日志（供用户参考）。
     */
    public void logMigrationGuide() {
        SteadyChunks.LOGGER.info("=== SteadyChunks 迁移指南（从 C2ME）===");
        SteadyChunks.LOGGER.info("  C2ME 调度 → SteadyChunks 调度器 [scheduler] enabled = true");
        SteadyChunks.LOGGER.info("  C2ME 并发管理 → SteadyChunks 阶段限制 [stage_limits]");
        SteadyChunks.LOGGER.info("  C2ME I/O 优化 → SteadyChunks I/O 队列（Phase 9）");
        SteadyChunks.LOGGER.info("  C2ME 诊断 → SteadyChunks Flight Recorder [telemetry]");
        SteadyChunks.LOGGER.info("  C2ME noop_chunk_serialization → Bye-Pregen 序列化（推荐）");
        SteadyChunks.LOGGER.info("  C2ME fixes.worldgen_thread_impl → SteadyChunks 调度器统一管理");
        SteadyChunks.LOGGER.info("=== End Migration Guide ===");
    }

    private String getModVersion(String modid) {
        return ModList.get().getModContainerById(modid)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /**
     * 版本范围检查。
     */
    private boolean isVersionInRange(String actual, String min, String max) {
        if (actual == null || actual.equals("unknown")) {
            return false;
        }
        try {
            return compareVersion(actual, min) >= 0 && compareVersion(actual, max) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private int compareVersion(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? Integer.parseInt(pa[i]) : 0;
            int vb = i < pb.length ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    /** 互斥动作 */
    public enum Action {
        /** 正常启动（未检测到 C2ME） */
        CONTINUE,
        /** 阻止启动（REJECT 模式） */
        BLOCK_STARTUP,
        /** 仅启用分析器（ANALYZER_ONLY 模式） */
        ANALYZER_ONLY,
        /** 强制共存（FORCE_COEXIST 模式，开发测试用） */
        FORCE_COEXIST
    }

    /** C2ME 互斥决策 */
    public record C2meDecision(Action action, String message) {
        /**
         * 是否应阻止启动。
         */
        public boolean shouldBlockStartup() {
            return action == Action.BLOCK_STARTUP;
        }
    }

    /** C2ME 互斥模式（与 CommonConfig.C2meMode 对齐） */
    public enum C2meMode {
        REJECT,
        ANALYZER_ONLY,
        FORCE_COEXIST
    }
}
