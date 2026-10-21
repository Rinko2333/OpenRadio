package com.rinko.openradio.item;

import java.util.List;

import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.radio.RadioConstants;
import com.rinko.openradio.radio.RadioSounds;
import com.rinko.openradio.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 手持电台。频率、开关机、电池、静噪全部存在 ItemStack 的数据组件里，跟着物品走 ——
 * 不依赖任何全局设备注册表，因此不会因为区块卸载或物品复制而腐坏存档。
 *
 * <h2>为什么没有插槽界面</h2>
 *
 * <p>电池是"嵌在组件里的物品"，但<b>不打开容器</b>：电台放一只手上、另一只手持电池右键，
 * 服务端一个 tick 内完成替换。这样做的收益不只是少画一个界面 ——
 * 容器槽位是 MC 复制漏洞的经典来源（界面开着时物品被丢弃/死亡/换手/被别人拿走），
 * 干脆不引入槽位，那一片 bug 面就不存在了。
 *
 * <p>天线更简单，直接放副手，连物品都进不了电台。见 {@code Antennas}。
 */
public class HandheldRadioItem extends Item {
    public HandheldRadioItem(Properties properties) {
        super(properties);
    }

    // ------------------------------------------------------------ 频率

    public static int getFrequency(ItemStack stack) {
        Integer value = stack.get(ModDataComponents.FREQUENCY.get());
        return value == null ? RadioConstants.DEFAULT_FREQUENCY : value;
    }

    public static void setFrequency(ItemStack stack, int frequency) {
        stack.set(ModDataComponents.FREQUENCY.get(), RadioConstants.clampFrequency(frequency));
    }

    // ------------------------------------------------------------ 开关

    public static boolean isPowered(ItemStack stack) {
        Boolean value = stack.get(ModDataComponents.POWERED.get());
        return value != null && value;
    }

    public static void setPowered(ItemStack stack, boolean powered) {
        stack.set(ModDataComponents.POWERED.get(), powered);
    }

    /**
     * 能不能正常工作 = 开机 + 装着电池。
     *
     * <p>单独抽出这个方法，是因为"开机"和"能用"现在是两件事：电池被拆走的电台
     * 仍然记着开机状态（这样换回电池不用重新开机），但它此刻是死的。
     */
    public static boolean isOperational(ItemStack stack) {
        return isPowered(stack) && RadioEnergy.hasBattery(stack);
    }

    // ------------------------------------------------------------ 静噪

    public static float getSquelch(ItemStack stack) {
        Float value = stack.get(ModDataComponents.SQUELCH.get());
        return value == null ? 0.0F : value;
    }

    public static void setSquelch(ItemStack stack, float squelch) {
        stack.set(ModDataComponents.SQUELCH.get(), Math.max(0.0F, Math.min(1.0F, squelch)));
    }

    // ------------------------------------------------------------ 查找

