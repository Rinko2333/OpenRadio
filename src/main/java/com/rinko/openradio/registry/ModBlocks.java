package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.block.SolarPowerBlock;
import com.rinko.openradio.block.antenna.AntennaBaseBlock;
import com.rinko.openradio.block.antenna.AntennaElementBlock;
import com.rinko.openradio.block.antenna.AntennaMastBlock;
import com.rinko.openradio.block.feeder.FeederCableBlock;
import com.rinko.openradio.radio.AntennaKind;

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

    // ---------------------------------------------------------------- 多方块天线
    //
    // 三个部件各司其职：基座锚定结构、延长杆买高度、振子决定波段。
    // 全部 noOcclusion() —— 它们是细杆和线缆，不该让相邻方块的面被剔除掉。

    public static final DeferredBlock<AntennaBaseBlock> ANTENNA_BASE = BLOCKS.registerBlock(
            "antenna_base",
            AntennaBaseBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion());

    public static final DeferredBlock<AntennaMastBlock> ANTENNA_MAST = BLOCKS.registerBlock(
            "antenna_mast",
            AntennaMastBlock::new,
            partProperties());

    public static final DeferredBlock<AntennaElementBlock> ANTENNA_ELEMENT_SHORT =
            element("antenna_element_short", AntennaKind.SHORT);
    public static final DeferredBlock<AntennaElementBlock> ANTENNA_ELEMENT_MEDIUM =
            element("antenna_element_medium", AntennaKind.MEDIUM);
    public static final DeferredBlock<AntennaElementBlock> ANTENNA_ELEMENT_LONG =
            element("antenna_element_long", AntennaKind.LONG);
    public static final DeferredBlock<AntennaElementBlock> ANTENNA_ELEMENT_WIDEBAND =
            element("antenna_element_wideband", AntennaKind.WIDEBAND);

    // ---------------------------------------------------------------- 馈线

    public static final DeferredBlock<FeederCableBlock> FEEDER_CABLE = BLOCKS.registerBlock(
            "feeder_cable",
            FeederCableBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_ORANGE)
                    .strength(0.4F, 1.0F)
                    .sound(SoundType.WOOL)
                    .noOcclusion());

    private static DeferredBlock<AntennaElementBlock> element(String name, AntennaKind kind) {
        return BLOCKS.registerBlock(name,
                properties -> new AntennaElementBlock(kind, properties),
                partProperties());
    }

    /** 每种部件都要独立的 Properties 实例，不能复用同一个 —— 方块属性是有状态的。 */
    private static BlockBehaviour.Properties partProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(0.6F, 1.5F)
                .sound(SoundType.METAL)
                .noOcclusion();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    private ModBlocks() {
    }
}
