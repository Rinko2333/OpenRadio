package com.rinko.openradio.registry;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.rinko.openradio.OpenRadio;

import net.minecraft.core.UUIDUtil;
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

    /** 能量。电池物品用它存自电量；电台用它存"里面那块电池"的电量。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> ENERGY =
            COMPONENTS.registerComponentType("energy", builder -> builder
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /**
     * 电台里有没有装电池。
     *
     * <p><b>不能直接存一个 ItemStack。</b>NeoForge 在 {@code ItemStack.set()} 里会校验
     * 组件值"实现了 equals/hashCode 且不可变"，而 {@code ItemStack} 恰恰没有 equals ——
     * 直接存会抛 {@code IllegalArgumentException: Data components must implement equals
     * and hashCode}，表现是打开创造模式物品栏直接崩。
     *
     * <p>所以电池拆成"一个布尔 + 一个 int"：{@code BATTERY_PRESENT} 表示装着，
     * {@link #ENERGY} 表示那块电池的剩余电量。换电池时把电量取出来、
     * 重新变成一个电池物品还给玩家，语义完全一样，而且少同步一个嵌套栈。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> BATTERY_PRESENT =
            COMPONENTS.registerComponentType("battery_present", builder -> builder
                    .persistent(Codec.BOOL)
                    .networkSynchronized(ByteBufCodecs.BOOL));

    /** 静噪阈值（0–1）：Q 低于该值时不播放音频，当作没有信号。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Float>> SQUELCH =
            COMPONENTS.registerComponentType("squelch", builder -> builder
                    .persistent(Codec.FLOAT)
                    .networkSynchronized(ByteBufCodecs.FLOAT));

    /**
     * 无线手咪绑定的固定台身份。
     *
     * <p>存的是固定台自己的 <b>UUID</b>，不是方块坐标。差别在固定台被拆掉之后：
     * 坐标为凭的话，原地重建一台新机器（可能是别人的、可能波段完全不同）会让手咪
     * 静默地绑上去 —— 玩家看起来"绑定还在"，实际已经连到别的台了。
     * 用 UUID 则新机器是新身份，手咪明确报"绑定的固定台已不存在"，玩家知道要重绑。
     *
     * <p>没有这个组件 = 还没绑定。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> MIC_STATION =
            COMPONENTS.registerComponentType("mic_station", builder -> builder
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC));

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
    }

    private ModDataComponents() {
    }
}
