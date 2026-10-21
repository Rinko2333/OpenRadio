package com.rinko.openradio.voice;

import com.rinko.openradio.Config;
import com.rinko.openradio.radio.SignalEffects;

/**
 * 把 Q 值作用到语音 PCM 上。
 *
 * <p>输入是 SVC 解码后的原始 PCM：<b>48000Hz / 16bit / 单声道</b>，
 * 每帧 960 个采样（20ms）。处理等价于一台真实的模拟电台：
 *
 * <pre>
 *   高通 → 低通 → 信号中断门 → 音量 → 叠加白噪声
 * </pre>
 *
 * <p>几个刻意的取舍：
 *
 * <ul>
 *   <li><b>高通先做。</b>真实电台没有低频，这一步决定了"像不像电台"，
 *       比噪声还重要。只加噪声听起来像"加了噪的语音"，加了高通才像"电台"。</li>
 *   <li><b>中断时保留噪声。</b>信号断了不等于安静 —— 真实无线电在信号衰落时
 *       听到的是更响的噪声。所以噪声在门之后叠加，门只掐掉人声部分。</li>
 *   <li><b>音量随可读度下降。</b>远处的人不只是听不清，是真的更小声。</li>
 * </ul>
 *
 * <p>这个方法跑在 SVC 的音频线程上，必须轻量：单帧 960 采样，两个一阶滤波器
 * 加一次随机数生成，开销可以忽略。但它<b>不能</b>访问世界或做任何可能阻塞的事。
 */
public final class RadioVoiceProcessor {
    /** SVC 的采样率，实测确认（960 采样 = 20ms）。 */
    private static final double SAMPLE_RATE = 48_000.0D;

    /** 门斜坡长度，1ms。 */
    private static final int RAMP_SAMPLES = 48;

    /**
     * 帧被判定为"信号中断"的概率系数。
     *
     * <p>直接用 dropoutRate 会让中等质量也听起来过于破碎 —— 因为一帧就是 20ms，
     * 连续几帧断掉已经是很强的观感。乘 0.6 让曲线更接近"偶有断续"而不是"抽搐"。
     */
    private static final double DROPOUT_FRAME_FACTOR = 0.6D;

    /**
     * 处理一帧音频。
     *
     * @param input 原始 PCM
     * @param fx    由 Q 推出的感官参数
     * @param state 该说话人的滤波器状态，跨帧复用
     * @return 处理后的新数组（不修改输入，避免和 SVC 共用缓冲区出问题）
     */
    public static short[] process(short[] input, SignalEffects fx, VoiceChannelState state) {
        int length = input.length;
        short[] out = new short[length];

        double lowPassAlpha = 1.0D - Math.exp(-2.0D * Math.PI * fx.lowpassHz() / SAMPLE_RATE);
        double highPassAlpha = 1.0D
                / (1.0D + 2.0D * Math.PI * Config.VOICE_HIGHPASS_HZ.get() / SAMPLE_RATE);
        double noiseAmplitude = fx.noiseFloor() * Config.VOICE_NOISE_GAIN.get() * 32767.0D;

        double minGain = Config.VOICE_MIN_GAIN.get();
        float gain = (float) (minGain + (1.0D - minGain) * fx.readability());

        // 每帧决定一次是否中断，而不是逐采样决定 —— 逐采样的结果听起来像"嗡嗡的失真"，
        // 逐帧才像"信号断续"。
        float targetGate = 1.0F;
        if (fx.dropoutRate() > 0.0D
                && state.random.nextDouble() < fx.dropoutRate() * DROPOUT_FRAME_FACTOR) {
            targetGate = 0.0F;
        }
        state.targetGate(targetGate, RAMP_SAMPLES);

        for (int i = 0; i < length; i++) {
            double x = input[i];

            // 一阶高通：y[n] = a * (y[n-1] + x[n] - x[n-1])
            double high = highPassAlpha * (state.highPassPrevOut + x - state.highPassPrevIn);
            state.highPassPrevIn = x;
            state.highPassPrevOut = high;

            // 一阶低通：y[n] = y[n-1] + a * (x[n] - y[n-1])
            state.lowPassPrev += lowPassAlpha * (high - state.lowPassPrev);

            if (state.rampRemaining > 0) {
                state.gate += state.rampStep;
                state.rampRemaining--;
            }

            double voice = state.lowPassPrev * state.gate * gain;
            double noise = (state.random.nextDouble() * 2.0D - 1.0D) * noiseAmplitude;
            double sample = voice + noise;

            if (sample > 32767.0D) {
                sample = 32767.0D;
            } else if (sample < -32768.0D) {
                sample = -32768.0D;
            }
            out[i] = (short) sample;
        }
        return out;
    }

    /** 全零帧，用于"完全不在链路上"的情况。 */
    public static short[] silence(int length) {
        return new short[length];
    }

    private RadioVoiceProcessor() {
    }
}
