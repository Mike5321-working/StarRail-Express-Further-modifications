package com.habitrain.core.scene.client.replay;

import com.habitrain.core.network.replay.ReplayBoardSettingsPackets;
import com.habitrain.core.scene.block.ReplayBoardSettings;
import com.habitrain.core.scene.block.SettlementSummaryBoard;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.habitrain.core.network.replay.ReplayBoardSettingsPackets.CheckedResult.Status;

/**
 * Non-pausing creative-mode table editor styled in the Star-Night Express Art Deco visual system.
 * Previews placement and 4-way block facing live in world space; only Save writes to the server.
 */
public final class ReplayBoardSettingsScreen extends Screen {
    private static final String[] KEYS = {"size", "height", "offset_x", "offset_z", "rotation"};
    private static final float[] MIN = {1, 0, -16, -16, -180}, MAX = {32, 16, 16, 16, 180};
    private static final Direction[] CARDINALS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    private final ResourceLocation dimension;
    private final BlockPos pos;
    private final String[] values = new String[5];
    private final EditBox[] fields = new EditBox[5];
    private final List<Button> stepButtons = new ArrayList<>();
    private final ArtDecoButton[] facingButtons = new ArtDecoButton[4];
    private ArtDecoButton faceMeButton;
    private ArtDecoButton save;

    private ReplayBoardSettings baseline;
    private Direction baselineFacing;
    private Direction draftFacing;
    private Direction initialFacing;
    private String[] initialValues;
    private long replyDeadline;
    private UUID requestId;
    private boolean conflicted;
    private Component status;
    private int left, top, panelWidth, panelHeight, rowStep, facingRowY;

    public ReplayBoardSettingsScreen(ResourceLocation dimension, BlockPos pos, ReplayBoardSettings settings) {
        this(dimension, pos, settings, Direction.NORTH);
    }

    public ReplayBoardSettingsScreen(ResourceLocation dimension, BlockPos pos,
                                     ReplayBoardSettings settings, Direction facing) {
        super(tr("title"));
        this.dimension = dimension;
        this.pos = pos.immutable();
        Direction validFacing = facing != null && facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
        setValues(settings);
        baseline = settings;
        baselineFacing = validFacing;
        draftFacing = validFacing;
        initialFacing = validFacing;
        initialValues = values.clone();
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("habitrain_core.replay_board.settings." + key, args);
    }

