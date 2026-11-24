package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Plain data handed from the replay model to the kinetic scenes (keeps the scenes previewable without a client). */
final class ReplayKineticData {
    private ReplayKineticData() {}

    record Person(@Nullable UUID id, Component name, Component role, int color, boolean mvp, boolean winner) {}

    /** Frame chrome: top-left section code/label, bottom-right timecode, bottom-left progress label. */
    record Chrome(String code, String label, String right, String bottomLeft, double progress, int accent) {}

    record Title(Component map, Component mode, String date, String duration, int players, String trainNo,
                 @Nullable Person subject, String view, String pace, float mapW, float mapD, float mapH) {}

    /** {@code word} is the display headline (FIRST BLOOD ...), {@code tag} the localized line under it. */
    record Kill(String word, Component tag, @Nullable Person killer, @Nullable Person victim, Component weapon,
                int accent, int index, int streak, boolean subjectInvolved) {}

    record Hud(String clock, String speed, int speedKind, int alive, int total, int[] colors, boolean[] states,
               double progress, double[] markers, int[] markerColors, Component map, String endClock,
               @Nullable Person focus) {}

    record FeedRow(Component line, int color, double age) {}

    record Stat(Component label, String value, int color) {}

    record Victory(Component title, Component subtitle, int winColor, List<Person> winners,
                   @Nullable Person subject, @Nullable Boolean subjectWon) {}

    record Mvp(Person who, List<Stat> stats, double[] ratios) {}

    record Row(int rank, Person who, int kills, int score, String survival, boolean focus) {}

    record Outro(List<Row> rows, int page, int pages, double pageAlpha, float slide, boolean initialEntry,
                 Component killsLine, Component footer, Component heading) {}
}
