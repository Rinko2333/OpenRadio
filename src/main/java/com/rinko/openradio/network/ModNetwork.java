package com.rinko.openradio.network;

import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.radio.RadioConstants;
import com.rinko.openradio.radio.RadioManager;
import com.rinko.openradio.radio.RadioSounds;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道注册。协议版本号改动后旧客户端会被拒绝连接，改包结构时记得递增。
 */
public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(C2SSetFrequency.TYPE, C2SSetFrequency.STREAM_CODEC,
                ModNetwork::handleSetFrequency);
        registrar.playToServer(C2SSetPowered.TYPE, C2SSetPowered.STREAM_CODEC,
                ModNetwork::handleSetPowered);
        registrar.playToServer(C2STransmit.TYPE, C2STransmit.STREAM_CODEC,
                ModNetwork::handleTransmit);
        registrar.playToClient(S2CRadioState.TYPE, S2CRadioState.STREAM_CODEC,
                S2CRadioState::handle);
    }

    private static void handleSetFrequency(C2SSetFrequency payload, IPayloadContext context) {
        // enqueueWork 保证在主线程执行，可以直接碰世界和物品。
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!RadioConstants.isValidFrequency(payload.frequency())) {
                return;
            }
            // 以服务端手里的物品为准，客户端说"我拿着电台"不算数。
            ItemStack stack = HandheldRadioItem.findRadio(player);
            if (stack.isEmpty()) {
                return;
            }
            HandheldRadioItem.setFrequency(stack, payload.frequency());
        });
    }

    private static void handleSetPowered(C2SSetPowered payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = HandheldRadioItem.findRadio(player);
            if (stack.isEmpty()) {
                return;
            }
            HandheldRadioItem.setPowered(stack, payload.powered());
            if (payload.powered()) {
                RadioSounds.powerOn(player);
            } else {
                RadioSounds.powerOff(player);
            }
        });
    }

    private static void handleTransmit(C2STransmit payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RadioManager.transmit(player, payload.message());
            }
        });
    }

    private ModNetwork() {
    }
}
