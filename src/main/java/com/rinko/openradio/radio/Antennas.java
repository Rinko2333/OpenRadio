package com.rinko.openradio.radio;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 解析一个实体当前"接的是什么天线"。
 *
 * <p>优先级：手持 → 护甲 → 无。手持排第一，是因为玩家临时换天线本来就该立刻生效。
 *
 * <p>注意发射端和接收端<b>都要算</b>。互易性在真实无线电里是成立的：
 * 听不清远方，可能是对方功率不够，也可能是自己天线不行。两端都算，
 * "我这儿天线不行"才会变成一个玩家能自己诊断的问题。
 */
public final class Antennas {

    public static AntennaKind resolve(LivingEntity entity) {
        for (InteractionHand hand : InteractionHand.values()) {
            AntennaKind kind = fromStack(entity.getItemInHand(hand));
            if (kind != null) {
                return kind;
            }
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            AntennaKind kind = fromStack(entity.getItemBySlot(slot));
            if (kind != null) {
                return kind;
            }
        }
        return AntennaKind.BARE;
    }

    private static AntennaKind fromStack(ItemStack stack) {
        if (!stack.isEmpty() && stack.getItem() instanceof AntennaProvider provider) {
            return provider.antennaKind();
        }
        return null;
    }

    /**
     * 一条链路的合成增益。
     *
     * <p>用几何平均（√(a×b)）而不是直接相乘：直接乘的话两端各装 1.6 倍天线会得到 2.56 倍，
     * 射程一下就翻倍不止，数值会失控。几何平均在两端同构时正好等于单端增益，
     * 而且保留了"两端都升级才有满额收益"的直觉。
     *
     * <p>参数是<b>已经算好的增益数值</b>而不是 {@link AntennaKind}：固定台的天线是多方块结构，
     * 增益取决于振子和高度，一个枚举装不下。让调用方各自算好自己的增益，
     * 这里只负责合成 —— 玩家和固定台走同一条公式，不会出现两套算法慢慢漂移。
     */
    public static double linkGain(double senderGain, double receiverGain) {
        return Math.sqrt(senderGain * receiverGain);
    }

    private Antennas() {
    }
}
