package com.rinko.openradio.client;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.rinko.openradio.client.screen.RadioScreen;
import com.rinko.openradio.item.HandheldRadioItem;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * 客户端输入。
 *
 * <p>面板走按键而不是右键，是为了不和"右键开关机"抢操作 —— 而且将来接语音时，
 * 手基本都在 WASD 和鼠标上，键盘入口比右键顺手。
 */
public final class ClientEvents {
    public static final KeyMapping OPEN_PANEL = new KeyMapping(
            "key.openradio.open_panel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "key.categories.openradio");

    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_PANEL);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN_PANEL.consumeClick()) {
            if (minecraft.player == null || minecraft.screen != null) {
                continue;
            }
            ItemStack radio = HandheldRadioItem.findRadio(minecraft.player);
            if (radio.isEmpty()) {
                continue;
            }
            minecraft.setScreen(new RadioScreen(radio));
        }

        // 静噪由主线程刷新到缓存，音频线程只读那个 float。
        // 没拿电台就当作不静噪 —— 否则上次的阈值会在空手时继续生效，很难排查。
        if (minecraft.player != null) {
            ItemStack radio = HandheldRadioItem.findRadio(minecraft.player);
            ClientRadioState.setSquelch(radio.isEmpty() ? 0.0F : HandheldRadioItem.getSquelch(radio));
        }
    }

    private ClientEvents() {
    }
}
