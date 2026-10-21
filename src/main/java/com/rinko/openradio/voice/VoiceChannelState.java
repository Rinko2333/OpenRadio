package com.rinko.openradio.voice;

import java.util.Random;

/**
 * 单个说话人的音频处理状态。
 *
 * <p>SVC 给每个音频通道单独开一个线程（日志里是 {@code AudioChannelThread-<uuid>}），
 * 所以这个状态只会被同一个线程访问，不需要同步。
 *
 * <p>但状态<b>必须</b>跨帧保留 —— 一阶滤波器的记忆就是它的全部意义所在。
 * 每帧重新建一个滤波器等于没有滤波，只会产生爆音。
 */
public final class VoiceChannelState {
    /** 高通滤波器记忆。 */
    double highPassPrevIn;
    double highPassPrevOut;
    /** 低通滤波器记忆。 */
    double lowPassPrev;

    /** 当前的信号门值 0–1，用斜坡过渡避免咔嗒声。 */
    float gate = 1.0F;
    float gateFrom = 1.0F;
    float gateTo = 1.0F;
    int rampRemaining;
    float rampStep;

    final Random random = new Random();

    /**
     * 把门值斜坡过渡到目标值。
     *
     * <p>直接 0/1 硬切会在频谱上产生宽带的冲击，听起来是"咔"而不是"信号断了"。
     * 1 毫秒的斜坡就够了，短到不会被听成渐变，长到足够消除爆音。
     */
    void targetGate(float value, int rampSamples) {
        if (value == gateTo) {
            return;
        }
        gateTo = value;
        gateFrom = gate;
        rampRemaining = rampSamples;
        rampStep = (gateTo - gateFrom) / rampSamples;
    }
}