    private static String number(float value) {
        return String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private void setValues(ReplayBoardSettings s) {
        float[] numbers = {s.mapSize(), s.height(), s.offsetX(), s.offsetZ(), s.rotation()};
        for (int i = 0; i < values.length; i++) values[i] = number(numbers[i]);
    }

    @Override protected void init() {
        panelWidth = Math.min(368, width - 16);
        panelHeight = Math.min(308, height - 12);
        left = width > 600 ? width - panelWidth - 12 : (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rowStep = Math.min(30, Math.max(22, (panelHeight - 134) / 5));
        stepButtons.clear();

        for (int i = 0; i < fields.length; i++) {
            final int index = i;
            int y = top + 44 + i * rowStep, x = left + panelWidth - 117;
            EditBox field = new EditBox(font, x, y, 64, 20, tr(KEYS[i]));
            field.setMaxLength(12);
            field.setFilter(text -> text.matches("-?\\d*(\\.\\d*)?"));
            field.setValue(values[i]);
            field.setTooltip(Tooltip.create(tr("range", number(MIN[i]), number(MAX[i]), i == 4 ? "15°" : "0.25")));
            fields[i] = addRenderableWidget(field);
            field.setResponder(text -> {
                values[index] = text;
                status = null;
                refresh();
            });
            stepButtons.add(addRenderableWidget(new ArtDecoButton(x - 23, y, 20, 20, Component.literal("−"),
                    button -> step(index, -1))));
            stepButtons.add(addRenderableWidget(new ArtDecoButton(x + 67, y, 20, 20, Component.literal("+"),
                    button -> step(index, 1))));
        }

        // 4-way primary front facing row: [North] [East] [South] [West] [Face Me]
        facingRowY = top + 44 + fields.length * rowStep + 4;
        int facingStartX = left + panelWidth - 228;
        int facingGap = 3;
        int dirW = 36;
        for (int i = 0; i < CARDINALS.length; i++) {
            Direction dir = CARDINALS[i];
            ArtDecoButton btn = new ArtDecoButton(
                    facingStartX + i * (dirW + facingGap), facingRowY, dirW, 20,
                    ReplayBoardTexts.facing(dir), button -> {
                        draftFacing = dir;
                        status = null;
                        refresh();
                    });
            btn.setTooltip(Tooltip.create(tr("facing_tip")));
            facingButtons[i] = addRenderableWidget(btn);
            stepButtons.add(btn);
        }
        int faceMeX = facingStartX + CARDINALS.length * (dirW + facingGap);
        int faceMeW = Math.max(48, (left + panelWidth - 12) - faceMeX);
        faceMeButton = addRenderableWidget(new ArtDecoButton(faceMeX, facingRowY, faceMeW, 20,
                tr("facing_towards_me"), button -> {
                    if (minecraft != null && minecraft.player != null) {
                        draftFacing = minecraft.player.getDirection().getOpposite();
                        status = null;
                        refresh();
                    }
                }));
        faceMeButton.setTooltip(Tooltip.create(tr("facing_tip")));
        stepButtons.add(faceMeButton);

        int gap = 6, bw = (panelWidth - 24 - gap * 2) / 3, by = top + panelHeight - 30;
        stepButtons.add(addRenderableWidget(new ArtDecoButton(left + 12, by, bw, 20, tr("reset"), button -> {
            setValues(ReplayBoardSettings.DEFAULT);
            for (int i = 0; i < fields.length; i++) fields[i].setValue(values[i]);
            draftFacing = baselineFacing;
            refresh();
        })));
        save = addRenderableWidget(new ArtDecoButton(left + 12 + bw + gap, by, bw, 20, tr("save"), button -> save()));
        save.accentGold = true;
        addRenderableWidget(new ArtDecoButton(left + 12 + (bw + gap) * 2, by, bw, 20, CommonComponents.GUI_CANCEL,
                button -> onClose()));
        refresh();
    }

    private void step(int index, int direction) {
        float current;
        try { current = Float.parseFloat(values[index]); }
        catch (NumberFormatException invalid) { current = index == 0 ? 6.25f : 0; }
        float next = Math.clamp(current + direction * (index == 4 ? 15f : 0.25f), MIN[index], MAX[index]);
        fields[index].setValue(number(next));
    }

    private @Nullable ReplayBoardSettings draft() {
        try {
            return new ReplayBoardSettings(Float.parseFloat(values[0]), Float.parseFloat(values[1]),
                    Float.parseFloat(values[2]), Float.parseFloat(values[3]), Float.parseFloat(values[4]));
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private void refresh() {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] == null) continue;
            boolean valid = false;
            try {
                float value = Float.parseFloat(values[i]);
                valid = Float.isFinite(value) && value >= MIN[i] && value <= MAX[i];
            } catch (NumberFormatException ignored) {}
            fields[i].setTextColor(valid ? ReplayPalette.CHAMPAGNE : 0xFFFF8888);
            fields[i].setEditable(replyDeadline == 0);
        }
        for (Button button : stepButtons) button.active = replyDeadline == 0;
        for (int i = 0; i < CARDINALS.length; i++) {
            if (facingButtons[i] != null) facingButtons[i].selected = CARDINALS[i] == draftFacing;
        }
        if (faceMeButton != null && minecraft != null && minecraft.player != null) {
            faceMeButton.selected = minecraft.player.getDirection().getOpposite() == draftFacing;
        }
        if (save != null) save.active = replyDeadline == 0 && !conflicted && draft() != null
                && (ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.CheckedSave.TYPE)
                    || ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.Save.TYPE));
    }

    private void save() {
        ReplayBoardSettings settings = draft();
        if (replyDeadline != 0 || conflicted || settings == null) return;
        boolean checked = ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.CheckedSave.TYPE);
        if (!checked && !ClientPlayNetworking.canSend(ReplayBoardSettingsPackets.Save.TYPE)) return;
        var request = new ReplayBoardSettingsPackets.Save(dimension, pos, settings, draftFacing);
        requestId = checked ? UUID.randomUUID() : null;
        replyDeadline = System.nanoTime() + 5_000_000_000L;
        status = tr("saving");
        refresh();
        if (checked) {
            ClientPlayNetworking.send(new ReplayBoardSettingsPackets.CheckedSave(
                    requestId, baseline, baselineFacing, request));
        } else {
            ClientPlayNetworking.send(request);
        }
    }

    public void accept(ReplayBoardSettingsPackets.Result result) {
        if (replyDeadline == 0 || requestId != null || !dimension.equals(result.dimension()) || !pos.equals(result.pos())) return;
        finish(result.saved() ? Status.SAVED : Status.REJECTED);
    }

    public void accept(ReplayBoardSettingsPackets.CheckedResult result) {
        if (replyDeadline != 0 && result.requestId().equals(requestId)) finish(result.status());
    }

    private void finish(Status result) {
        replyDeadline = 0;
        requestId = null;
        if (result == Status.SAVED) {
            if (minecraft.player != null) minecraft.player.displayClientMessage(tr("saved"), true);
            onClose();
        } else {
            conflicted = result == Status.CONFLICT;
            status = tr(conflicted ? "conflict" : "rejected");
            refresh();
        }
    }

    /** Renderer reads this draft instead of changing the synchronized block entity. */
    public static @Nullable ReplayBoardSettings previewAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ReplayBoardSettingsScreen screen && screen.pos.equals(pos)
                && mc.level != null && screen.dimension.equals(mc.level.dimension().location())) return screen.draft();
        return null;
    }

    /** Renderer reads this draft facing to preview 4-way orientation changes before saving. */
    public static @Nullable Direction previewFacingAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ReplayBoardSettingsScreen screen && screen.pos.equals(pos)
                && mc.level != null && screen.dimension.equals(mc.level.dimension().location())) return screen.draftFacing;
        return null;
    }

    @Override public void tick() {
        if (minecraft.player == null || minecraft.level == null || !minecraft.player.isCreative()
                || !minecraft.player.isAlive() || !minecraft.player.mayBuild()
                || !dimension.equals(minecraft.level.dimension().location())
                || !minecraft.player.canInteractWithBlock(pos, 1.0)
                || !minecraft.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()),
                        SectionPos.blockToSectionCoord(pos.getZ()))
                || !minecraft.level.getBlockState(pos).is(SettlementSummaryBoard.BLOCK)
                || !(minecraft.level.getBlockEntity(pos) instanceof SettlementSummaryBoard.Marker marker)) {
            onClose();
            return;
        }
        if (replyDeadline != 0 && System.nanoTime() - replyDeadline >= 0) {
            replyDeadline = 0;
            requestId = null;
            status = tr("save_timeout");
            refresh();
        }
        Direction liveFacing = minecraft.level.getBlockState(pos).getValue(SettlementSummaryBoard.FACING);
        if (replyDeadline == 0 && (!baseline.equals(marker.settings()) || baselineFacing != liveFacing)) {
            boolean dirty = !Arrays.equals(initialValues, values) || draftFacing != initialFacing;
            if (dirty) {
                conflicted = true;
                refresh();
            } else {
                baseline = marker.settings();
                baselineFacing = liveFacing;
                draftFacing = liveFacing;
                initialFacing = liveFacing;
                conflicted = false;
                setValues(baseline);
                initialValues = values.clone();
                for (int i = 0; i < fields.length; i++) fields[i].setValue(values[i]);
                refresh();
            }
        }
    }

    @Override public boolean isPauseScreen() { return false; }

    @Override public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
        g.fillGradient(0, 0, width, height, 0x50040810, 0x90040810);
        g.fillGradient(left, top, left + panelWidth, top + panelHeight, 0xF2111A33, 0xF404060D);
        g.renderOutline(left, top, panelWidth, panelHeight, ReplayPalette.BRASS);
        g.renderOutline(left + 3, top + 3, panelWidth - 6, panelHeight - 6, 0x998A6A35);

        int cx = left + panelWidth / 2;
        int titleW = font.width(title);
        int ruleY = top + 15;
        int lx0 = left + 14, lx1 = cx - titleW / 2 - 12;
        int rx0 = cx + titleW / 2 + 12, rx1 = left + panelWidth - 14;
        if (lx1 > lx0 + 4) {
            g.fill(lx0, ruleY, lx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            drawDiamond(g, lx1 + 4, ruleY, ReplayPalette.BRASS_LIGHT);
        }
        if (rx1 > rx0 + 4) {
            g.fill(rx0, ruleY, rx1, ruleY + 1, ReplayPalette.BRASS_DIM);
            drawDiamond(g, rx0 - 4, ruleY, ReplayPalette.BRASS_LIGHT);
        }
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        int cx = left + panelWidth / 2;
        g.drawCenteredString(font, title, cx, top + 11, ReplayPalette.BRASS_LIGHT);
        g.drawCenteredString(font, font.plainSubstrByWidth(tr("preview").getString(), panelWidth - 24),
                cx, top + 27, ReplayPalette.TEXT_MUTED);
        for (int i = 0; i < fields.length; i++) {
            g.drawString(font, tr(KEYS[i]), left + 14, top + 50 + i * rowStep, ReplayPalette.CHAMPAGNE, false);
        }
        g.drawString(font, tr("facing"), left + 14, facingRowY + 6, ReplayPalette.CHAMPAGNE, false);
        int hintY = top + panelHeight - 61;
        g.drawString(font, font.plainSubstrByWidth(tr("hint").getString(), panelWidth - 28),
                left + 14, hintY, ReplayPalette.TEXT_MUTED, false);
        Component message = conflicted ? tr("conflict") : status != null ? status : draft() == null ? tr("invalid") : tr("hint_offsets");
        g.drawString(font, font.plainSubstrByWidth(message.getString(), panelWidth - 28), left + 14, hintY + 13,
                conflicted || draft() == null ? ReplayPalette.RUBY : ReplayPalette.BRASS_LIGHT, false);
    }

    private static void drawDiamond(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y - 2, x + 1, y + 3, color);
        g.fill(x - 1, y - 1, x + 2, y + 2, color);
        g.fill(x - 2, y, x + 3, y + 1, color);
    }

    private static final class ArtDecoButton extends Button {
        boolean selected;
        boolean accentGold;

        ArtDecoButton(int x, int y, int w, int h, Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, DEFAULT_NARRATION);
        }

        @Override public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float delta) {
            boolean hover = active && isHoveredOrFocused();
            int x0 = getX(), y0 = getY(), x1 = x0 + getWidth(), y1 = y0 + getHeight();
            int topC, botC, border, text;
            if (!active) {
                topC = 0xFF0E1426; botC = 0xFF080C18; border = 0xFF3B3322; text = ReplayPalette.TEXT_FAINT;
            } else if (selected || accentGold) {
                topC = hover ? 0xFF56431D : 0xFF3E3015; botC = 0xFF1F170A;
                border = ReplayPalette.BRASS_LIGHT; text = ReplayPalette.CHAMPAGNE;
            } else if (hover) {
                topC = 0xFF26365C; botC = 0xFF131D38; border = ReplayPalette.BRASS; text = 0xFFFFFFFF;
            } else {
                topC = 0xFF18233F; botC = 0xFF0C1326; border = ReplayPalette.BRASS_DIM; text = ReplayPalette.TEXT;
            }
            g.fillGradient(x0, y0, x1, y1, topC, botC);
            g.renderOutline(x0, y0, getWidth(), getHeight(), border);
            var font = Minecraft.getInstance().font;
            String label = font.plainSubstrByWidth(getMessage().getString(), getWidth() - 4);
            g.drawCenteredString(font, label, x0 + getWidth() / 2, y0 + (getHeight() - 8) / 2, text);
        }
    }
}
