package com.rinko.openradio.radio;

import com.rinko.openradio.Config;

import net.minecraft.util.Mth;

/**
 * 把 Q 值翻译成"感官参数"。
 *
 * <p>这一层是文本和语音的<b>共同上游</b> —— 也是整个表现层唯一需要被相信的地方。
 * 它的输出不是某一种媒体的具体操作，而是四组抽象量：
 *
 * <ul>
 *   <li>{@code readability} 可读度 0–1：多少内容还能被分辨出来</li>
 *   <li>{@code noiseFloor} 噪声底 0–1：底噪相对内容的强度。用 1-Q 的 1.6 次幂，
 *       让"清晰"档几乎听不到噪声，同时低质量段噪声迅速压过语音 ——
 *       线性曲线的问题是高档位噪声太多、低档位又不够凶。</li>
 *   <li>{@code lowpassHz} 低通截止：越高越清晰，低到 300Hz 就只剩闷响</li>
 *   <li>{@code dropoutRate} 断音率：内容被打断的比例</li>
 * </ul>
 *
 * <p>文本层现在消费 {@code readability} 和 {@code dropoutRate}（错字与掉字）；
 * 语音层将来四条全用（噪声混音、滤波器截止、丢包）。两边共用同一份映射，
 * 所以"听到的和看到的"永远是一致的 —— 不会出现文字乱码但声音清晰这种矛盾。
 */
public record SignalEffects(double readability, double noiseFloor, double lowpassHz, double dropoutRate) {

    /**
     * 由 Q 值推出感官参数。
     *
     * <p>低通截止用对数插值而不是线性 —— 人耳对频率的感知本身是对数的，
     * 线性插值会让"从 300Hz 到 8000Hz"这个过程前半段几乎听不出变化、后半段突变。
     */
    public static SignalEffects of(double quality) {
        double q = Mth.clamp(quality, 0.0D, 1.0D);

        double unusable = Config.EFFECT_UNUSABLE_THRESHOLD.get();
        double clear = Config.EFFECT_CLEAR_THRESHOLD.get();
        double span = Math.max(1.0E-6D, clear - unusable);
        double readability = Mth.clamp((q - unusable) / span, 0.0D, 1.0D);

        double minHz = Config.LOWPASS_MIN_HZ.get();
        double maxHz = Config.LOWPASS_MAX_HZ.get();
        double lowpass = minHz * Math.pow(maxHz / minHz, readability);

        double noise = Math.pow(1.0D - q, 1.6D);
        double dropout = (1.0D - readability) * Config.MAX_DROPOUT_RATE.get();

        return new SignalEffects(readability, noise, lowpass, dropout);
    }

    /** 完全清晰时用的参数，方便调用方在"没有链路"时拿一个安全的默认值。 */
    public static SignalEffects clean() {
        return new SignalEffects(1.0D, 0.0D, Config.LOWPASS_MAX_HZ.get(), 0.0D);
    }
}
