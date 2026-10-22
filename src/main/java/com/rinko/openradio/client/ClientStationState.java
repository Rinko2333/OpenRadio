package com.rinko.openradio.client;

import com.rinko.openradio.network.S2CStationState;

import net.minecraft.core.BlockPos;

/**
 * 最近一次收到的固定台快照。
 *
 * <p>只保存一个值而不是按位置建表：一个玩家同一时刻只可能看一台固定台的界面。
 * 表会带来"什么时候清理"的问题，而单值没有这个问题 —— 打开另一台时自然就被覆盖了。
 */
public final class ClientStationState {
    private static volatile S2CStationState latest;

    public static void accept(S2CStationState state) {
        latest = state;
    }

    public static S2CStationState latest() {
        return latest;
    }

    /** 当前快照是不是这台固定台的。界面用它在切换目标时丢弃旧数据。 */
    public static boolean tracking(BlockPos pos) {
        S2CStationState state = latest;
        return state != null && state.pos().equals(pos);
    }

    public static S2CStationState of(BlockPos pos) {
        S2CStationState state = latest;
        return state != null && state.pos().equals(pos) ? state : null;
    }

    private ClientStationState() {
    }
}
