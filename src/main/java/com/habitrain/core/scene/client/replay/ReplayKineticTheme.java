package com.habitrain.core.scene.client.replay;

import com.habitrain.core.scene.replay.ReplayExportContentOptions.ExportMode;

import static com.habitrain.core.scene.client.replay.ReplayKineticKit.*;

/** One kinetic (MV style) visual identity per export mode; all share the motion vocabulary of {@link ReplayKineticKit}. */
enum ReplayKineticTheme {
    /** Short replay: closest to the reference MV, a fast short-video cut. */
    KINETIC_CUT("KC", "KINETIC CUT", PAPER, INK, INK_2, RED, BLUE, HAIR),
    /** Personal replay: a quiet single-subject dossier. */
    DOSSIER("DS", "DOSSIER", PAPER, INK, INK_2, RED, BLUE, HAIR),
    /** Scene replay: an engineering blueprint of the carriage. */
    BLUEPRINT("BP", "BLUEPRINT", BP_PAPER, BP_INK, BP_DARK, RED, BP_BLUE, BP_GRID),
    /** Auto edit: the full music video, every graphic on the beat. */
    MUSIC_VIDEO("MV", "MUSIC VIDEO", PAPER, INK, INK_2, RED, BLUE, HAIR);

    final String code, title;
    final int paper, ink, night, red, blue, hair;

    ReplayKineticTheme(String code, String title, int paper, int ink, int night, int red, int blue, int hair) {
        this.code = code;
        this.title = title;
        this.paper = paper;
        this.ink = ink;
        this.night = night;
        this.red = red;
        this.blue = blue;
        this.hair = hair;
    }

    static ReplayKineticTheme of(ExportMode mode) {
        if (mode == null) return KINETIC_CUT;
        return switch (mode) {
            case SHORT -> KINETIC_CUT;
            case PERSONAL -> DOSSIER;
            case SANDBOX_FULL -> BLUEPRINT;
            case CINEMATIC_PROMO -> MUSIC_VIDEO;
        };
    }

    /** Background of the live 3D action (always dark, per the art direction). */
    int actionBackdrop() { return this == BLUEPRINT ? BP_DARK : INK; }

    int actionGrid() { return this == BLUEPRINT ? BP_DARK_GRID : 0xFF1A1F2C; }
}
