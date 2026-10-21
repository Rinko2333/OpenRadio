package com.rinko.openradio.item;

import java.util.List;

import com.rinko.openradio.radio.AntennaKind;
import com.rinko.openradio.radio.AntennaProvider;
import com.rinko.openradio.radio.RadioBand;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 天线物品。放在副手生效，不需要任何槽位界面。
 *
 * <p>物品本身只携带"我是哪种天线"，增益数值在 {@link AntennaKind#gainFor} 里
 * 从配置读 —— 管理员改配置立刻生效，不用动存档里的物品。
 */
public class AntennaItem extends Item implements AntennaProvider {
    private final AntennaKind kind;

    public AntennaItem(AntennaKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public AntennaKind antennaKind() {
        return kind;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        if (kind.isDedicated()) {
            tooltip.add(Component.translatable("tooltip.openradio.antenna.band",
                            Component.translatable(kind.band().translationKey()))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.openradio.antenna.wideband")
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.openradio.antenna.gain",
                        String.format("%+.0f%%", (kind.nominalGain() - 1.0D) * 100.0D))
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.openradio.antenna.offhand")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 显示"最佳波段"的辅助文案用。 */
    public RadioBand band() {
        return kind.band();
    }
}
