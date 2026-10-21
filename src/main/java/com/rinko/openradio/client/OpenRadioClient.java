package com.rinko.openradio.client;

import com.rinko.openradio.OpenRadio;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口。这个类在专用服务器上不会被加载，可以安全访问客户端专属代码。
 *
 * <p>事件总线显式注册，不依赖 {@code @EventBusSubscriber} 的自动分派 ——
 * 少一层魔法，出问题时也更好定位。
 */
@Mod(value = OpenRadio.MODID, dist = Dist.CLIENT)
public class OpenRadioClient {
    public OpenRadioClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(ClientEvents::onRegisterKeyMappings);
        NeoForge.EVENT_BUS.addListener(ClientEvents::onClientTick);

        OpenRadio.LOGGER.info("OpenRadio client loaded");
    }
}
