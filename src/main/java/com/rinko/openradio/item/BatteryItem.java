package com.rinko.openradio.item;

import java.util.List;

import com.rinko.openradio.Config;
import com.rinko.openradio.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 电池。电量存在物品自己的组件里，所以它<b>可以单独拿去别的地方充</b> ——
 * 因为对外暴露了 NeoForge Energy 能力，通用发电机和充电座都认它。
 *
 * <p>电台里也"装着一块电池"，但存的是电量（`BATTERY_PRESENT` + `ENERGY` 两个组件），
 * 不是把电池物品塞进去 —— 原因见 {@code RadioEnergy} 的类注释。换电池是
 * "电台放手、主手持电池右键"，一个 tick 内完成的原子操作，没有容器界面。
 *
 * <p>容量从配置读，不在物品上写死 —— 这样调平衡不用改存档里的每一块电池。
 */
public class BatteryItem extends Item {
    public BatteryItem(Properties properties) {
        super(properties);
    }

    public static int capacity() {
        return Config.ENERGY_BATTERY_CAPACITY.get();
    }

    public static int getEnergy(ItemStack stack) {
        Integer value = stack.get(ModDataComponents.ENERGY.get());
        return value == null ? 0 : value;
    }

    public static void setEnergy(ItemStack stack, int energy) {
        stack.set(ModDataComponents.ENERGY.get(), Mth.clamp(energy, 0, capacity()));
    }

    public static float chargeRatio(ItemStack stack) {
        int cap = capacity();
        return cap <= 0 ? 0.0F : Mth.clamp((float) getEnergy(stack) / cap, 0.0F, 1.0F);
    }

    /** 一块满电的新电池。合成或创造模式给的默认就是这个。 */
    public static ItemStack fresh(Item item) {
        ItemStack stack = new ItemStack(item);
        setEnergy(stack, capacity());
        return stack;
    }

    /** 电量指定的一块电池。用于从电台里拆出电池时，把剩余电量原样带回去。 */
    public static ItemStack of(Item item, int energy) {
        ItemStack stack = new ItemStack(item);
        setEnergy(stack, energy);
        return stack;
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return getEnergy(stack) < capacity();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round(13.0F * chargeRatio(stack));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return Mth.hsvToRgb(chargeRatio(stack) / 3.0F, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.openradio.battery.charge",
                        String.format("%,d", getEnergy(stack)),
                        String.format("%,d", capacity()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.openradio.battery.install")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
