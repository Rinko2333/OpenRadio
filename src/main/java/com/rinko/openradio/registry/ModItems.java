package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.item.HandheldRadioItem;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(OpenRadio.MODID);

    public static final DeferredItem<HandheldRadioItem> HANDHELD_RADIO = ITEMS.registerItem(
            "handheld_radio",
            HandheldRadioItem::new,
            new Item.Properties().stacksTo(1));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