    /**
     * 找玩家手里的电台，不要求开机。用于调频（关机时也应该允许改频率）。
     *
     * @return 找到的电台，没有则返回 {@link ItemStack#EMPTY}
     */
    public static ItemStack findRadio(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof HandheldRadioItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 找玩家手里<b>真正能用</b>的电台：开机且装着电池。
     *
     * <p>发射和接收都走这个入口，所以"电池被拆走"会立刻从链路表里消失，
     * 不需要任何额外的清理逻辑。
     */
    public static ItemStack findPoweredRadio(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof HandheldRadioItem && isOperational(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    // --------------------------------------------------------- 右键行为

    /**
     * 右键的优先级：<b>换电池 &gt; 拆电池 &gt; 开关机</b>。
     *
     * <p>顺序不能反 —— 如果先判断开关机，那么手上拿着电池右键会变成"开机"，
     * 玩家会以为换电池没生效。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack radio = player.getItemInHand(hand);
        InteractionHand otherHand = hand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        ItemStack other = player.getItemInHand(otherHand);

        // 1) 另一只手持电池 → 装入 / 更换。
        if (other.getItem() instanceof BatteryItem) {
            if (!level.isClientSide) {
                installBattery(player, radio, other);

                float ratio = RadioEnergy.ratio(radio);
                player.displayClientMessage(Component.translatable(
                        "message.openradio.battery_installed",
                        String.format("%.0f%%", ratio * 100.0F),
                        String.format("%,d", RadioEnergy.stored(radio))).withStyle(
                        ratio >= 0.10F ? ChatFormatting.GREEN : ChatFormatting.RED), true);
            }
            return InteractionResultHolder.sidedSuccess(radio, level.isClientSide);
        }

        // 2) 潜行右键 → 把电池拆出来。
        if (player.isShiftKeyDown() && RadioEnergy.hasBattery(radio)) {
            if (!level.isClientSide) {
                ejectBattery(player, radio);
                player.displayClientMessage(Component.translatable("message.openradio.battery_removed")
                        .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.sidedSuccess(radio, level.isClientSide);
        }

        // 3) 开关机。没有电池就不让开机 —— 没有电的电台开机也没意义，
        //    与其让它"看起来开着但什么都不做"，不如直接说清楚。
        if (!level.isClientSide) {
            if (!RadioEnergy.hasBattery(radio)) {
                player.displayClientMessage(Component.translatable("message.openradio.no_battery")
                        .withStyle(ChatFormatting.RED), true);
                return InteractionResultHolder.success(radio);
            }
            boolean powered = !isPowered(radio);
            setPowered(radio, powered);
            if (player instanceof ServerPlayer serverPlayer) {
                if (powered) {
                    RadioSounds.powerOn(serverPlayer);
                } else {
                    RadioSounds.powerOff(serverPlayer);
                }
            }
            player.displayClientMessage(Component.translatable(
                    powered ? "message.openradio.powered_on" : "message.openradio.powered_off",
                    String.format("%05d", getFrequency(radio))), true);
        }
        return InteractionResultHolder.sidedSuccess(radio, level.isClientSide);
    }

    /**
     * 装入 / 更换电池。
     *
     * <p>整个过程同步完成，没有"界面开着的时候物品被别处改动"的时间窗口 ——
     * 这正是不要容器槽位换来的东西。
     */
    private static void installBattery(Player player, ItemStack radio, ItemStack held) {
        ItemStack previous = RadioEnergy.battery(radio);
        RadioEnergy.setBattery(radio, held.copyWithCount(1));

        boolean creative = player.getAbilities().instabuild;
        if (!creative) {
            held.shrink(1);
        }

        // 旧电池还给玩家。创造模式不消耗新电池，所以也不需要特判返还。
        if (!previous.isEmpty()) {
            ItemStack returned = previous.copy();
            if (!player.getInventory().add(returned)) {
                player.drop(returned, false);
            }
        }
    }

    private static void ejectBattery(Player player, ItemStack radio) {
        ItemStack previous = RadioEnergy.battery(radio);
        if (previous.isEmpty()) {
            return;
        }
        RadioEnergy.setBattery(radio, ItemStack.EMPTY);
        setPowered(radio, false);

        ItemStack returned = previous.copy();
        if (!player.getInventory().add(returned)) {
            player.drop(returned, false);
        }
    }

    // ----------------------------------------------------------- tooltip

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        int frequency = getFrequency(stack);
        tooltip.add(Component.translatable("tooltip.openradio.frequency",
                        String.format("%05d", frequency),
                        Component.translatable(RadioBands.of(frequency).translationKey()))
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.translatable(isOperational(stack)
                        ? "tooltip.openradio.powered.on"
                        : "tooltip.openradio.powered.off")
                .withStyle(isOperational(stack) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));

        if (RadioEnergy.hasBattery(stack)) {
            float ratio = RadioEnergy.ratio(stack);
            tooltip.add(Component.translatable("tooltip.openradio.battery.charge",
                            String.format("%,d", RadioEnergy.stored(stack)),
                            String.format("%,d", RadioEnergy.capacity(stack)))
                    .withStyle(ratio >= 0.10F ? ChatFormatting.GRAY : ChatFormatting.RED));
        } else {
            tooltip.add(Component.translatable("tooltip.openradio.no_battery")
                    .withStyle(ChatFormatting.RED));
        }

        float squelch = getSquelch(stack);
        if (squelch > 0.0F) {
            tooltip.add(Component.translatable("tooltip.openradio.squelch",
                            String.format("%.0f%%", squelch * 100.0F))
                    .withStyle(ChatFormatting.GRAY));
        }

        tooltip.add(Component.translatable("tooltip.openradio.open_panel")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
