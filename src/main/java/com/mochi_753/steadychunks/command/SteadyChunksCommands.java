package com.mochi_753.steadychunks.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.telemetry.ChunkFlightRecorder;
import com.mochi_753.steadychunks.telemetry.ReportExporter;
import com.mochi_753.steadychunks.diagnostics.DiagnosticBundleExporter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Path;

/**
 * SteadyChunks 命令注册中心，对应开发计划 §2.7。
 * <p>
 * 命令树：{@code /steadychunks <sub>}
 * <ul>
 *   <li>{@code status} - 总览状态</li>
 *   <li>{@code profile start|stop} - 开关诊断</li>
 *   <li>{@code stages} - 阶段指标</li>
 *   <li>{@code structures} - 结构长尾</li>
 *   <li>{@code features} - FEATURES 细分</li>
 *   <li>{@code queues} - 队列深度</li>
 *   <li>{@code spikes} - 尖峰事件</li>
 *   <li>{@code export} - 导出报告</li>
 *   <li>{@code diagnostic} - 一键诊断包导出（zip）</li>
 * </ul>
 * 仅管理员可用（权限等级 2）。
 */
public final class SteadyChunksCommands {
    private SteadyChunksCommands() {
    }

    /**
     * 注册到服务端命令分发器。由 {@code ServerStarter} 在 {@code RegisterCommandsEvent} 调用。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("steadychunks")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("status").executes(SteadyChunksCommands::status))
                        .then(Commands.literal("profile")
                                .then(Commands.literal("start").executes(SteadyChunksCommands::profileStart))
                                .then(Commands.literal("stop").executes(SteadyChunksCommands::profileStop)))
                        .then(Commands.literal("stages").executes(SteadyChunksCommands::stages))
                        .then(Commands.literal("structures").executes(SteadyChunksCommands::structures))
                        .then(Commands.literal("features").executes(SteadyChunksCommands::features))
                        .then(Commands.literal("queues").executes(SteadyChunksCommands::queues))
                        .then(Commands.literal("spikes").executes(SteadyChunksCommands::spikes))
                        .then(Commands.literal("export").executes(SteadyChunksCommands::export))
                        .then(Commands.literal("diagnostic").executes(SteadyChunksCommands::diagnostic))
        );
        SteadyChunks.LOGGER.info("SteadyChunks 命令已注册");
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("=== SteadyChunks Status ==="), false);
        src.sendSuccess(() -> Component.literal("诊断: " + (ChunkFlightRecorder.isEnabled() ? "ON" : "OFF")), false);
        src.sendSuccess(() -> Component.literal("高精度: " + (ChunkFlightRecorder.isHighDetail() ? "ON" : "OFF")), false);
        src.sendSuccess(() -> Component.literal("阶段完成: " + ChunkFlightRecorder.stages().totalCompleted()), false);
        src.sendSuccess(() -> Component.literal("FULL 整合: " + ChunkFlightRecorder.fullSend().fullCommits()), false);
        src.sendSuccess(() -> Component.literal("区块发送: " + ChunkFlightRecorder.fullSend().chunkSends()), false);
        src.sendSuccess(() -> Component.literal("ProtoChunk 当前: " + ChunkFlightRecorder.system().protoChunkCurrent()
                + " (峰值 " + ChunkFlightRecorder.system().protoChunkPeak() + ")"), false);
        return 1;
    }

    private static int profileStart(CommandContext<CommandSourceStack> ctx) {
        ChunkFlightRecorder.setEnabled(true);
        ctx.getSource().sendSuccess(() -> Component.literal("SteadyChunks 诊断已开启"), true);
        return 1;
    }

    private static int profileStop(CommandContext<CommandSourceStack> ctx) {
        ChunkFlightRecorder.setEnabled(false);
        ctx.getSource().sendSuccess(() -> Component.literal("SteadyChunks 诊断已关闭"), true);
        return 1;
    }

    private static int stages(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("=== 阶段指标 ==="), false);
        for (var b : ChunkFlightRecorder.stages().allBuckets()) {
            ChunkStatus s = b.status;
            if (b.completed() == 0) {
                continue;
            }
            src.sendSuccess(() -> Component.literal(String.format(
                    "%s: 完成=%d P99=%.2fms 最大=%.2fms 失败=%d 取消=%d",
                    s, b.completed(),
                    b.execution.quantile(0.99) / 1_000_000.0,
                    b.execution.maxNanos() / 1_000_000.0,
                    b.failed.sum(), b.cancelled.sum())), false);
        }
        return 1;
    }

    private static int structures(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("=== 结构长尾 Top 10（按 P99）==="), false);
        ChunkFlightRecorder.structures().allBuckets().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().duration.quantile(0.99), a.getValue().duration.quantile(0.99)))
                .limit(10)
                .forEach(e -> {
                    var b = e.getValue();
                    if (b.candidates.sum() == 0) {
                        return;
                    }
                    src.sendSuccess(() -> Component.literal(String.format(
                            "%s: 候选=%d 成功=%d P99=%.2fms 最大=%.2fms Piece=%d",
                            e.getKey(), b.candidates.sum(), b.starts.sum(),
                            b.duration.quantile(0.99) / 1_000_000.0,
                            b.duration.maxNanos() / 1_000_000.0,
                            b.pieces.sum())), false);
                });
        return 1;
    }

    private static int features(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("=== FEATURES 细分 ==="), false);
        src.sendSuccess(() -> Component.literal("总执行: " + ChunkFlightRecorder.features().totalPlaced()), false);
        src.sendSuccess(() -> Component.literal("跨区块读: " + ChunkFlightRecorder.features().totalCrossChunkReads()), false);
        src.sendSuccess(() -> Component.literal("跨区块写: " + ChunkFlightRecorder.features().totalCrossChunkWrites()), false);
        // modid 分配 Top 5
        ChunkFlightRecorder.features().modidAllocations().entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .forEach(e -> src.sendSuccess(() -> Component.literal(
                        "  " + e.getKey() + ": " + (e.getValue().sum() / 1024) + " KB 分配"), false));
        return 1;
    }

    private static int queues(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var sys = ChunkFlightRecorder.system();
        var fs = ChunkFlightRecorder.fullSend();
        src.sendSuccess(() -> Component.literal("=== 队列深度 ==="), false);
        src.sendSuccess(() -> Component.literal("工作队列: " + sys.workerQueueDepth()), false);
        src.sendSuccess(() -> Component.literal("ProtoChunk: " + sys.protoChunkCurrent() + " (峰值 " + sys.protoChunkPeak() + ")"), false);
        src.sendSuccess(() -> Component.literal("FULL 队列: " + fs.fullQueueDepthCurrent() + " (峰值 " + fs.fullQueueDepthPeak() + ")"), false);
        src.sendSuccess(() -> Component.literal("发送队列: " + fs.sendQueueDepthCurrent() + " (峰值 " + fs.sendQueueDepthPeak() + ")"), false);
        return 1;
    }

    private static int spikes(CommandContext<CommandSourceStack> ctx) {
        var buf = ChunkFlightRecorder.spikeBuffer();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "尖峰事件: 总数=" + buf.totalCount() + " 溢出=" + buf.overflowCount() + " 容量=" + buf.capacity()), false);
        return 1;
    }

    private static int export(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            Path gameDir = src.getServer().getServerDirectory().toAbsolutePath();
            Path outDir = gameDir.resolve("steadychunks-reports");
            Path[] files = ReportExporter.export(outDir);
            src.sendSuccess(() -> Component.literal("报告已导出: " + files[0].getFileName() + " / " + files[1].getFileName()), true);
        } catch (Exception e) {
            SteadyChunks.LOGGER.error("SteadyChunks 报告导出失败", e);
            src.sendFailure(Component.literal("导出失败: " + e.getMessage()));
        }
        return 1;
    }

    /**
     * §12.3 一键诊断包导出：打包报告、配置、兼容性表为 zip。
     */
    private static int diagnostic(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            Path gameDir = src.getServer().getServerDirectory().toAbsolutePath();
            Path outDir = gameDir.resolve("steadychunks-reports");
            Path zip = DiagnosticBundleExporter.export(outDir);
            src.sendSuccess(() -> Component.literal("诊断包已导出: " + zip.getFileName()), true);
        } catch (Exception e) {
            SteadyChunks.LOGGER.error("SteadyChunks 诊断包导出失败", e);
            src.sendFailure(Component.literal("导出失败: " + e.getMessage()));
        }
        return 1;
    }
}
