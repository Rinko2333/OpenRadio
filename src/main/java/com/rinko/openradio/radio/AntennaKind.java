package com.rinko.openradio.radio;

import java.util.Locale;

import com.rinko.openradio.Config;

/**
 * 天线的种类。
 *
 * <p>专用天线只对<b>对应波段</b>有效。装错波段不是不能用，只是效率低 ——
 * 这个刻意的"软失败"是为了让硬核不至于变成劝退：玩家不需要背频段表也能玩，
 * 但知道表的人能拿到 2 倍的射程差。
 *
 * <p>增益在<b>使用时</b>从配置读取，而不是注册时写死，这样管理员改配置立刻生效，
 * 不用重启存档。
 */
public enum AntennaKind {
    /** 没有天线。手持机裸奔，射程大幅缩水。 */
    BARE(null),
    SHORT(RadioBands.SHORT),
    MEDIUM(RadioBands.MEDIUM),
    LONG(RadioBands.LONG),
    /** 宽带：任何波段都不挑，但增益低于专用天线。 */
    WIDEBAND(null);

    private final RadioBand band;

    AntennaKind(RadioBand band) {
        this.band = band;
    }

    /** @return 该天线专用的波段；{@link #BARE} 与 {@link #WIDEBAND} 返回 null */
    public RadioBand band() {
        return band;
    }

    /** 是否是挑波段的专用天线。 */
    public boolean isDedicated() {
        return band != null;
    }

    public String translationKey() {
        return "antenna.openradio." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * 这条天线在某个波段上的射程系数。
     *
     * <p>比较用 {@code id} 而不是对象引用：{@link RadioBands} 里虽然是单例，
     * 但将来若改成数据包驱动，记录会重新构造，引用比较就会静默失效。
     */
    public double gainFor(RadioBand band) {
        return switch (this) {
            case BARE -> Config.ANTENNA_GAIN_BARE.get();
            case WIDEBAND -> Config.ANTENNA_GAIN_WIDEBAND.get();
            default -> this.band != null && band != null && this.band.id().equals(band.id())
                    ? Config.ANTENNA_GAIN_MATCHED.get()
                    : Config.ANTENNA_GAIN_MISMATCHED.get();
        };
    }

    /** 这条天线是不是"装错了"。用于 UI 提示，不参与计算。 */
    public boolean mismatched(RadioBand band) {
        return isDedicated() && band != null && !this.band.id().equals(band.id());
    }

    /**
     * 这条天线在它<b>最佳</b>条件下的增益，只用于 tooltip 展示。
     * 不要用它做计算 —— 计算一律走 {@link #gainFor(RadioBand)}。
     */
    public double nominalGain() {
        return switch (this) {
            case BARE -> Config.ANTENNA_GAIN_BARE.get();
            case WIDEBAND -> Config.ANTENNA_GAIN_WIDEBAND.get();
            default -> Config.ANTENNA_GAIN_MATCHED.get();
        };
    }
}
