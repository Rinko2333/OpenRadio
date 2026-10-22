package com.rinko.openradio.spike;

import com.mojang.brigadier.context.CommandContext;
import com.rinko.openradio.voice.OpenRadioVoicePlugin;

import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * M6 可行性验证的入口命令。<b>正式版会删掉。</b>
 *
 * <pre>
 * /openradio spike audio    以 Opus AUDIO 模式推送（SSTV 应该用的模式）
 * /openradio spike voip     以 Opus VOIP 模式推送（SVC 语音默认用的模式）
 * </pre>
 *
 * <p>为什么要两种模式各跑一次：SSTV 副载波在 1.5–2.3kHz，VOIP 模式为语音优化会
 * 削减这个区间。差别有多大直接决定 SSTV 能不能做 —— 如果 AUDIO 模式也保不住，
 * 那 SSTV 就得换方案（或者只做数据通道的伪 SSTV）。
 *
 * <p>整个类只在确认 SVC 已加载时才会被注册（见 {@code OpenRadio#commonSetup}）。
 * 否则类里的 SVC 类型会在没有 SVC 的客户端上抛 {@code NoClassDefFoundError}。
 */
public final class VoiceSpikeCommand {

    public static void register() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(VoiceSpikeCommand::onRegisterCommands);
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("openradio")
                .then(Commands.literal("spike")
                        // 不放权限门槛：这是临时验证工具，而测试存档不一定开了作弊。
                        // 正式版整个 spike 包会被删掉，不存在被玩家滥用的窗口期。
                        .then(Commands.literal("audio")
                                .executes(context -> run(context, OpusEncoderMode.AUDIO)))
                        .then(Commands.literal("voip")
                                .executes(context -> run(context, OpusEncoderMode.VOIP)))));
    }

    private static int run(CommandContext<CommandSourceStack> context, OpusEncoderMode mode) {
        VoicechatServerApi api = OpenRadioVoicePlugin.serverApi();
        if (api == null) {
            context.getSource().sendFailure(Component.literal("SVC 服务端 API 尚未就绪"));
            return 0;
        }

        int listeners = SpikeTransmitter.start(api, mode);
        String label = mode == OpusEncoderMode.AUDIO ? "audio" : "voip";
        context.getSource().sendSuccess(() -> Component.literal(
                "已推送测试音频 · 模式 " + label + " · 听众 " + listeners + " 人 · "
                        + SpikeSignal.describe()), false);
        return Math.max(1, listeners);
    }

    private VoiceSpikeCommand() {
    }
}
