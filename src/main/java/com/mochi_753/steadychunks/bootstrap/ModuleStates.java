package com.mochi_753.steadychunks.bootstrap;

/**
 * 启动时探测到的兼容性状态快照。
 * <p>
 * 由 {@link CompatibilityProbe#probe()} 一次性填充，后续模块读取此快照决定是否启用。
 * 字段刻意保留为包级可见，仅允许 bootstrap 包写入。
 */
public final class ModuleStates {
    /** FastNoise（zfastnoise modid）是否安装 */
    boolean fastNoisePresent = false;

    /** Bye-Pregen 是否安装 */
    boolean byepregenPresent = false;

    /** Bye-Pregen YALight 光照引擎是否启用（通过类存在性推断） */
    boolean yalightEnabled = false;

    /** Bye-Pregen Palette 重写是否启用（通过类存在性推断） */
    boolean paletteRewriteEnabled = false;

    /** 独立 C2ME（modid `c2me`）是否安装；不含 Bye-Pregen vendored 端口 */
    boolean c2mePresent = false;

    /** Embeddium 是否安装 */
    boolean embeddiumPresent = false;

    /** Sodium 是否安装 */
    boolean sodiumPresent = false;

    @Override
    public String toString() {
        return String.format(
                "FastNoise=%b, Bye-Pregen=%b (YALight=%b, PaletteRewrite=%b), C2ME=%b, Embeddium=%b, Sodium=%b",
                fastNoisePresent, byepregenPresent, yalightEnabled, paletteRewriteEnabled,
                c2mePresent, embeddiumPresent, sodiumPresent
        );
    }

    /** Phase 8：光照兼容探测需要读取 YALight 状态 */
    public boolean yalightEnabled() {
        return yalightEnabled;
    }

    /** Bye-Pregen 是否安装 */
    public boolean byepregenPresent() {
        return byepregenPresent;
    }

    /** FastNoise 是否安装 */
    public boolean fastNoisePresent() {
        return fastNoisePresent;
    }

    /** Bye-Pregen Palette 重写是否启用 */
    public boolean paletteRewriteEnabled() {
        return paletteRewriteEnabled;
    }

    /** 独立 C2ME 是否安装 */
    public boolean c2mePresent() {
        return c2mePresent;
    }

    /** Embeddium 是否安装 */
    public boolean embeddiumPresent() {
        return embeddiumPresent;
    }

    /** Sodium 是否安装 */
    public boolean sodiumPresent() {
        return sodiumPresent;
    }
}
