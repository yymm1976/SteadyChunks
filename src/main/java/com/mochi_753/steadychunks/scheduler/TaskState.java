package com.mochi_753.steadychunks.scheduler;

/**
 * 区块任务生命周期状态，对应开发计划 §3.1 与 §3.6，技术指导 §8。
 * <p>
 * 状态流转：
 * <pre>
 * QUEUED → WAITING_DEPS → READY → RUNNING → COMPLETING → DONE
 *    ↓         ↓            ↓         ↓           ↓
 * CANCELLED  CANCELLED   CANCELLED  CANCEL_REQUESTED → CANCELLED
 *                                   FAILED
 * </pre>
 * 软取消（§3.6/§8）分两阶段：
 * <ul>
 *   <li>QUEUED / WAITING_DEPS / READY：直接 CANCELLED</li>
 *   <li>RUNNING：置为 CANCEL_REQUESTED，阶段完成后阻止下一阶段，置为 CANCELLED</li>
 *   <li>FEATURES 阶段已开始时不可中断（§8.1），但可阻止后续阶段</li>
 * </ul>
 */
public enum TaskState {
    /** 已入队，等待调度器评分 */
    QUEUED,
    /** 等待依赖区块完成 */
    WAITING_DEPS,
    /** 依赖已满足，等待资源 permit */
    READY,
    /** 已获取 permit，正在执行 */
    RUNNING,
    /** 运行中请求取消，阶段完成后阻止下一阶段（§8） */
    CANCEL_REQUESTED,
    /** 执行完成，正在释放资源与通知依赖 */
    COMPLETING,
    /** 最终完成 */
    DONE,
    /** 软取消（QUEUED/WAITING_DEPS/READY 直接取消，或 CANCEL_REQUESTED 阶段完成后取消） */
    CANCELLED,
    /** 执行失败 */
    FAILED
}
