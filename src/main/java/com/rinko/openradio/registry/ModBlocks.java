package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.SolarPowerBlock;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册。
 *
 * <p>固定台的硬度给得比较高（3.5 / 6.0）：它是基础设施，不该被路过的苦力怕
 * 一下炸掉，也不该被随手一拳打碎。太阳能用玻璃音效，符合它的材质直觉。
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OpenRadio.MODID);

    public static final DeferredBlock<FixedStationBlock> FIXED_STATION = BLOCKS.registerBlock(
            "fixed_station",
            FixedStationBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL));

    public static final DeferredBlock<SolarPowerBlock> SOLAR_POWER = BLOCKS.registerBlock(
            "solar_power",
            SolarPowerBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLUE)
                    .strength(0.6F, 3.0F)
                    .sound(SoundType.GLASS));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    private ModBlocks() {
    }
}
