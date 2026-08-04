package com.mochi_753.steadychunks.diagnostics.inflight;

/**
 * 在途 NOISE 任务生命周期事件类型（阶段 3：固定容量环形缓冲追踪）。
 * <p>
 * 事件只携带值类型字段（taskId/时间/线程/维度 id/区块坐标），不持有 Holder、
 * Future 等强引用——诊断数据永不阻碍任务与区块回收。
 */
public enum TaskEventType {
    /** 任务创建（入队前；direct 路径为执行前） */
    CREATED,
    /** 已入队等待（offer 成功） */
    ADMITTED,
    /** 被 drain 从等待队列 poll 出队 */
    DEQUEUED,
    /** 提交到恢复执行器（worldgen mailbox 或测试 override） */
    SUBMITTED,
    /** mailbox runnable 开始运行（生命周期二次校验之前） */
    MAILBOX_STARTED,
    /** executeOriginal：原版操作即将同步调用 */
    EXECUTING,
    /** originalOperation.get() 已返回（原 Future 在途） */
    ORIGINAL_RETURNED,
    /** 原版 Future 终态（whenComplete：permit 释放、代理完成） */
    ORIGINAL_COMPLETED,
    /** 代理 Future 对外终态（调用方可见完成） */
    PROXY_COMPLETED,
    /** 被恢复批次捕获（captureRecoveryBatch） */
    RECOVERY_CAPTURED,
    /** 恢复处置完成（mailbox 提交执行或第二级强制完成） */
    RECOVERY_COMPLETED,
    /** 生命周期/提交拒绝（error result 完成） */
    REJECTED,
    /** 清理/维度取消（error result 完成） */
    CANCELLED,
    /** 每 taskId 唯一的终态标记（注册表强制：重复终态计为异常） */
    TASK_TERMINAL
}
