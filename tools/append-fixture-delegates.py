#!/usr/bin/env python3
# 阶段 2：向 4 个拆分后的 GameTest 类追加统一 fixture 委托块。
# 拆分脚本只搬 @GameTest 方法；辅助方法（obtainHolder/obtainHolderForLevel/
# waitForQueueDrain/awaitTrue/resetScheduler）统一委托 SchedulerGameTestFixture。
# 幂等：已含 delegate 的文件跳过。用法：python tools/append-fixture-delegates.py
import io

OUT = [
    r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest\SchedulerAdmissionGameTest.java",
    r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest\SchedulerLifecycleGameTest.java",
    r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest\WatchdogRecoveryGameTest.java",
    r"C:\Users\杨铭\Desktop\SteadyChunks\src\main\java\com\mochi_753\steadychunks\gametest\WorldgenIntegrationGameTest.java",
]

BLOCK = '''

    // ---- 阶段 2：统一 fixture 委托（辅助方法与清理由 SchedulerGameTestFixture 提供） ----
    private static GenerationChunkHolder obtainHolder(GameTestHelper helper) {
        return SchedulerGameTestFixture.obtainHolder(helper);
    }

    private static GenerationChunkHolder obtainHolderForLevel(ServerLevel level) {
        return SchedulerGameTestFixture.obtainHolderForLevel(level);
    }

    private static void waitForQueueDrain(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.waitForQueueDrain(scheduler);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, String message) {
        SchedulerGameTestFixture.awaitTrue(condition, message);
    }

    /** 重置调度器全局状态（统一清理 + 清洁硬断言 + 追踪复位，见 SchedulerGameTestFixture）。 */
    private static void resetScheduler(ChunkScheduler scheduler) {
        SchedulerGameTestFixture.forceCleanupAfterFailure();
    }
}
'''

# 幂等：已含 delegate 块的文件跳过。注意不能用 resetGlobalState 字样判断——
# split 脚本注入的首语句也含该字符串（误判跳过导致委托缺失）。
MARKER = "统一 fixture 委托"
for path in OUT:
    with io.open(path, "r", encoding="utf-8") as f:
        content = f.read()
    if MARKER in content:
        print("已含 delegate（跳过）:", path)
        continue
    stripped = content.rstrip()
    assert stripped.endswith("}"), "文件不以类结束符收尾: " + path
    # 去掉末尾类结束符，换用 delegate 块（块内自带 }）
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(stripped[:-1].rstrip() + BLOCK)
    print("delegate 追加:", path)

print("完成")
