package com.rinko.openradio.radio;

/**
 * 无线电常量。频率使用整数表示，单位是虚构的"单位"（不是现实 kHz/MHz），
 * 避免映射真实法规频段带来的法律与平衡问题。
 */
public final class RadioConstants {
    /** 可用频率下限。 */
    public static final int MIN_FREQUENCY = 1_000;
    /** 可用频率上限。 */
    public static final int MAX_FREQUENCY = 99_999;
    /** 手持机出厂默认频率。 */
    public static final int DEFAULT_FREQUENCY = 3_000;

    /** 频率是否落在合法范围内。 */
    public static boolean isValidFrequency(int frequency) {
        return frequency >= MIN_FREQUENCY && frequency <= MAX_FREQUENCY;
    }

    /** 把一个任意整数夹到合法频率范围内。 */
    public static int clampFrequency(int frequency) {
        return Math.max(MIN_FREQUENCY, Math.min(MAX_FREQUENCY, frequency));
    }

    private RadioConstants() {
    }
}
