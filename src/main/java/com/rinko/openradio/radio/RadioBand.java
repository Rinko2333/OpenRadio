package com.rinko.openradio.radio;

/**
 * 一个频段的传播特性。
 *
 * <p>频段用虚构命名，不映射现实法规频段。三者的平衡关系是刻意的：
 * 低频段射程短但穿墙强、不怕天气；高频段射程远但怕遮挡和天气 ——
 * 这样"用哪个频段"才成为一个需要判断的决策，而不是无脑选最远的。
 *
 * @param id                    标识，同时用于语言文件的键
 * @param minFrequency          频率下限（含）
 * @param maxFrequency          频率上限（含）
 * @param maxRange              开阔地最大射程（方块），会再乘以全局 rangeMultiplier
 * @param distanceExponent      距离衰减曲线指数，越大近距离越"干净"、远端跌落越陡
 * @param blockLossMultiplier   每方块遮挡损失的倍率，相对配置里的 blockLossPerBlock
 * @param maxBlockLoss          遮挡损失的封顶比例
 * @param weatherSensitivity    天气敏感度倍率，1.0 表示完全承受全局天气损失
 */
public record RadioBand(
        String id,
        int minFrequency,
        int maxFrequency,
        double maxRange,
        double distanceExponent,
        double blockLossMultiplier,
        double maxBlockLoss,
        double weatherSensitivity) {

    public String translationKey() {
        return "band.openradio." + id;
    }

    public boolean contains(int frequency) {
        return frequency >= minFrequency && frequency <= maxFrequency;
    }
}
