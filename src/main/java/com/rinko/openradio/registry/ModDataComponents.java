package com.rinko.openradio.registry;

import com.mojang.serialization.Codec;
import com.rinko.openradio.OpenRadio;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品数据组件。1.21 起物品自定义数据全部走 DataComponent，不再使用 NBT 标签。
 */
public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, OpenRadio.MODID);

    /** 手持机当前频率。必须 networkSynchronized，否则客户端拿不到值。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> FREQUENCY =
            COMPONENTS.registerComponentType("frequency", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /** 设备是否开机。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> POWERED =
            COMPONENTS.registerComponentType("powered", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
