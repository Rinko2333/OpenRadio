package com.rinko.openradio.radio;

import java.util.List;

/**
 * 频段表。
 *
 * <p>目前写死在代码里，因为频段是一组<b>相互关联</b>的平衡参数，拆到 TOML 里逐项填
 * 反而容易配出不合理的组合（比如只把射程拉到 4096 却忘了同步衰减指数）。
 * 管理员可以通过配置里的 rangeMultiplier 和各倍率整体调节，等玩法定型再考虑做成数据包驱动。
 *
 * <p>默认真值（blockLossMultiplier 已折算到 blockLossPerBlock = 0.020 上）：
 * <pre>
 *   短波  1000–19999   射程  512   每方块 0.014   遮挡上限 0.85   天气 0.35
 *   中波 20000–59999   射程 1024   每方块 0.026   遮挡上限 0.80   天气 0.65
 *   长波 60000–99999   射程 2048   每方块 0.044   遮挡上限 0.72   天气 1.00
 * </pre>
 */
public final class RadioBands {
    public static final RadioBand SHORT = new RadioBand(
            "short", 1_000, 19_999, 512.0D, 0.60D, 0.7D, 0.85D, 0.35D);
    public static final RadioBand MEDIUM = new RadioBand(
            "medium", 20_000, 59_999, 1024.0D, 0.65D, 1.3D, 0.80D, 0.65D);
    public static final RadioBand LONG = new RadioBand(
            "long", 60_000, 99_999, 2048.0D, 0.70D, 2.2D, 0.72D, 1.00D);

    private static final List<RadioBand> ALL = List.of(SHORT, MEDIUM, LONG);

    public static List<RadioBand> all() {
        return ALL;
    }

    /**
     * 查频率所属频段。频率合法性应先用 {@link RadioConstants#isValidFrequency} 校验过，
     * 这里兜底返回短波段，保证永远不会因为配置越界而抛异常。
     */
    public static RadioBand of(int frequency) {
        for (RadioBand band : ALL) {
            if (band.contains(frequency)) {
                return band;
            }
        }
        return SHORT;
    }

    private RadioBands() {
    }
}
