package com.rinko.openradio.client.screen;

import com.rinko.openradio.client.ClientRadioState;
import com.rinko.openradio.energy.RadioEnergy;
import com.rinko.openradio.item.HandheldRadioItem;
import com.rinko.openradio.network.C2SSetFrequency;
import com.rinko.openradio.network.C2SSetPowered;
import com.rinko.openradio.network.C2SSetSquelch;
import com.rinko.openradio.network.C2STransmit;
import com.rinko.openradio.radio.AntennaKind;
import com.rinko.openradio.radio.Antennas;
import com.rinko.openradio.radio.RadioBand;
import com.rinko.openradio.radio.RadioBands;
import com.rinko.openradio.radio.RadioConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 电台操作面板。
 *
 * <p>这个界面<b>不做任何判定</b>：调频、开关机、静噪、发射都只是发一个请求包出去，
 * 界面上立刻有反应只是因为服务端改了组件再同步回来。所以它不能、也不会被用来作弊。
 *
 * <p>电池和天线都没有槽位 —— 电池是"另一只手持电池右键"，天线是副手。
 * 这个界面只负责<b>显示</b>它们的状态，不做拖放，也就没有容器同步可言。
 */
public class RadioScreen extends Screen {
    private static final int PANEL_W = 224;
    private static final int PANEL_H = 186;
    private static final int PANEL_COLOR = 0xE0101418;
    private static final int BORDER_COLOR = 0xFF3FA88F;
    private static final int MUTED = 0xFF8A8A80;
    private static final int ACCENT = 0xFF9FE1CB;
    private static final int WARN = 0xFFC9A227;
    private static final int BAD = 0xFFC24B3A;

    /** 静噪的调节步长。5% 一格，按住连点也不会跳过有用的档位。 */
    private static final float SQUELCH_STEP = 0.05F;
    private static final float SQUELCH_MAX = 0.80F;

    /**
     * 打开界面时的电台引用，仅作兜底。
     *
     * <p><b>不能直接用它读数据。</b>服务端改完数据组件同步回来时，原版是把这个槽位里的
     * ItemStack <b>整个替换掉</b>，而不是原地修改。所以这个引用会很快变成"上一帧的旧对象"，
     * 拿它读频率会一直读到旧值 —— 这正是"按下回车后波段名不刷新，重开界面才对"的原因。
     */
    private final ItemStack initialRadio;

    private EditBox frequencyBox;
    private EditBox messageBox;
    private Button powerButton;

    /** 静噪的本地值。见 {@link #tick()} 里关于"本地先改、稍后再跟服务端对齐"的说明。 */
    private float squelch;
    private int squelchHoldTicks;

    private int left;
    private int top;

    public RadioScreen(ItemStack radio) {
        super(Component.translatable("gui.openradio.title"));
        this.initialRadio = radio;
        this.squelch = HandheldRadioItem.getSquelch(radio);
    }

    /**
     * 取当前真正在手里的电台。
     *
     * <p>每次都从玩家手上重新解析，而不是缓存引用 —— 界面打开期间玩家无法切换手持物，
     * 所以这个查询是稳定且便宜的，而且能自动跟上服务端的同步。
     */
    private ItemStack radio() {
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            ItemStack current = HandheldRadioItem.findRadio(player);
            if (!current.isEmpty()) {
                return current;
            }
        }
        return initialRadio;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        frequencyBox = new EditBox(font, left + 10, top + 26, 64, 18,
                Component.translatable("gui.openradio.frequency"));
        frequencyBox.setMaxLength(5);
        frequencyBox.setFilter(text -> text.matches("\\d*"));
        frequencyBox.setValue(String.format("%05d", HandheldRadioItem.getFrequency(radio())));
        addRenderableWidget(frequencyBox);

