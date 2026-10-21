package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
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
                    .displayItems((parameters, output) -> output.accept(ModItems.HANDHELD_RADIO.get()))
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }

    private ModCreativeTabs() {
    }
}
