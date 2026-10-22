package com.rinko.openradio.client.screen;

import com.rinko.openradio.client.ClientStationState;
import com.rinko.openradio.network.C2SStationConfig;
import com.rinko.openradio.network.C2SStationRequest;
import com.rinko.openradio.network.C2SStationTransmit;
import com.rinko.openradio.network.S2CStationState;
import com.rinko.openradio.radio.AntennaKind;
import com.rinko.openradio.radio.RadioBand;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.radio.RadioConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 固定台操作面板。
 *
 * <p>和手台面板一样是<b>纯客户端 Screen</b>，没有 {@code Menu}、没有容器同步。
 * 固定台没有物品槽位（天线在多方块结构上、电从电网来），既然没有槽位，
 * 引入容器就只剩下"物品套物品复制"这一片漏洞面，一点好处都没有。
 *
 * <p>界面本身不做任何判定：所有输入都发给服务端，服务端校验后回一份快照。
 * 距离、电量、频率合法性全部在服务端查，所以改过的客户端也遥控不了别人的固定台。
 *
 * <h2>为什么是"请求-响应"而不是服务端推送</h2>
 *
 * <p>客户端每秒主动要 3 次快照。服务端不需要记住"谁在看哪台机器"，
 * 关掉界面就不再请求，自然停止。任何"订阅/退订"式设计都要处理玩家掉线、
 * 走远、方块被拆 —— 那三件事都会漏，而漏一个就会留下永久占用。
 */
public class StationScreen extends Screen {
    private static final int PANEL_W = 300;
    private static final int PANEL_H = 226;
    private static final int PANEL_COLOR = 0xE0101418;
    private static final int BORDER_COLOR = 0xFF3FA88F;
    private static final int MUTED = 0xFF8A8A80;
    private static final int ACCENT = 0xFF9FE1CB;
    private static final int WARN = 0xFFC9A227;
    private static final int BAD = 0xFFC24B3A;

    /** 每秒刷 3 次：信号质量会随别的电台移动变化，太慢会显得读数卡住。 */
    private static final int REFRESH_INTERVAL = 20;
    /** 这么久没收到快照就自己关掉 —— 方块被拆了、或者人走远了。 */
    private static final int STALE_TICKS = 120;
    /** 收件箱显示条数。再多就需要滚动，那是给自己找麻烦。 */
    private static final int INBOX_ROWS = 5;

    private final BlockPos pos;

    private EditBox frequencyBox;
    private EditBox messageBox;
    private Button powerButton;

    private int left;
    private int top;
    private int refreshCounter;
    private int ticksSinceSync;

    public StationScreen(BlockPos pos) {
        super(Component.translatable("gui.openradio.station.title"));
        this.pos = pos;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        frequencyBox = new EditBox(font, left + 62, top + 34, 78, 18,
                Component.translatable("gui.openradio.station.frequency"));
        frequencyBox.setMaxLength(5);
        frequencyBox.setFilter(text -> text.chars().allMatch(Character::isDigit));
        S2CStationState state = ClientStationState.of(pos);
        frequencyBox.setValue(state == null
                ? String.format("%05d", RadioConstants.DEFAULT_FREQUENCY)
                : String.format("%05d", state.frequency()));
        addRenderableWidget(frequencyBox);

        addRenderableWidget(Button.builder(Component.literal("<"), button -> stepFrequency(-100))
                .bounds(left + 144, top + 33, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> stepFrequency(100))
                .bounds(left + 168, top + 33, 20, 20).build());

        // 开关机按钮的标签依赖服务端状态，所以在 render 里每帧更新。
        powerButton = Button.builder(Component.empty(), button -> togglePower())
                .bounds(left + PANEL_W - 66, top + 8, 56, 16).build();
        addRenderableWidget(powerButton);

        messageBox = new EditBox(font, left + 10, top + 196, 196, 18,
                Component.translatable("gui.openradio.station.message"));
        messageBox.setMaxLength(160);
        addRenderableWidget(messageBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.openradio.station.transmit"),
                        button -> transmit())
                .bounds(left + 212, top + 195, 78, 20).build());

