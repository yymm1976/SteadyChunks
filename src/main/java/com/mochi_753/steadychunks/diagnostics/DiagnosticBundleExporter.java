package com.mochi_753.steadychunks.diagnostics;

import com.mochi_753.steadychunks.SteadyChunks;
import com.mochi_753.steadychunks.compat.MixinConflictTable;
import com.mochi_753.steadychunks.compat.OwnershipTableRegistry;
import com.mochi_753.steadychunks.telemetry.ReportExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 一键诊断包导出器，对应开发计划 §12.3 RC。
 * <p>
 * 将 SteadyChunks 全部诊断信息打包为单个 zip 文件，便于用户提交问题报告：
 * <ul>
 *   <li>Flight Recorder 报告（Markdown + JSON）</li>
 *   <li>当前配置快照（toml）</li>
 *   <li>所有权表与 Mixin 冲突表（txt）</li>
 *   <li>崩溃报告贡献器状态快照（txt）</li>
 * </ul>
 * 输出到游戏目录下 {@code steadychunks-reports/} 子目录。
 */
public final class DiagnosticBundleExporter {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private DiagnosticBundleExporter() {
    }

    /**
     * 导出诊断包到指定目录。
     *
     * @param outputDir 输出目录
     @return 生成的 zip 文件路径
     */
    public static Path export(Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        String ts = LocalDateTime.now().format(TS_FMT);
        Path zipPath = outputDir.resolve("steadychunks-diagnostic-" + ts + ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            // 1. Flight Recorder 报告（先导出到临时目录再写入 zip）
            Path[] reports = ReportExporter.export(outputDir);
            addFileToZip(zos, reports[0], "report.md");
            addFileToZip(zos, reports[1], "report.json");

            // 2. 配置快照
            addEntry(zos, "config-snapshot.txt", buildConfigSnapshot());

            // 3. 所有权表
            addEntry(zos, "ownership-table.txt", buildOwnershipTable());

            // 4. Mixin 冲突表
            addEntry(zos, "mixin-conflicts.txt", buildMixinConflicts());

            // 5. 运行时状态快照（与崩溃报告中追加的内容一致）
            addEntry(zos, "runtime-state.txt", CrashReportContributor.collectState());
        }

        SteadyChunks.LOGGER.info("SteadyChunks 诊断包已导出: {}", zipPath);
        return zipPath;
    }

    private static void addFileToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
    }

    private static void addEntry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String buildConfigSnapshot() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SteadyChunks 配置快照\n");
        sb.append("生成时间: ").append(LocalDateTime.now()).append("\n\n");
        sb.append("配置值通过运行时访问器读取（确保反映实际生效值）\n\n");
        try {
            sb.append("[general]\n");
            sb.append("enabled = ").append(com.mochi_753.steadychunks.config.CommonConfig.ENABLED.get()).append('\n');
            sb.append("preset = ").append(com.mochi_753.steadychunks.config.CommonConfig.PRESET.get()).append('\n');
            sb.append("strict_compatibility = ").append(com.mochi_753.steadychunks.config.CommonConfig.STRICT_COMPATIBILITY.get()).append('\n');
            sb.append('\n').append("[compatibility]\n");
            sb.append("fastnoise = ").append(com.mochi_753.steadychunks.config.CommonConfig.FASTNOISE.get()).append('\n');
            sb.append("byepregen = ").append(com.mochi_753.steadychunks.config.CommonConfig.BYEPREGEN.get()).append('\n');
            sb.append("c2me = ").append(com.mochi_753.steadychunks.config.CommonConfig.C2ME.get()).append('\n');
            sb.append("unknown_chunk_system = ").append(com.mochi_753.steadychunks.config.CommonConfig.UNKNOWN_CHUNK_SYSTEM.get()).append('\n');
            sb.append('\n').append("[scheduler]\n");
            sb.append("enabled = ").append(com.mochi_753.steadychunks.config.CommonConfig.SCHEDULER_ENABLED.get()).append('\n');
            sb.append("max_inflight = ").append(com.mochi_753.steadychunks.config.CommonConfig.MAX_INFLIGHT.get()).append('\n');
            sb.append('\n').append("[governor]\n");
            sb.append("enabled = ").append(com.mochi_753.steadychunks.config.CommonConfig.GOVERNOR_ENABLED.get()).append('\n');
            sb.append("target_p95_mspt = ").append(com.mochi_753.steadychunks.config.CommonConfig.TARGET_P95_MSPT.get()).append('\n');
            sb.append("hard_mspt = ").append(com.mochi_753.steadychunks.config.CommonConfig.HARD_MSPT.get()).append('\n');
            sb.append('\n').append("[completion]\n");
            sb.append("enabled = ").append(com.mochi_753.steadychunks.config.CommonConfig.COMPLETION_ENABLED.get()).append('\n');
            sb.append("full_max_commits_per_tick = ").append(com.mochi_753.steadychunks.config.CommonConfig.FULL_MAX_COMMITS_PER_TICK.get()).append('\n');
            sb.append('\n').append("[send]\n");
            sb.append("enabled = ").append(com.mochi_753.steadychunks.config.CommonConfig.SEND_QUOTA_ENABLED.get()).append('\n');
            sb.append("max_chunks_per_tick = ").append(com.mochi_753.steadychunks.config.CommonConfig.SEND_MAX_CHUNKS_PER_TICK.get()).append('\n');
        } catch (Throwable t) {
            sb.append("配置采集失败: ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String buildOwnershipTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SteadyChunks 所有权表\n\n");
        try {
            var entries = OwnershipTableRegistry.getInstance().snapshot();
            sb.append("| 模块 | 所有者 | 版本 | 激活 |\n");
            sb.append("|---|---|---|---|\n");
            for (var e : entries) {
                sb.append("| ").append(e.module())
                        .append(" | ").append(e.owner())
                        .append(" | ").append(e.version())
                        .append(" | ").append(e.active())
                        .append(" |\n");
            }
        } catch (Throwable t) {
            sb.append("所有权表采集失败: ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private static String buildMixinConflicts() {
        StringBuilder sb = new StringBuilder();
        sb.append("# SteadyChunks Mixin 冲突表\n\n");
        try {
            var entries = MixinConflictTable.getInstance().snapshot();
            if (entries.isEmpty()) {
                sb.append("无冲突记录\n");
            } else {
                sb.append("| 目标类 | 目标方法 | FastNoise | Bye-Pregen | SteadyChunks | 顺序 | 回退 |\n");
                sb.append("|---|---|---|---|---|---|---|\n");
                for (var e : entries) {
                    sb.append("| ").append(e.targetClass())
                            .append(" | ").append(e.targetMethod())
                            .append(" | ").append(e.fastNoiseInject())
                            .append(" | ").append(e.byepregenInject())
                            .append(" | ").append(e.steadychunksInject())
                            .append(" | ").append(e.order())
                            .append(" | ").append(e.fallback())
                            .append(" |\n");
                }
            }
        } catch (Throwable t) {
            sb.append("Mixin 冲突表采集失败: ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
