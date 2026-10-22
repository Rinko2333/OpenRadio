package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.item.AntennaItem;
import com.rinko.openradio.item.BatteryItem;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.radio.AntennaKind;

import net.minecraft.world.item.BlockItem;
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

    public static final DeferredItem<BatteryItem> BATTERY = ITEMS.registerItem(
            "battery",
            BatteryItem::new,
            new Item.Properties().stacksTo(16));

    // 天线全部 stacksTo(1)：它们是装备而不是消耗品。叠起来反而会让
    // "手里这条到底是哪个波段的"变得容易看错。
    public static final DeferredItem<AntennaItem> ANTENNA_SHORT =
            antenna("antenna_short", AntennaKind.SHORT);
    public static final DeferredItem<AntennaItem> ANTENNA_MEDIUM =
            antenna("antenna_medium", AntennaKind.MEDIUM);
    public static final DeferredItem<AntennaItem> ANTENNA_LONG =
            antenna("antenna_long", AntennaKind.LONG);
    public static final DeferredItem<AntennaItem> ANTENNA_WIDEBAND =
            antenna("antenna_wideband", AntennaKind.WIDEBAND);

    private static DeferredItem<AntennaItem> antenna(String name, AntennaKind kind) {
        return ITEMS.registerItem(name,
                properties -> new AntennaItem(kind, properties),
                new Item.Properties().stacksTo(1));
    }

    // 方块的物品形态。放在这里统一注册，创造页只需要看 ModItems。
    public static final DeferredItem<BlockItem> FIXED_STATION =
            ITEMS.registerSimpleBlockItem(ModBlocks.FIXED_STATION);
    public static final DeferredItem<BlockItem> SOLAR_POWER =
            ITEMS.registerSimpleBlockItem(ModBlocks.SOLAR_POWER);

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    private ModItems() {
    }
}
