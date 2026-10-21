package com.rinko.openradio.registry;

import com.mojang.serialization.Codec;
import com.rinko.openradio.OpenRadio;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.ItemStack;
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

    /** 电池里存了多少 FE。只在电池物品上出现。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
            COMPONENTS.registerComponentType("energy", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * 电台里装的那块电池。
     *
     * <p>用组件而不是额外槽位，是为了<b>彻底避开容器 UI</b>：没有 {@code Menu}、
     * 没有跨端同步的槽位，也就没有"物品套物品"的复制漏洞面。换电池是服务端
     * 一个 tick 内完成的原子操作。
     *
     * <p>同步用 OPTIONAL 版本：空电池必须能正常序列化，否则拆掉电池后
     * 客户端会收到一个无法解码的包。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ItemStack>> BATTERY =
            COMPONENTS.registerComponentType("battery", builder -> builder
                    .persistent(ItemStack.OPTIONAL_CODEC)
                    .networkSynchronized(ItemStack.OPTIONAL_STREAM_CODEC));

    /** 静噪阈值（0–1）：Q 低于该值时不播放音频，当作没有信号。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> SQUELCH =
            COMPONENTS.registerComponentType("squelch", builder -> builder
                    .persistent(Codec.FLOAT)
                    .networkSynchronized(ByteBufCodecs.FLOAT));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
