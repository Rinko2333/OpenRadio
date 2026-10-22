package com.rinko.openradio.client;

import com.rinko.openradio.block.FixedStationBlock;
import com.rinko.openradio.client.screen.StationScreen;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 右键固定台打开界面。
 *
 * <p>为什么不用 {@code MenuProvider}：那套机制的目的是让服务端托管一个容器，
 * 由原版负责槽位同步。固定台没有槽位，用它只会白引入一层 MenuType 和
 * "界面开着时方块被拆"的收尾逻辑。
 *
 * <p>这里的做法是纯客户端的：识别右键目标是固定台就开界面，然后取消事件，
 * 于是原版不会发包给服务端。服务端那边 {@code useWithoutItem} 也刻意不做动作，
 * 两道保险都不依赖对方。
 */
public final class ClientStationInteraction {

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getLevel().getBlockState(event.getPos()).getBlock() instanceof FixedStationBlock)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        minecraft.setScreen(new StationScreen(event.getPos()));
        event.setCanceled(true);
    }

    private ClientStationInteraction() {
    }
}
