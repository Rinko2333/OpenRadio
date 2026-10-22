package com.rinko.openradio.energy;

import com.rinko.openradio.Config;
import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.registry.ModDataComponents;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 电台的供电。
 *
 * <h2>为什么电台里不存一个 ItemStack</h2>
 *
 * <p>原本的写法是把电池物品整个塞进电台的组件里。这条路在 NeoForge 上走不通：
 * {@code ItemStack.set()} 会校验组件值必须<b>实现 equals/hashCode 且不可变</b>，
 * 而 {@code ItemStack} 正好没有 equals，于是抛
 * {@code IllegalArgumentException: Data components must implement equals and hashCode}，
 * 表现是打开创造模式物品栏瞬间崩溃。
 *
 * <p>现在拆成"一个布尔 + 一个 int"：{@code BATTERY_PRESENT} 表示装着电池，
 * {@code ENERGY} 表示那块电池剩下的电量。换电池时把电量取出来重新变成一个电池物品还回去，
 * 对外语义和真实电池一模一样，而且同步的数据还更少。
 *
 * <p>副作用是"电池型号"没有地方放了 —— 现在只有一种电池所以无所谓；
 * 将来要做不同容量时，加一个容量组件或档位枚举即可，仍然不需要嵌套物品。
 */
public final class RadioEnergy {

    public static boolean hasBattery(ItemStack radio) {
        Boolean value = radio.get(ModDataComponents.BATTERY_PRESENT.get());
        return value != null && value;
    }

    public static int stored(ItemStack radio) {
        Integer value = radio.get(ModDataComponents.ENERGY.get());
        return value == null ? 0 : value;
    }

    private static void setStored(ItemStack radio, int energy) {
        radio.set(ModDataComponents.ENERGY.get(), Mth.clamp(energy, 0, BatteryItem.capacity()));
    }

    public static int capacity(ItemStack radio) {
        return hasBattery(radio) ? BatteryItem.capacity() : 0;
    }

    public static float ratio(ItemStack radio) {
        int cap = capacity(radio);
        return cap <= 0 ? 0.0F : Mth.clamp((float) stored(radio) / cap, 0.0F, 1.0F);
    }

    /** 电量是否足以发射。低于阈值只限制发射，接收照样能用 —— 不做变砖。 */
    public static boolean canTransmit(ItemStack radio) {
        return hasBattery(radio) && ratio(radio) >= Config.ENERGY_LOW_THRESHOLD.get();
    }

    /**
     * 消耗电量。
     *
     * @return 实际消耗掉的量
     */
    public static int drain(ItemStack radio, int amount) {
        if (amount <= 0 || !hasBattery(radio)) {
            return 0;
        }
        int have = stored(radio);
        int taken = Math.min(have, amount);
        if (taken > 0) {
            setStored(radio, have - taken);
        }
        return taken;
    }

    /** 充电。返回实际接受的电量。 */
    public static int charge(ItemStack radio, int amount) {
        if (amount <= 0 || !hasBattery(radio)) {
            return 0;
        }
        int have = stored(radio);
        int accepted = Math.min(BatteryItem.capacity() - have, amount);
        if (accepted > 0) {
            setStored(radio, have + accepted);
        }
        return Math.max(0, accepted);
    }

    /**
     * 拆下电池。
     *
     * @return 那块电池取出来时剩下的电量；本来就没装电池返回 -1
     */
    public static int eject(ItemStack radio) {
        if (!hasBattery(radio)) {
            return -1;
        }
        int remaining = stored(radio);
        radio.set(ModDataComponents.BATTERY_PRESENT.get(), false);
        setStored(radio, 0);
        return remaining;
    }

    /** 装入一块电量已经确定的电池。调用方负责消耗新电池物品、把旧电池还给玩家。 */
    public static void install(ItemStack radio, int energy) {
        radio.set(ModDataComponents.BATTERY_PRESENT.get(), true);
        setStored(radio, energy);
    }

    private RadioEnergy() {
    }
}
