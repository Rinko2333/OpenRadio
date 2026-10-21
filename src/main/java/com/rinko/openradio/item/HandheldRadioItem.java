package com.rinko.openradio.item;

import java.util.List;

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
 * 手持电台。频率与开关机状态存在 ItemStack 的数据组件里，跟着物品走 ——
 * 不依赖任何全局设备注册表，因此不会因为区块卸载或物品复制而腐坏存档。
 */
public class HandheldRadioItem extends Item {
    public HandheldRadioItem(Properties properties) {
        super(properties);
    }

    public static int getFrequency(ItemStack stack) {
        Integer value = stack.get(ModDataComponents.FREQUENCY.get());
        return value == null ? RadioConstants.DEFAULT_FREQUENCY : value;
    }

    public static void setFrequency(ItemStack stack, int frequency) {
        stack.set(ModDataComponents.FREQUENCY.get(), RadioConstants.clampFrequency(frequency));
    }

    public static boolean isPowered(ItemStack stack) {
        Boolean value = stack.get(ModDataComponents.POWERED.get());
        return value != null && value;
    }

    public static void setPowered(ItemStack stack, boolean powered) {
        stack.set(ModDataComponents.POWERED.get(), powered);
    }

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
     * 找玩家手里<b>已开机</b>的电台。发射和接收都要求开机。
     */
    public static ItemStack findPoweredRadio(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof HandheldRadioItem && isPowered(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 右键切换开关机。
     *
     * <p>只在服务端改数据组件，客户端靠原版的物品同步拿到新状态 ——
     * 这样不存在两端各改一次导致的闪烁或不同步。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            boolean powered = !isPowered(stack);
            setPowered(stack, powered);
            if (player instanceof ServerPlayer serverPlayer) {
                if (powered) {
                    RadioSounds.powerOn(serverPlayer);
                } else {
                    RadioSounds.powerOff(serverPlayer);
                }
            }
            player.displayClientMessage(Component.translatable(
                    powered ? "message.openradio.powered_on" : "message.openradio.powered_off",
                    String.format("%05d", getFrequency(stack))), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        int frequency = getFrequency(stack);
        tooltip.add(Component.translatable("tooltip.openradio.frequency",
                        String.format("%05d", frequency),
                        Component.translatable(RadioBands.of(frequency).translationKey()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(isPowered(stack)
                        ? "tooltip.openradio.powered.on"
                        : "tooltip.openradio.powered.off")
                .withStyle(isPowered(stack) ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.openradio.open_panel")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
