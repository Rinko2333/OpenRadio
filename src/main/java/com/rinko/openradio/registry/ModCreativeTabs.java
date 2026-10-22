package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.item.BatteryItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, OpenRadio.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.openradio"))
                    .icon(() -> ModItems.HANDHELD_RADIO.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // 创造模式给一台装好满电电池的电台 —— 否则玩家拿到手
                        // 只会看到"没有电池"，还得自己去找电池、再研究怎么装。
                        ItemStack radio = new ItemStack(ModItems.HANDHELD_RADIO.get());
                        RadioEnergy.install(radio, BatteryItem.capacity());
                        output.accept(radio);

                        output.accept(BatteryItem.fresh(ModItems.BATTERY.get()));
                        output.accept(ModItems.ANTENNA_SHORT.get());
                        output.accept(ModItems.ANTENNA_MEDIUM.get());
                        output.accept(ModItems.ANTENNA_LONG.get());
                        output.accept(ModItems.ANTENNA_WIDEBAND.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
