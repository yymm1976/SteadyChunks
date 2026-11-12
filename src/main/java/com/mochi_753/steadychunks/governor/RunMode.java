package com.mochi_753.steadychunks.governor;

/**
 * 运行模式，对应开发计划 §4.1。
 * <p>
 * 集成服务器（单人/本地联机）优先保护渲染、客户端主线程、音频与输入；
 * 独立服务器优先保护服务端主线程、网络与 I/O。
 */
public enum RunMode {
    /** 集成服务器：优先保护渲染线程与客户端响应 */
    INTEGRATED,
    /** 独立服务器：优先保护服务端主线程与网络 */
    DEDICATED
}
