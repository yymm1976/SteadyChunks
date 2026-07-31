package com.mochi_753.steadychunks.mixin.diagnostics;

import com.mochi_753.steadychunks.diagnostics.CrashReportContributor;
import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 崩溃报告 Mixin，对应开发计划 §12.1。
 * <p>
 * 目标：{@link CrashReport#getDetails(StringBuilder)}
 * 用途：在崩溃报告生成末尾追加 SteadyChunks 运行时状态
 * 范围：仅 @Inject 追加字符串，不修改原方法逻辑
 */
@Mixin(CrashReport.class)
public class MixinCrashReport {

    @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V",
            at = @At("RETURN"))
    private void steadychunks$appendState(StringBuilder builder, CallbackInfo ci) {
        try {
            builder.append(CrashReportContributor.collectState());
        } catch (Throwable t) {
            // 崩溃报告生成中不能再抛异常，静默忽略
            builder.append("\n[SteadyChunks] 崩溃状态追加失败: ").append(t.getMessage()).append('\n');
        }
    }
}
