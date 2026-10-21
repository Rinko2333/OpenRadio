package com.rinko.openradio;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.rinko.openradio.energy.ModCapabilities;
import com.rinko.openradio.network.ModNetwork;
import com.rinko.openradio.radio.RadioManager;
import com.rinko.openradio.registry.ModCreativeTabs;
import com.rinko.openradio.registry.ModDataComponents;
import com.rinko.openradio.registry.ModItems;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * OpenRadio —— Minecraft 硬核无线电通信。
 *
 * <p>设计要点：
 * <ul>
 *   <li>服务端权威：频率、信号质量 Q、干扰全部由服务端判定，客户端只上报意图。</li>
 *   <li>主线程预算制：传播计算在主线程按 tick 预算切片执行，不使用异步线程。</li>
 *   <li>设备状态跟随自身：手持机存 ItemStack 数据组件，固定台存 BlockEntity。</li>
 *   <li>投递通道可替换：文本/语音只是 {@code MessageSink} 的不同实现。</li>
 * </ul>
 */
@Mod(OpenRadio.MODID)
public class OpenRadio {
    public static final String MODID = "openradio";
    public static final Logger LOGGER = LogUtils.getLogger();

    public OpenRadio(IEventBus modBus, ModContainer container) {
        ModItems.register(modBus);
        ModDataComponents.register(modBus);
        ModCreativeTabs.register(modBus);
        modBus.addListener(ModNetwork::register);
        modBus.addListener(ModCapabilities::register);

        NeoForge.EVENT_BUS.addListener(RadioManager::onServerTick);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> RadioManager.onServerStopping());

        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("OpenRadio initialised");
    }
}
