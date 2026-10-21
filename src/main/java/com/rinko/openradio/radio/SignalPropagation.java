package com.rinko.openradio.radio;

import com.rinko.openradio.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 信号传播模型。
 *
 * <p>Q 值在模型里是<b>有向且成对</b>的，不是信道的属性：
 * {@code Q(A→B)} 用 A 的发射条件、A 到 B 的距离与遮挡单独计算，
 * 和 {@code Q(B→A)} 是两次独立计算。现在两边设备同构所以数值相同，
 * 等 M5 加上功率与定向天线增益，这个方向性才会真正体现出来。
 *
 * <p>计算是纯函数：只依赖传入的坐标、频段和配置。唯一的外部读取是射线采样
 * （必须读世界），那部分隔离在 {@link BlockOcclusion} 里。
 *
 * <p>合成方式：
 * <pre>
 *   Q = 距离衰减 × (1 − 遮挡比例) − 天气损失
 * </pre>
 * 距离和遮挡相乘、天气相减。相乘是因为遮挡是"路径上逐段削弱"，本身就有累积效应；
 * 相减是因为天气是全局叠加的底噪恶化。最后夹到 0..1。
 */
public final class SignalPropagation {

    /**
     * 计算一条链路的信号质量。
     *
     * @param level    所在维度
     * @param from     发射端坐标
     * @param to       接收端坐标
     * @param band     频段
     * @param maxSteps 射线步数上限
     * @param linkGain 两端天线的合成射程系数，见 {@link Antennas#linkGain}
     * @return 0.0–1.0 的归一化质量，0 表示收不到
     */
    public static double compute(ServerLevel level, Vec3 from, Vec3 to,
                                 RadioBand band, int maxSteps, double linkGain) {
        // 天线只改射程，不改衰减曲线形状 —— 增益是"能打更远"，
        // 不是"同样距离更清楚"。这个区别在数值上很小，但在手感上很重要：
        // 换上好天线之后，近处的信号不会突然变成完美的。
        double range = band.maxRange() * Config.RANGE_MULTIPLIER.get() * Math.max(0.01D, linkGain);
        double distance = from.distanceTo(to);
        if (distance >= range) {
            return 0.0D;
        }

        double base = Math.pow(1.0D - distance / range, band.distanceExponent());
        double blocked = BlockOcclusion.fraction(level, from, to, band, maxSteps);
        double quality = base * (1.0D - blocked);
        quality -= weatherLoss(level, from, to, band);

        return Mth.clamp(quality, 0.0D, 1.0D);
    }

    /**
     * 天气损失。取路径中点判断是否在下雨 —— 两端有一端在雨中就要承受衰减，
     * 用中点比用接收端更接近"信号沿着路径被削弱"的直觉。
     */
    private static double weatherLoss(ServerLevel level, Vec3 from, Vec3 to, RadioBand band) {
        BlockPos mid = BlockPos.containing(
                (from.x + to.x) * 0.5D,
                (from.y + to.y) * 0.5D,
                (from.z + to.z) * 0.5D);

        double base;
        if (level.isThundering() && level.isRainingAt(mid)) {
            base = Config.WEATHER_LOSS_THUNDER.get();
        } else if (level.isRainingAt(mid)) {
            base = Config.WEATHER_LOSS_RAIN.get();
        } else {
            return 0.0D;
        }
        return base * band.weatherSensitivity();
    }

    private SignalPropagation() {
    }
}
