package com.rinko.openradio.radio;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rinko.openradio.block.FixedStationBlockEntity;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 当前已加载的固定台索引。
 *
 * <h2>为什么这个索引可以存在，而"全局设备注册表"不行</h2>
 *
 * <p>区别在于<b>它不落盘</b>，内容完全由"哪些区块现在是加载的"决定。
 * 方块实体在 {@code onLoad} 时登记、{@code setRemoved} 时注销 ——
 * 这两个回调正好对应区块的加载与卸载，所以索引永远等于真实世界的一个快照，
 * 不存在"索引里说有、实际没有"这种腐坏状态。
 *
 * <p>这也是为什么固定台的身份（UUID）存在方块实体自己身上而不是这张表里：
 * 表是临时的，身份是永久的。反过来的话，区块卸载再加载就会换一个身份，
 * 别人记着的链路就全断了。
 *
 * <p>只存服务端的方块实体。客户端不需要，也不该有。
 */
public final class FixedStationIndex {
    private static final Map<ResourceKey<Level>, Set<FixedStationBlockEntity>> BY_LEVEL = new HashMap<>();

    public static void register(FixedStationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BY_LEVEL.computeIfAbsent(level.dimension(), key -> new HashSet<>()).add(station);
    }

    public static void unregister(FixedStationBlockEntity station) {
        if (!(station.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Set<FixedStationBlockEntity> set = BY_LEVEL.get(level.dimension());
        if (set == null) {
            return;
        }
        set.remove(station);
        if (set.isEmpty()) {
            BY_LEVEL.remove(level.dimension());
        }
    }

    /**
     * 按身份找一台已加载的固定台。
     *
     * <p>只在一个维度里找，而且只找<b>当前加载着</b>的 —— 手咪是十格内的无线附件，
     * 跨维度或跨区块不可能连上，这两种情况返回 null 就是正确答案。
     *
     * <p>用 UUID 而不是坐标匹配：坐标会被"拆了在原地重建"这种情况骗到，
     * UUID 不会。见 {@code ModDataComponents#MIC_STATION}。
     */
    public static FixedStationBlockEntity find(ServerLevel level, UUID stationId) {
        Set<FixedStationBlockEntity> set = BY_LEVEL.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return null;
        }
        for (FixedStationBlockEntity station : set) {
            if (station.stationId().equals(stationId)) {
                return station;
            }
        }
        return null;
    }

    /**
     * 取一个维度里的所有固定台。
     *
     * <p>返回<b>复制后的列表</b>而不是内部集合：调用方在遍历时可能间接触发
     * 方位块变化（比如耗电导致方块实体标记为脏），直接用原集合会踩
     * {@code ConcurrentModificationException}。复制一个几十元素的列表，代价可以忽略。
     */
    public static List<FixedStationBlockEntity> in(ServerLevel level) {
        Set<FixedStationBlockEntity> set = BY_LEVEL.get(level.dimension());
        if (set == null || set.isEmpty()) {
            return List.of();
        }
        return List.copyOf(set);
    }

    /** 已登记的固定台总数，用于诊断日志。 */
    public static int size() {
        int total = 0;
        for (Set<FixedStationBlockEntity> set : BY_LEVEL.values()) {
            total += set.size();
        }
        return total;
    }

    public static void clear() {
        BY_LEVEL.clear();
    }

    private FixedStationIndex() {
    }
}
