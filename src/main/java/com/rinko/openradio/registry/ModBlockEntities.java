package com.rinko.openradio.registry;

import com.rinko.openradio.OpenRadio;
import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.block.SolarPowerBlockEntity;
import com.rinko.openradio.block.antenna.AntennaBaseBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块实体注册。
 *
 * <p>一个方块实体类型可以绑定多个方块（比如未来的各种电台变体），
 * 所以这里是列表而不是单个。1.21 的 {@code Builder#build} 已经不需要再传 Type 参数。
 */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, OpenRadio.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FixedStationBlockEntity>> FIXED_STATION =
            BLOCK_ENTITIES.register("fixed_station", () -> BlockEntityType.Builder
                    .of(FixedStationBlockEntity::new, ModBlocks.FIXED_STATION.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SolarPowerBlockEntity>> SOLAR_POWER =
            BLOCK_ENTITIES.register("solar_power", () -> BlockEntityType.Builder
                    .of(SolarPowerBlockEntity::new, ModBlocks.SOLAR_POWER.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AntennaBaseBlockEntity>> ANTENNA_BASE =
            BLOCK_ENTITIES.register("antenna_base", () -> BlockEntityType.Builder
                    .of(AntennaBaseBlockEntity::new, ModBlocks.ANTENNA_BASE.get())
                    .build(null));

    public static void register(IEventBus modBus) {
        BLOCK_ENTITIES.register(modBus);
    }

    private ModBlockEntities() {
    }
}
