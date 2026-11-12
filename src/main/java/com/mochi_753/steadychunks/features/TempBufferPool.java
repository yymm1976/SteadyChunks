package com.mochi_753.steadychunks.features;

import java.util.ArrayList;
import java.util.List;

/**
 * FEATURES 阶段临时缓冲池，对应开发计划 §7.4。
 * <p>
 * 使用 ThreadLocal 复用临时 List，减少 FEATURES 阶段 BlockPos / Stream / 临时 List 的对象分配。
 * <p>
 * 适用场景：
 * <ul>
 *   <li>结构 Piece 放置时的临时方块列表</li>
 *   <li>Jigsaw 展开时的候选 Piece 列表</li>
 *   <li>Processor 处理时的输入/输出方块信息缓冲</li>
 *   <li>跨区块访问时的待写入方块聚合</li>
 * </ul>
 * <p>
 * 风险缓解（计划 §7 风险表）：缓冲复用发生线程串扰。
 * 使用 ThreadLocal 确保每线程独立缓冲；{@link #borrow()} 返回的缓冲必须在同一线程
 * 通过 {@link #release(List)} 归还，且归还前必须调用 {@code clear()}。
 * <p>
 * 使用模式（try-finally 必备）：
 * <pre>{@code
 * List<BlockPos> buf = TempBufferPool.borrowPosList();
 * try {
 *     // 使用 buf
 * } finally {
 *     buf.clear();
 *     TempBufferPool.releasePosList(buf);
 * }
 * }</pre>
 * <p>
 * 不复用：
 * <ul>
 *   <li>跨方法返回的 List（调用方可能持有引用）</li>
 *   <li>传入第三方代码的 List（生命周期不可控）</li>
 *   <li>需要保留结果的 List（应拷贝到独立 List）</li>
 * </ul>
 */
public final class TempBufferPool {

    /** BlockPos 临时列表，初始容量 64 */
    private static final ThreadLocal<List<long[]>> POS_LIST_POOL =
            ThreadLocal.withInitial(() -> new ArrayList<>(64));
    /** int 临时列表（方块索引等），初始容量 128 */
    private static final ThreadLocal<List<Integer>> INT_LIST_POOL =
            ThreadLocal.withInitial(() -> new ArrayList<>(128));
    /** 通用对象临时列表，初始容量 32 */
    private static final ThreadLocal<List<Object>> OBJ_LIST_POOL =
            ThreadLocal.withInitial(() -> new ArrayList<>(32));

    private TempBufferPool() {
    }

    /**
     * 借用一个 BlockPos 临时列表（packed long 数组形式，避免 BlockPos 对象分配）。
     * <p>
     * 使用后必须 {@link #releasePosList(List)} 归还。
     */
    public static List<long[]> borrowPosList() {
        List<long[]> list = POS_LIST_POOL.get();
        // 防御性：若上次未正确清空，此处清空
        list.clear();
        return list;
    }

    /**
     * 归还 BlockPos 临时列表。调用前必须先 {@code clear()}。
     */
    public static void releasePosList(List<long[]> list) {
        if (!list.isEmpty()) {
            list.clear();
        }
    }

    /**
     * 借用一个 int 临时列表（方块索引、palette 索引等）。
     */
    public static List<Integer> borrowIntList() {
        List<Integer> list = INT_LIST_POOL.get();
        list.clear();
        return list;
    }

    public static void releaseIntList(List<Integer> list) {
        if (!list.isEmpty()) {
            list.clear();
        }
    }

    /**
     * 借用一个通用对象临时列表。
     */
    public static <T> List<T> borrowObjList() {
        List<T> list = (List<T>) OBJ_LIST_POOL.get();
        list.clear();
        return list;
    }

    public static <T> void releaseObjList(List<T> list) {
        if (!list.isEmpty()) {
            list.clear();
        }
    }

    /**
     * 借用一个 packed long BlockPos 数组（单个位置，避免单次 BlockPos 分配）。
     * <p>
     * 返回长度为 1 的数组，调用方设置 [0]。
     */
    public static long[] borrowSinglePos() {
        return TEMP_POS.get();
    }

    private static final ThreadLocal<long[]> TEMP_POS = ThreadLocal.withInitial(() -> new long[1]);
}
