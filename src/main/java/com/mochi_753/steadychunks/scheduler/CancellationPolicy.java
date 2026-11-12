package com.mochi_753.steadychunks.scheduler;

/**
 * 软取消策略，对应开发计划 §3.6 与技术指导 §8。
 * <p>
 * 两阶段软取消（§8）：
 * <ul>
 *   <li>QUEUED / WAITING_DEPS / READY：直接 CANCELLED（立即生效）</li>
 *   <li>RUNNING：置为 CANCEL_REQUESTED，当前阶段完成后由调度器阻止下一阶段并转为 CANCELLED</li>
 *   <li>FEATURES 阶段已开始时不可中断（§8.1），但可阻止后续阶段</li>
 * </ul>
 * <p>
 * 审查修复：移除 ChunkTaskGraph 依赖。原版已通过 ChunkGenerationTask 和 GenerationChunkHolder
 * 解决邻区块依赖，SteadyChunks 第一版不需要自建依赖图。
 * <p>
 * 禁止：
 * <ul>
 *   <li>中断正在写区块的阶段</li>
 *   <li>中断 FEATURES 中的结构或特征放置</li>
 *   <li>中断光照传播</li>
 *   <li>中断保存</li>
 * </ul>
 */
public final class CancellationPolicy {

    /**
     * 判断任务是否可以被安全取消，以及取消的生效方式。
     *
     * @param task 待取消任务
     * @return CancellationDecision：ALLOWED / ALLOWED_DEFERRED / ALREADY_FINISHED
     */
    public CancellationDecision canCancel(ChunkTask task) {
        TaskState state = task.state();
        // 已结束的终态：DONE / CANCELLED / FAILED / COMPLETING / CANCEL_REQUESTED 不可重复取消
        if (state == TaskState.COMPLETING || state == TaskState.DONE
                || state == TaskState.CANCELLED || state == TaskState.FAILED
                || state == TaskState.CANCEL_REQUESTED) {
            return CancellationDecision.ALREADY_FINISHED;
        }
        // RUNNING：不允许立即中断，但可请求延迟取消（§8 两阶段软取消）
        if (state == TaskState.RUNNING) {
            return CancellationDecision.ALLOWED_DEFERRED;
        }
        // QUEUED / WAITING_DEPS / READY 可安全立即取消
        // 审查修复：移除 DENY_DEPENDENT 检查，原版 ChunkGenerationTask 已处理依赖
        return CancellationDecision.ALLOWED;
    }

    /**
     * 执行软取消。调用前应先检查 {@link #canCancel}。
     * <p>
     * QUEUED / WAITING_DEPS / READY → 直接 CAS 到 CANCELLED，释放已持有的 permit。<br>
     * RUNNING → CAS 到 CANCEL_REQUESTED，当前阶段完成后由 {@code ChunkScheduler.onComplete}
     * 检测并阻止下一阶段、释放资源、转为 CANCELLED。<br>
     * 不中断已执行的阶段，不修改区块数据，不调用 {@code Future.cancel(true)}。
     *
     * @return true 表示取消请求已被接受（立即或延迟）；false 表示拒绝取消
     */
    public boolean cancel(ChunkTask task) {
        CancellationDecision decision = canCancel(task);
        switch (decision) {
            case ALLOWED -> {
                return task.compareAndSetState(task.state(), TaskState.CANCELLED);
            }
            case ALLOWED_DEFERRED -> {
                return task.compareAndSetState(TaskState.RUNNING, TaskState.CANCEL_REQUESTED);
            }
            default -> {
                return false;
            }
        }
    }

    /** 取消决策结果 */
    public enum CancellationDecision {
        /** 允许立即取消（QUEUED/WAITING_DEPS/READY） */
        ALLOWED,
        /** 允许延迟取消（RUNNING，置为 CANCEL_REQUESTED，阶段完成后生效） */
        ALLOWED_DEFERRED,
        /** 任务已结束、已取消或已请求取消 */
        ALREADY_FINISHED
    }
}
