package com.rinko.openradio.energy;

import com.rinko.openradio.Config;
import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.registry.ModDataComponents;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

/**
 * 电台的供电。能量存在<b>电池物品</b>上，电池再嵌在电台的组件里 ——
 * 所以一块电池既能在电台里用，也能拿去别的机器上充。
 *
 * <h2>为什么每次操作都 copy 一遍电池</h2>
 *
 * <p>数据组件是不可变共享对象：同一个组件实例可能同时被背包里的一叠、掉落物、
 * 客户端缓存引用。直接原地改电池的能量，会让所有持有这份组件的栈同时变化，
 * 表现为"电量乱跳"或"客户端凭空多电"。
 * 所以统一改成：取出来 → copy → 改副本 → set 回去。
 */
public final class RadioEnergy {

    /** 电台里装的那块电池，没有电池时返回 {@link ItemStack#EMPTY}。 */
    public static ItemStack battery(ItemStack radio) {
        ItemStack battery = radio.get(ModDataComponents.BATTERY.get());
        return battery == null ? ItemStack.EMPTY : battery;
    }

    public static void setBattery(ItemStack radio, ItemStack battery) {
        radio.set(ModDataComponents.BATTERY.get(), battery == null ? ItemStack.EMPTY : battery);
    }

    public static boolean hasBattery(ItemStack radio) {
        return !battery(radio).isEmpty();
    }

    public static int stored(ItemStack radio) {
        return BatteryItem.getEnergy(battery(radio));
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
     * @return 实际消耗掉的量。电量不足时会返回能拿到的部分，调用方据此判断"够不够发"。
     */
    public static int drain(ItemStack radio, int amount) {
        if (amount <= 0 || !hasBattery(radio)) {
            return 0;
        }
        ItemStack current = battery(radio);
        int have = BatteryItem.getEnergy(current);
        if (have <= 0) {
            return 0;
        }
        int taken = Math.min(have, amount);
        ItemStack updated = current.copy();
        BatteryItem.setEnergy(updated, have - taken);
        setBattery(radio, updated);
        return taken;
    }

    /** 往电台里的电池充电。 */
    public static int charge(ItemStack radio, int amount) {
        if (amount <= 0 || !hasBattery(radio)) {
            return 0;
        }
        ItemStack current = battery(radio);
        int have = BatteryItem.getEnergy(current);
        int cap = BatteryItem.capacity();
        int accepted = Math.min(cap - have, amount);
        if (accepted <= 0) {
            return 0;
        }
        ItemStack updated = current.copy();
        BatteryItem.setEnergy(updated, have + accepted);
        setBattery(radio, updated);
        return accepted;
    }

    private RadioEnergy() {
    }
}
