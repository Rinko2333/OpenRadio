package com.rinko.openradio.spike;

import java.util.Locale;
import java.util.UUID;

/**
 * M6 可行性验证用的测试信号。<b>正式版会删掉整个 spike 包。</b>
 *
 * <h2>为什么是这个波形</h2>
 *
 * <p>SSTV 的副载波占用 1500–2300Hz，而 Opus 是为语音优化的<b>有损</b>编码器。
 * 我们要验证的是"这些频率过一遍 Opus 还剩多少"，所以测试信号必须同时覆盖三件事：
 *
 * <ul>
 *   <li><b>频点</b>：1000 / 1200 / 1500 / 1900 / 2300 / 2800 Hz 各 0.2 秒。
 *       逐点比较能量，就能画出 Opus 在这个区间的频率响应 ——
 *       SSTV 的 1500(黑) / 1900(中灰) / 2300(白) 三个关键电平都在里面。</li>
 *   <li><b>扫频</b>：1500→2300Hz 线性 0.6 秒。单音能看频率响应，扫频才能看
 *       <b>瞬时频率是否跟得上</b> —— SSTV 每一行都是从 1500 连续升到 2300，
 *       如果编码器把快速变化的频率抹平，扫频段会明显失真，而单音测不出来。</li>
 *   <li><b>首尾静音</b>：0.2 秒。用来测编码器引入的底噪与"启停拖尾"，
 *       这两项会直接决定 VIS 头能不能被可靠识别。</li>
 * </ul>
 *
 * <p>幅度取 0.5 满幅（−6dB）而不是拉满：Opus 前级如果有任何增益都会削波，
 * 而削波产生的宽带谐波会污染整个频谱，让测量结果失去意义。
 */
public final class SpikeSignal {
    public static final int SAMPLE_RATE = 48_000;
    /** SVC 的硬性要求：{@code AudioPlayerImpl} 会校验帧长必须正好 960，否则报错并停止。 */
    public static final int FRAME_SIZE = 960;

    /**
     * 两种模式的通道 ID，故意不同。
     *
     * <p>客户端靠它区分"这是哪一次 spike 的录音"，文件名里就能带上模式 ——
     * 否则两次测试的 wav 放在一起根本分不清哪个是 AUDIO 哪个是 VOIP。
     * 用固定 UUID 而不是随机值，也是为了让客户端能识别这两条通道、不把它们当成玩家语音处理。
     */
    public static final UUID CHANNEL_AUDIO = UUID.fromString("6f70656e-7261-6469-6f2d-000000000001");
    public static final UUID CHANNEL_VOIP = UUID.fromString("6f70656e-7261-6469-6f2d-000000000002");

    /** 单音频率，按 SSTV 关心的顺序排列。 */
    private static final double[] TONES = {1000.0D, 1200.0D, 1500.0D, 1900.0D, 2300.0D, 2800.0D};
    private static final double TONE_SECONDS = 0.20D;
    private static final double LEAD_SECONDS = 0.20D;
    private static final double TAIL_SECONDS = 0.20D;
    private static final double SWEEP_FROM_HZ = 1500.0D;
    private static final double SWEEP_TO_HZ = 2300.0D;
    private static final double SWEEP_SECONDS = 0.60D;

    /** −6dB。见类注释，留余量是为了避免削波污染频谱。 */
    private static final double AMPLITUDE = 0.5D * Short.MAX_VALUE;

    /** 生成整段波形。纯函数，不含任何状态。 */
    public static short[] build() {
        int lead = samples(LEAD_SECONDS);
        int tone = samples(TONE_SECONDS);
        int sweep = samples(SWEEP_SECONDS);
        int tail = samples(TAIL_SECONDS);
        int total = lead + TONES.length * tone + sweep + tail;

        short[] out = new short[total];
        int cursor = lead;   // 首段静音：数组默认就是 0，直接跳过

        for (double hz : TONES) {
            cursor = writeTone(out, cursor, hz, tone);
        }
        cursor = writeSweep(out, cursor, sweep);
        // 尾段同样留静音，cursor 之后不用再写
        return out;
    }

    /** 总样本数。客户端用它判断"收齐了没有"。 */
    public static int totalSamples() {
        return samples(LEAD_SECONDS) + TONES.length * samples(TONE_SECONDS)
                + samples(SWEEP_SECONDS) + samples(TAIL_SECONDS);
    }

    public static int totalFrames() {
        return (totalSamples() + FRAME_SIZE - 1) / FRAME_SIZE;
    }

    /** 人类可读的时长，写进日志方便对照。 */
    public static String describe() {
        return String.format(Locale.ROOT, "%.2fs / %d 帧", totalSamples() / (double) SAMPLE_RATE, totalFrames());
    }

    private static int writeTone(short[] out, int cursor, double hz, int count) {
        double step = 2.0D * Math.PI * hz / SAMPLE_RATE;
        for (int i = 0; i < count; i++) {
            out[cursor++] = (short) Math.round(Math.sin(step * i) * AMPLITUDE);
        }
        return cursor;
    }

    /**
     * 线性扫频。用相位积分而不是直接把 sin 的相位写成 {@code 2πf(t)t} ——
     * 后者的瞬时频率是真实扫频速率的两倍，是这类信号最常见的错误写法。
     */
    private static int writeSweep(short[] out, int cursor, int count) {
        double phase = 0.0D;
        for (int i = 0; i < count; i++) {
            double progress = count <= 1 ? 0.0D : (double) i / (count - 1);
            double hz = SWEEP_FROM_HZ + (SWEEP_TO_HZ - SWEEP_FROM_HZ) * progress;
            phase += 2.0D * Math.PI * hz / SAMPLE_RATE;
            out[cursor++] = (short) Math.round(Math.sin(phase) * AMPLITUDE);
        }
        return cursor;
    }

    private static int samples(double seconds) {
        return (int) Math.round(seconds * SAMPLE_RATE);
    }

    private SpikeSignal() {
    }
}
