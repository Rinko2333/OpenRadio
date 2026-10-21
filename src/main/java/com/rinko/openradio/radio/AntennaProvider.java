package com.rinko.openradio.radio;

/**
 * "这个东西能当天线用"。
 *
 * <p>现在只有 {@code AntennaItem} 实现它，天线放副手。将来做带天线的背包或胸甲时，
 * 让那个物品/护甲实现同一个接口即可 —— <b>传播计算和链路表一行都不用改</b>。
 *
 * <p>这是本次"天线不插槽、放副手"决定的延伸：设备形态会变，但"这条链路的天线增益是多少"
 * 这个语义不该跟着变。{@link SignalPropagation} 只认增益数值，不认物品。
 */
public interface AntennaProvider {
    AntennaKind antennaKind();
}
