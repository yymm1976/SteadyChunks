#!/usr/bin/env python3
# 阶段 2：按测试方法边界拆分 SchedulerAdmissionGameTest.java 为 4 个文件。
# 分组：Admission（准入/队列/清理）、Lifecycle（生命周期/维度）、
#       WatchdogRecovery（恢复状态机）、WorldgenIntegration（真实生成集成）。
import re, sys, io

SRC = r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest\SchedulerAdmissionGameTest.java"
OUT_DIR = r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest"

GROUPS = {
    "SchedulerAdmissionGameTest": [
        "admissionPausedShouldBlockNewTasks", "clearAllShouldCompleteWaitingTasks",
        "clearConcurrentAdmissionShouldNotLeaveTasks", "pausedThenDisableShouldBypass",
        "noisePermitOneShouldQueueAndResume", "directAdmissionShouldBeCountedUntilCompletion",
        "mailboxFailureShouldReturnErrorResult", "originalOperationThrowsSynchronously",
    ],
    "SchedulerLifecycleGameTest": [
        "lateOldServerLeaseMustNotAffectNewServerCounter", "serverRestartShouldUseNewLifecycleGeneration",
        "dimensionUnloadDuringEnqueueShouldReject", "dimensionUnloadShouldCancelOnlyTargetDimension",
        "oldDimensionLeaseMustNotDecrementReloadedDimension", "dimensionUnloadAfterPollBeforeSubmitShouldReject",
        "dimensionUnloadShouldCancelOnlyThatDimension", "governorShouldRecoverSharedResource",
        "closeAfterPollBeforeSubmitShouldRejectTask",
    ],
    "WatchdogRecoveryGameTest": [
        "mailboxRecoveryMustEscalateToUnsafe", "mailboxRejectionMustCountUnsafeRecovery",
        "watchdogStateMachineMustRecoverEndToEnd", "watchdogMustNotRecoverDuringCorrectnessTests",
        "watchdogImmediateStopStartMustLeaveLiveThread", "watchdogRestartsAcrossServerLifecycle",
        "shutdownMustTerminateActiveRecoveryBatch", "blockingMailboxSubmissionMustRemainRecoverable",
        "blockingCompletionCallbackMustKeepBatchVisible", "blockingMailboxSubmissionMustEscalateWithoutShutdown",
        "stoppedWatchdogMustNotPublishNewBatch", "stopRecoveryThreadMustNotBlockOnCompletionCallback",
    ],
    "WorldgenIntegrationGameTest": [
        "realGenerationShouldCapNoiseConcurrency",
    ],
}

with io.open(SRC, "r", encoding="utf-8") as f:
    lines = f.readlines()

# 方法起始行：@GameTest 注解（含 javadoc 注释块）或 public void
# 用大括号平衡从 "public void NAME(" 找方法体结束
methods = {}  # name -> (start_line_index, sig_line_index, end_line_index)
i = 0
while i < len(lines):
    m = re.search(r"public void (\w+)\(GameTestHelper helper\)", lines[i])
    if m:
        name = m.group(1)
        # 回退找 javadoc/@GameTest 起始：只允许收集紧邻的 javadoc 块（/**、*、*/）
        # 或空行；遇到其他行（上一个方法的结尾 } 或上一个 @GameTest）立即停止——
        # 没有独立 javadoc 的方法从 @GameTest 行开始（旧实现回溯越界吞掉上一个方法，
        # 切片重叠导致输出重复方法）。
        start = i
        while start > 0 and (lines[start - 1].strip().startswith(("/**", "*", "*/", "@GameTest")) or lines[start - 1].strip() == ""):
            if "@GameTest" in lines[start - 1]:
                start -= 1
                while start > 0 and (lines[start - 1].strip().startswith(("/**", "*", "*/")) or lines[start - 1].strip() == ""):
                    start -= 1
                break
            start -= 1
        # 方法体大括号平衡
        depth = 0
        j = i
        started = False
        while j < len(lines):
            for ch in lines[j]:
                if ch == "{":
                    depth += 1
                    started = True
                elif ch == "}":
                    depth -= 1
                    if started and depth == 0:
                        methods[name] = (start, i, j)
                        i = j
                        break
            if name in methods:
                break
            j += 1
    i += 1

print("提取方法数:", len(methods))
missing = [g for grp in GROUPS.values() for g in grp if g not in methods]
if missing:
    print("缺失方法:", missing)
    sys.exit(1)

# 提取 import 块（package 之后到第一个类声明前）
imports = []
in_imports = False
for line in lines:
    if line.startswith("package "):
        in_imports = True
        continue
    if in_imports:
        if line.startswith("import "):
            imports.append(line)
        elif line.startswith("public ") or line.startswith("@GameTestHolder"):
            break

HEADER = """package com.mochi_753.steadychunks.gametest;

{imports}
/**
 * {desc}
 * <p>
 * 阶段 2：测试拆分自 SchedulerAdmissionGameTest——共享
 * {{@link SchedulerGameTestFixture}}（统一清理/清洁断言/辅助方法），
 * 不再复制 reset 逻辑。
 */
@GameTestHolder("steadychunks")
public class {cls} {{
"""

DESC = {
    "SchedulerAdmissionGameTest": "NOISE 准入、等待队列、清理与 permit 门控测试。",
    "SchedulerLifecycleGameTest": "服务器/维度生命周期、lease 计数与维度卸载隔离测试。",
    "WatchdogRecoveryGameTest": "Watchdog 两级恢复状态机、停服处置与阻塞场景测试。",
    "WorldgenIntegrationGameTest": "真实区块生成集成测试（经 Mixin 拦截链）。",
}

for cls, names in GROUPS.items():
    body = []
    for name in names:
        start, sig, end = methods[name]
        slice_lines = lines[start:end + 1]
        # 阶段 2 失败隔离：每个测试首语句统一清理——GameTest 无 before/after 钩子，
        # 上一测试断言失败中途中止的残留（Future/permit/override/探针）由下一测试
        # 首语句接管清理（resetGlobalState 幂等），保证"失败后后续测试仍独立运行"。
        off = sig - start
        slice_lines = slice_lines[:off + 1] + ["        SchedulerGameTestFixture.resetGlobalState();\n"] + slice_lines[off + 1:]
        body.append("".join(slice_lines))
        body.append("\n")
    content = HEADER.format(imports="".join(imports), desc=DESC[cls], cls=cls) + "".join(body) + "}\n"
    out = OUT_DIR + "\\" + cls + ".java"
    with io.open(out, "w", encoding="utf-8") as f:
        f.write(content)
    print("写入:", cls, "方法数:", len(names))

print("完成")
