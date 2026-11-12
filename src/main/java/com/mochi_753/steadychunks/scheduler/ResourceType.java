package com.mochi_753.steadychunks.scheduler;

/**
 * 区块生成消耗的资源类型，对应开发计划 §3.2。
 * <p>
 * 调度器不能只限制线程数，还要为阶段建立资源令牌。
 * 一个任务可以同时请求多个资源，必须固定资源获取顺序，避免死锁。
 * <p>
 * 获取顺序固定为枚举声明顺序（ordinal），所有任务必须按此顺序申请 permit。
 */
public enum ResourceType {
    /** 通用 CPU 令牌，限制总并发 */
    CPU_GENERAL,
    /** 结构规划阶段：STRUCTURE_STARTS / STRUCTURE_REFERENCES */
    STRUCTURE_PLANNING,
    /** 噪声与表面生成：NOISE / SURFACE，CPU 密集 */
    NOISE_HEAVY,
    /** 特征放置：FEATURES，主线程写入密集 */
    FEATURES_WRITE,
    /** 光照传播：LIGHT */
    LIGHT,
    /** 主线程整合：FULL commit */
    MAIN_THREAD_COMMIT,
    /** 区块发送：网络包构建与压缩 */
    CHUNK_SEND,
    /** 磁盘读取：IO 密集 */
    IO_READ,
    /** 磁盘写入：保存 */
    IO_WRITE
}
