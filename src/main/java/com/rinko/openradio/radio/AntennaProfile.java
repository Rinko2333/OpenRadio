package com.rinko.openradio.radio;

import com.rinko.openradio.Config;

/**
 * 一座已建成的天线：振子种类 + 结构高度。
 *
 * <p>为什么把"高度"和"振子"分开记：它们回答两个不同的问题。
 * 振子决定<b>这条天线为哪个波段设计</b>（装错波段效率低），
 * 高度决定<b>同样的振子架多高</b>（越高越好）。现实中也是这样 ——
 * 一副短波八木装在 3 米和 30 米上，是两个完全不同的东西。
 *
 * <p>增益是算出来的，不是存下来的：配置改了就立刻生效，不用重新扫描结构。
 *
 * @param element 顶端振子的种类；{@link AntennaKind#BARE} 表示没有天线
 * @param height  杆 + 振子的总格数；纯杆没有振子时为 0
 */
public record AntennaProfile(AntennaKind element, int height) {
    /** 没有天线。固定台没接天线时用这个，增益退回"裸机"档。 */
    public static final AntennaProfile EMPTY = new AntennaProfile(AntennaKind.BARE, 0);

    public boolean present() {
        return element != AntennaKind.BARE;
    }

    /**
     * 高度带来的额外增益系数。
     *
     * <p>严格线性而不是对数：玩家搭 8 格能算出"+32%"，搭 16 格就是"+64%"。
     * 这种可预期的线性关系比一条漂亮的曲线更有用 —— 玩家能自己规划怎么搭。
     */
    public double heightFactor() {
        int effective = Math.min(height, Config.ANTENNA_MAX_MAST_BLOCKS.get());
        return 1.0D + effective * Config.ANTENNA_MAST_GAIN_PER_BLOCK.get();
    }

    /** 这条天线在某个波段上的最终射程系数。 */
    public double gainFor(RadioBand band) {
        return element.gainFor(band) * heightFactor();
    }

    /** 装错波段了没有。只用于 UI 提示。 */
    public boolean mismatched(RadioBand band) {
        return element.mismatched(band);
    }
}
