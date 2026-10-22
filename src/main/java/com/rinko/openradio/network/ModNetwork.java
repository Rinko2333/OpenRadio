package com.rinko.openradio.network;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.rinko.openradio.block.FixedStationBlockEntity;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.radio.AntennaProfile;
import com.rinko.openradio.radio.RadioBand;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.radio.RadioConstants;
import com.rinko.openradio.radio.RadioManager;
import com.rinko.openradio.radio.RadioSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 网络通道注册。协议版本号改动后旧客户端会被拒绝连接，改包结构时记得递增。
 */
public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";

    /** 操作固定台的最大距离（格）。超过就当作"不在旁边"，请求一律丢弃。 */
    private static final double STATION_REACH = 8.0D;
    /** 一次同步给界面的收信条数。界面只显示最近几条，多传是浪费。 */
    private static final int STATION_INBOX_SYNC = 8;

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);

        registrar.playToServer(C2SSetFrequency.TYPE, C2SSetFrequency.STREAM_CODEC,
                ModNetwork::handleSetFrequency);
        registrar.playToServer(C2SSetPowered.TYPE, C2SSetPowered.STREAM_CODEC,
                ModNetwork::handleSetPowered);
        registrar.playToServer(C2SSetSquelch.TYPE, C2SSetSquelch.STREAM_CODEC,
                ModNetwork::handleSetSquelch);
        registrar.playToServer(C2STransmit.TYPE, C2STransmit.STREAM_CODEC,
                ModNetwork::handleTransmit);
        registrar.playToServer(C2SStationRequest.TYPE, C2SStationRequest.STREAM_CODEC,
                ModNetwork::handleStationRequest);
        registrar.playToServer(C2SStationConfig.TYPE, C2SStationConfig.STREAM_CODEC,
                ModNetwork::handleStationConfig);
        registrar.playToServer(C2SStationTransmit.TYPE, C2SStationTransmit.STREAM_CODEC,
                ModNetwork::handleStationTransmit);

        registrar.playToClient(S2CRadioState.TYPE, S2CRadioState.STREAM_CODEC,
                S2CRadioState::handle);
        registrar.playToClient(S2CStationState.TYPE, S2CStationState.STREAM_CODEC,
                S2CStationState::handle);
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
            // 服务端也要挡住"没电池却开机"，不能只靠客户端自觉。
            if (payload.powered() && !RadioEnergy.hasBattery(stack)) {
                player.displayClientMessage(
                        Component.translatable("message.openradio.no_battery")
                                .withStyle(ChatFormatting.RED), true);
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

    private static void handleSetSquelch(C2SSetSquelch payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ItemStack stack = HandheldRadioItem.findRadio(player);
            if (stack.isEmpty()) {
                return;
            }
            HandheldRadioItem.setSquelch(stack, payload.squelch());
        });
    }

    private static void handleTransmit(C2STransmit payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                RadioManager.transmit(player, payload.message());
            }
        });
    }

    // ------------------------------------------------------------ 固定台

    /**
     * 处理固定台界面发来的三个包。
     *
     * <p>共同的第一道校验是<b>距离</b>：客户端说自己打开了哪台机器的界面不算数，
     * 服务端只认"你人是不是还站在旁边"。没有这一道，改过的客户端就能遥控
     * 服务器上任意一台固定台。
     */
    private static void handleStationRequest(C2SStationRequest payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FixedStationBlockEntity station = stationNear(player, payload.pos());
            if (station != null) {
                PacketDistributor.sendToPlayer(player, snapshot(station));
            }
        });
    }

    private static void handleStationConfig(C2SStationConfig payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FixedStationBlockEntity station = stationNear(player, payload.pos());
            if (station == null || !RadioConstants.isValidFrequency(payload.frequency())) {
                return;
            }
            station.setFrequency(payload.frequency());

            // 没电不让开机，和手台保持一致的语义：不做"看起来开着但什么都不做"的机器。
            if (payload.powered() && station.getEnergy() <= 0) {
                player.displayClientMessage(
                        Component.translatable("message.openradio.station.no_power")
                                .withStyle(ChatFormatting.RED), true);
            } else {
                station.setPowered(payload.powered());
            }
            PacketDistributor.sendToPlayer(player, snapshot(station));
        });
    }

    private static void handleStationTransmit(C2SStationTransmit payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            FixedStationBlockEntity station = stationNear(player, payload.pos());
            if (station == null) {
                return;
            }
            RadioManager.transmitFromStation(station, player, player.server.getTickCount(),
                    payload.text());
            // 回一份快照：电量、收件箱都会因为这次发射变化，界面得立刻看到。
            PacketDistributor.sendToPlayer(player, snapshot(station));
        });
    }

    /**
     * 取玩家身边的固定台。距离用方块中心的平方距离，阈值 8 格。
     *
     * <p>{@code getBlockEntity} 在未加载区块返回 null 且不会触发加载，
     * 所以这个检查不会变成"玩家点一下就生成一片世界"。
     */
    @Nullable
    private static FixedStationBlockEntity stationNear(ServerPlayer player, BlockPos pos) {
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > STATION_REACH * STATION_REACH) {
            return null;
        }
        return player.level().getBlockEntity(pos) instanceof FixedStationBlockEntity station
                ? station
                : null;
    }

    /** 把方块实体当前状态打包成界面要的快照。 */
    private static S2CStationState snapshot(FixedStationBlockEntity station) {
        RadioBand band = RadioBands.of(station.getFrequency());
        AntennaProfile antenna = station.antenna();
        RadioManager.Meter meter = RadioManager.meterOf(station.stationId());

        List<S2CStationState.InboxEntry> inbox = new ArrayList<>();
        for (FixedStationBlockEntity.LoggedMessage message : station.inbox()) {
            inbox.add(new S2CStationState.InboxEntry(
                    message.senderName(), (float) message.quality(), message.text()));
        }
        if (inbox.size() > STATION_INBOX_SYNC) {
            inbox = inbox.subList(0, STATION_INBOX_SYNC);
        }

        return new S2CStationState(station.getBlockPos(), station.getFrequency(),
                station.getEnergy(), station.getCapacity(), station.isPowered(),
                antenna.element(), antenna.height(), (float) antenna.gainFor(band),
                antenna.mismatched(band), (float) meter.quality(), meter.heard(), inbox);
    }

    private ModNetwork() {
    }
}