        requestSnapshot();
    }

    // ------------------------------------------------------------ 交互

    private void requestSnapshot() {
        PacketDistributor.sendToServer(new C2SStationRequest(pos));
    }

    private void stepFrequency(int delta) {
        setFrequency(RadioConstants.clampFrequency(parseFrequency() + delta));
    }

    private void setFrequency(int frequency) {
        frequencyBox.setValue(String.format("%05d", frequency));
        pushConfig(frequency, null);
    }

    /**
     * 提交频率与开关。
     *
     * @param powered 为 null 时沿用服务端当前状态 —— 改频率不应该顺手把机器关掉
     */
    private void pushConfig(int frequency, Boolean powered) {
        S2CStationState state = ClientStationState.of(pos);
        boolean next = powered != null ? powered : (state != null && state.powered());
        PacketDistributor.sendToServer(new C2SStationConfig(pos, frequency, next));
    }

    private void togglePower() {
        S2CStationState state = ClientStationState.of(pos);
        if (state == null) {
            return;
        }
        pushConfig(state.frequency(), !state.powered());
    }

    private void transmit() {
        String text = messageBox.getValue();
        if (text.isBlank()) {
            return;
        }
        PacketDistributor.sendToServer(new C2SStationTransmit(pos, text));
        messageBox.setValue("");
    }

    private int parseFrequency() {
        String raw = frequencyBox.getValue();
        if (raw.isEmpty()) {
            // 玩家正在删改，空串不该被当成 0 —— 保留当前频率。
            S2CStationState state = ClientStationState.of(pos);
            return state == null ? RadioConstants.DEFAULT_FREQUENCY : state.frequency();
        }
        try {
            return RadioConstants.clampFrequency(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            return RadioConstants.DEFAULT_FREQUENCY;
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticksSinceSync++;
        if (++refreshCounter >= REFRESH_INTERVAL) {
            refreshCounter = 0;
            requestSnapshot();
        }
        // 方块被拆、或玩家走远后服务端不再回包，界面要自己收场，
        // 否则会一直显示一份越来越旧的快照，看起来像"卡住了"。
        if (ticksSinceSync > STALE_TICKS) {
            onClose();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean enter = keyCode == 257 || keyCode == 335;
        if (enter && frequencyBox.isFocused()) {
            setFrequency(parseFrequency());
            frequencyBox.setFocused(false);
            return true;
        }
        if (enter && messageBox.isFocused()) {
            transmit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------ 渲染

    /**
     * 面板背景必须在 widget <b>之前</b>画。
     *
     * <p>框架会在 {@code render} 之前调这个，所以放进这里是唯一正确的时机 ——
     * 如果在 {@code super.render} 之后 fill，会把输入框和按钮整个盖住。
     *
     * <p>不调用 {@code super}：省掉原版的模糊与压暗，面板要贴着世界显示。
     */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int x0 = left - 4;
        int y0 = top - 4;
        int x1 = left + PANEL_W + 4;
        int y1 = top + PANEL_H + 4;
        graphics.fill(x0, y0, x1, y1, PANEL_COLOR);
        graphics.fill(x0, y0, x1, y0 + 1, BORDER_COLOR);
        graphics.fill(x0, y1 - 1, x1, y1, BORDER_COLOR);
        graphics.fill(x0, y0, x0 + 1, y1, BORDER_COLOR);
        graphics.fill(x1 - 1, y0, x1, y1, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        S2CStationState state = ClientStationState.of(pos);
        if (state != null) {
            ticksSinceSync = 0;
            // 输入框没被操作时跟着服务端走，否则玩家自己输入的内容会被覆盖。
            if (!frequencyBox.isFocused()) {
                String expected = String.format("%05d", state.frequency());
                if (!expected.equals(frequencyBox.getValue())) {
                    frequencyBox.setValue(expected);
                }
            }
            powerButton.setMessage(Component.translatable(
                    state.powered() ? "gui.openradio.on" : "gui.openradio.off"));
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, left + 10, top + 12, ACCENT, false);

        if (state == null) {
            graphics.drawString(font, Component.translatable("gui.openradio.station.syncing"),
                    left + 10, top + 60, MUTED, false);
            return;
        }

        graphics.drawString(font, Component.translatable("gui.openradio.station.frequency"),
                left + 10, top + 39, MUTED, false);
        renderMeters(graphics, state);
        renderInbox(graphics, state);
    }

    private void renderMeters(GuiGraphics graphics, S2CStationState state) {
        int y = top + 62;

        // 电量
        float ratio = state.capacity() <= 0 ? 0.0F
                : Math.min(1.0F, (float) state.energy() / state.capacity());
        graphics.drawString(font, Component.translatable("gui.openradio.station.energy"),
                left + 10, y + 1, MUTED, false);
        drawBar(graphics, left + 46, y, 116, 8, ratio,
                ratio > 0.5F ? ACCENT : (ratio > 0.1F ? WARN : BAD));
        graphics.drawString(font, String.format("%,d / %,d RF", state.energy(), state.capacity()),
                left + 170, y + 1, MUTED, false);

        // 天线：装错波段用警告色，不要让玩家对着一个低数字猜原因
        y += 16;
        if (state.element() == AntennaKind.BARE) {
            graphics.drawString(font, Component.translatable("gui.openradio.station.no_antenna"),
                    left + 10, y + 1, WARN, false);
        } else {
            graphics.drawString(font, Component.translatable("gui.openradio.station.antenna_line",
                            Component.translatable(state.element().translationKey()),
                            state.height(), String.format("%.2f", state.gain())),
                    left + 10, y + 1, state.mismatched() ? BAD : MUTED, false);
            if (state.mismatched()) {
                RadioBand band = RadioBands.of(state.frequency());
                graphics.drawString(font,
                        Component.translatable("gui.openradio.station.mismatch",
                                Component.translatable(band.translationKey())),
                        left + 170, y + 1, BAD, false);
            }
        }

        // 信号
        y += 16;
        graphics.drawString(font, Component.translatable("gui.openradio.station.signal"),
                left + 10, y + 1, MUTED, false);
        drawBar(graphics, left + 46, y, 116, 8, state.quality(),
                state.quality() >= 0.5F ? ACCENT : (state.quality() >= 0.2F ? WARN : BAD));
        Component meter = state.heard() == 0
                ? Component.translatable("gui.openradio.station.nobody")
                : Component.translatable("gui.openradio.station.meter",
                        String.format("%.0f%%", state.quality() * 100.0F), state.heard());
        graphics.drawString(font, meter, left + 170, y + 1, MUTED, false);

        int dividerY = top + 116;
        graphics.fill(left + 10, dividerY, left + PANEL_W - 10, dividerY + 1, 0x33FFFFFF);
        graphics.drawString(font, Component.translatable("gui.openradio.station.inbox"),
                left + 10, dividerY + 6, ACCENT, false);
    }

    private void renderInbox(GuiGraphics graphics, S2CStationState state) {
        int y = top + 134;
        if (state.inbox().isEmpty()) {
            graphics.drawString(font, Component.translatable("gui.openradio.station.inbox_empty"),
                    left + 12, y, MUTED, false);
            return;
        }
        int rows = Math.min(INBOX_ROWS, state.inbox().size());
        for (int i = 0; i < rows; i++) {
            S2CStationState.InboxEntry entry = state.inbox().get(i);
            // 质量差的消息用暗色，和聊天栏里的降级观感保持一致。
            int color = entry.quality() < 0.35F ? 0xFF6A6A64 : MUTED;
            Component line = Component.literal("[" + String.format("%05d", state.frequency()) + "] ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(entry.senderName()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(": ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(trim(entry.text(), 44)));
            graphics.drawString(font, line, left + 12, y, color, false);
            y += 12;
        }
    }

    private static String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private void drawBar(GuiGraphics graphics, int x, int y, int width, int height,
                         float ratio, int color) {
        graphics.fill(x, y, x + width, y + height, 0xFF20262A);
        int filled = Math.round(width * Math.max(0.0F, Math.min(1.0F, ratio)));
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, color);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // 不暂停单机世界：固定台收到的消息应该能实时看到。
        return false;
    }
}