        addRenderableWidget(Button.builder(Component.literal("<"), button -> step(-100))
                .bounds(left + 78, top + 26, 20, 18)
                .build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> step(100))
                .bounds(left + 102, top + 26, 20, 18)
                .build());

        messageBox = new EditBox(font, left + 10, top + 50, 204, 18,
                Component.translatable("gui.openradio.message"));
        messageBox.setMaxLength(120);
        addRenderableWidget(messageBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.openradio.send"),
                        button -> sendMessage())
                .bounds(left + 10, top + 74, 96, 20)
                .build());

        powerButton = Button.builder(powerLabel(), button -> togglePower())
                .bounds(left + 114, top + 74, 100, 20)
                .build();
        addRenderableWidget(powerButton);

        addRenderableWidget(Button.builder(Component.literal("-"), button -> adjustSquelch(-SQUELCH_STEP))
                .bounds(left + 52, top + 128, 16, 14)
                .build());
        addRenderableWidget(Button.builder(Component.literal("+"), button -> adjustSquelch(SQUELCH_STEP))
                .bounds(left + 136, top + 128, 16, 14)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        // 开关机状态可能来自右键，也可能来自服务端的纠正，所以每 tick 刷新一次标签。
        if (powerButton != null) {
            powerButton.setMessage(powerLabel());
        }

        // 静噪：点一下立刻改本地值，否则要等服务端回包才动，连点会像卡住。
        // 但本地权威只保留半秒 —— 之后重新以物品上的值为准，避免和服务端长期不一致。
        if (squelchHoldTicks > 0) {
            squelchHoldTicks--;
        } else {
            squelch = HandheldRadioItem.getSquelch(radio());
        }
    }

    private Component powerLabel() {
        return Component.translatable(HandheldRadioItem.isPowered(radio())
                ? "gui.openradio.power_off"
                : "gui.openradio.power_on");
    }

    private void step(int delta) {
        int current = parseFrequency();
        applyFrequency(current + delta);
    }

    private void applyFrequency(int frequency) {
        int clamped = RadioConstants.clampFrequency(frequency);
        PacketDistributor.sendToServer(new C2SSetFrequency(clamped));
        frequencyBox.setValue(String.format("%05d", clamped));
        frequencyBox.setFocused(false);
    }

    private int parseFrequency() {
        String raw = frequencyBox.getValue().trim();
        if (raw.isEmpty()) {
            return HandheldRadioItem.getFrequency(radio());
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return HandheldRadioItem.getFrequency(radio());
        }
    }

    private void togglePower() {
        PacketDistributor.sendToServer(new C2SSetPowered(!HandheldRadioItem.isPowered(radio())));
    }

    private void adjustSquelch(float delta) {
        float next = Math.max(0.0F, Math.min(SQUELCH_MAX, Math.round((squelch + delta) * 100.0F) / 100.0F));
        if (next == squelch) {
            return;
        }
        squelch = next;
        squelchHoldTicks = 10;
        PacketDistributor.sendToServer(new C2SSetSquelch(next));
    }

    private void sendMessage() {
        String text = messageBox.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new C2STransmit(text));
        messageBox.setValue("");
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 在输入框里按回车 = 确认，而不是关界面。
        if (keyCode == 257) { // GLFW_KEY_ENTER
            if (frequencyBox.isFocused()) {
                applyFrequency(parseFrequency());
                return true;
            }
            if (messageBox.isFocused()) {
                sendMessage();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 不调用 super：省掉原版的模糊与压暗，面板要贴着世界显示。
        graphics.fill(left - 4, top - 4, left + PANEL_W + 4, top + PANEL_H + 4, PANEL_COLOR);
        graphics.fill(left - 4, top - 4, left + PANEL_W + 4, top - 3, BORDER_COLOR);
        graphics.fill(left - 4, top + PANEL_H + 3, left + PANEL_W + 4, top + PANEL_H + 4, BORDER_COLOR);
        graphics.fill(left - 4, top - 4, left - 3, top + PANEL_H + 4, BORDER_COLOR);
        graphics.fill(left + PANEL_W + 3, top - 4, left + PANEL_W + 4, top + PANEL_H + 4, BORDER_COLOR);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, left + 10, top + 8, ACCENT, false);

        RadioBand band = RadioBands.of(HandheldRadioItem.getFrequency(radio()));
        Component bandName = Component.translatable(band.translationKey());
        graphics.drawString(font, bandName,
                left + PANEL_W - 10 - font.width(bandName), top + 8, MUTED, false);

        drawBattery(graphics, left + 10, top + 100);
        drawAntenna(graphics, left + 10, top + 114, band);

        Component squelchLabel = Component.translatable("gui.openradio.squelch");
        graphics.drawString(font, squelchLabel, left + 10, top + 131, MUTED, false);
        Component squelchValue = squelch <= 0.0F
                ? Component.translatable("gui.openradio.squelch.off")
                : Component.literal(String.format("%.0f%%", squelch * 100.0F));
        graphics.drawString(font, squelchValue, left + 76, top + 131,
                squelch <= 0.0F ? MUTED : ACCENT, false);

        Component status = statusLine();
        graphics.drawString(font, status, left + 10, top + 146, MUTED, false);
        drawSignalBar(graphics, left + 10, top + 159, 204, 5);

        graphics.drawString(font, Component.translatable("gui.openradio.hint"),
                left + 10, top + 170, MUTED, false);
    }

    private Component statusLine() {
        if (!RadioEnergy.hasBattery(radio())) {
            return Component.translatable("gui.openradio.no_battery").withStyle(ChatFormatting.RED);
        }
        if (!ClientRadioState.active()) {
            return Component.translatable("gui.openradio.offline");
        }
        if (ClientRadioState.heard() > 0) {
            return Component.translatable("gui.openradio.status",
                    String.format("%.0f%%", ClientRadioState.quality() * 100.0F),
                    Component.translatable(ClientRadioState.qualityKey()),
                    ClientRadioState.heard());
        }
        return Component.translatable("gui.openradio.only_self",
                String.format("%.0f%%", ClientRadioState.quality() * 100.0F));
    }

    /** 电池条。没有电池时显示一行红字，而不是一条空条 —— 空条看起来像"电量耗尽"。 */
    private void drawBattery(GuiGraphics graphics, int x, int y) {
        if (!RadioEnergy.hasBattery(radio())) {
            graphics.drawString(font, Component.translatable("gui.openradio.no_battery"),
                    x, y, BAD, false);
            return;
        }
        float ratio = RadioEnergy.ratio(radio());
        boolean low = ratio < 0.10F;
        Component label = Component.translatable("gui.openradio.battery",
                String.format("%,d", RadioEnergy.stored(radio())),
                String.format("%,d", RadioEnergy.capacity(radio())));
        graphics.drawString(font, label, x, y, low ? BAD : MUTED, false);

        int barX = x + 140;
        int barW = 64;
        graphics.fill(barX - 1, y - 1, barX + barW + 1, y + 7, 0xFF2A2A28);
        int filled = Math.round(barW * ratio);
        if (filled > 0) {
            graphics.fill(barX, y, barX + filled, y + 6,
                    ratio >= 0.10F ? 0xFF3FA88F : BAD);
        }
    }

    /**
     * 天线状态。装错波段时明确写出"不匹配（当前短波）"——
     * 这是玩家能自己诊断问题的唯一线索，不能让他对着一个低数字猜。
     */
    private void drawAntenna(GuiGraphics graphics, int x, int y, RadioBand band) {
        Player player = Minecraft.getInstance().player;
        AntennaKind antenna = player == null ? AntennaKind.BARE : Antennas.resolve(player);

        Component antennaName = Component.translatable(antenna.translationKey());
        int color;
        Component suffix;
        if (antenna == AntennaKind.BARE) {
            color = WARN;
            suffix = Component.translatable("gui.openradio.antenna.missing");
        } else if (antenna.mismatched(band)) {
            color = BAD;
            suffix = Component.translatable("gui.openradio.antenna.mismatched", bandName(band));
        } else {
            color = ACCENT;
            suffix = Component.translatable("gui.openradio.antenna.ok");
        }
        graphics.drawString(font, Component.translatable("gui.openradio.antenna"),
                x, y, MUTED, false);
        int nameX = x + font.width(Component.translatable("gui.openradio.antenna"));
        graphics.drawString(font, antennaName, nameX, y, color, false);
        graphics.drawString(font, suffix, nameX + font.width(antennaName) + 4, y, color, false);
    }

    private static Component bandName(RadioBand band) {
        return Component.translatable(band.translationKey());
    }

    /**
     * 信号强度条。
     *
     * <p>画的是汇总 Q（最好那条链路），因为它的作用是"我现在能不能通到人"，
     * 而不是"某一个人的声音有多清楚" —— 后者在语音上是逐个说话人各自处理的。
     */
    private void drawSignalBar(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF2A2A28);
        float q = Math.max(0.0F, Math.min(1.0F, ClientRadioState.quality()));
        int filled = Math.round(width * q);
        if (filled > 0) {
            graphics.fill(x, y, x + filled, y + height, signalColor(q));
        }
        // 静噪阈值画一条竖线，让玩家直观看到"这条线以下我听不见"。
        if (squelch > 0.0F) {
            int markX = x + Math.round(width * Math.min(1.0F, squelch));
            graphics.fill(markX, y - 1, markX + 1, y + height + 1, 0xFFC9A227);
        }
    }

    private static int signalColor(float quality) {
        if (quality >= 0.80F) {
            return 0xFF3FA88F;
        }
        if (quality >= 0.50F) {
            return 0xFFC9A227;
        }
        if (quality >= 0.20F) {
            return 0xFFD1783A;
        }
        return 0xFFC24B3A;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
