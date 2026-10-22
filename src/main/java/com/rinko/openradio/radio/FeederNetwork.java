package com.rinko.openradio.radio;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.rinko.openradio.Config;
import com.rinko.openradio.block.antenna.AntennaBaseBlock;
import com.rinko.openradio.block.antenna.AntennaBaseBlockEntity;
import com.rinko.openradio.block.feeder.FeederCableBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * 从电台出发，沿馈线找到它接的天线。
 *
 * <h2>为什么是 BFS 搜索而不是"线缆自己记着连到谁"</h2>
 *
 * <p>另一种做法是让每段馈线记住网络里有哪些设备，增删时维护。那需要处理
 * 区块加载卸载、方块被活塞推动、世界编辑、以及最麻烦的<b>断电时的半成品状态</b> ——
 * 一旦漏掉一次更新，网络就永久错了，而且极难排查。
 *
 * <p>这里改成"用时现搜"：只在电台需要天线参数时走一遍，找到就返回。
 * 代价是每次要遍历，但一次发射周期才查一次（结果还会被缓存），
 * 而换来的好处是<b>永远不会出现"线缆以为连着、实际没连"</b>。
 *
 * <h2>为什么必须检查 isLoaded</h2>
 *
 * <p>服务端的 {@code getBlockState} 遇到未加载区块会<b>触发区块加载</b>。
 * 一条横跨几百格的馈线足以让一次查询变成一次世界生成。
 * 所以这里的规矩是：未加载的位置直接跳过，当作断线。
 * 这个取舍是对的 —— 天线在别的区块没加载，本来也不该为一个远处的电台提供增益。
 */
public final class FeederNetwork {

    /**
     * 找出这个位置接到的天线。
     *
     * @return 找到的基座方块实体；没接天线 / 超出搜索上限返回 null
     */
    @Nullable
    public static AntennaBaseBlockEntity findAntenna(Level level, BlockPos from) {
        int limit = Config.ANTENNA_FEEDER_MAX_BLOCKS.get();
        Set<BlockPos> seen = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();

        // 起点是电台自己：把与它相邻的馈线全部入队。
        enqueueCables(level, from, queue, seen);

        while (!queue.isEmpty()) {
            if (seen.size() > limit) {
                return null;
            }
            BlockPos current = queue.poll();

            for (Direction side : Direction.values()) {
                BlockPos next = current.relative(side);
                if (!seen.add(next)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    continue;
                }
                if (level.getBlockState(next).getBlock() instanceof AntennaBaseBlock) {
                    return level.getBlockEntity(next) instanceof AntennaBaseBlockEntity base ? base : null;
                }
                if (level.getBlockState(next).getBlock() instanceof FeederCableBlock) {
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static void enqueueCables(Level level, BlockPos from,
                                      ArrayDeque<BlockPos> queue, Set<BlockPos> seen) {
        for (Direction side : Direction.values()) {
            BlockPos next = from.relative(side);
            if (!level.isLoaded(next)) {
                continue;
            }
            if (level.getBlockState(next).getBlock() instanceof FeederCableBlock && seen.add(next)) {
                queue.add(next);
            }
        }
    }

    private FeederNetwork() {
    }
}
