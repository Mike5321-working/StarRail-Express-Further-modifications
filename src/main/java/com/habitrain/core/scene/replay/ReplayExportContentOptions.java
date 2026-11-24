package com.habitrain.core.scene.replay;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * Immutable configuration model for the freeform replay video content customizer across all 4 export
 * modes, covering 4 categories (14 core toggles &amp; controls):
 * <ol>
 *     <li>Structure &amp; cinematic framing (6): title, review overview, victory/MVP, outro, letterbox/DOF, watermark;</li>
 *     <li>Tactical HUD &amp; visual filters (6): timecode, alive matrix, kill feed, timeline, role identity reveal
 *     (omniscient vs suspense fog vs hidden), trail/nameplate actor filter (plus tactical skill fallback toasts);</li>
 *     <li>Multi-track audio mix (voice volume slider, game SFX volume slider, procedural music level, Auto-Ducking);</li>
 *     <li>Clip time range trimming ({@code trimStartTick .. trimEndTick}).</li>
 * </ol>
 */
public record ReplayExportContentOptions(
        ExportMode mode,
        PersonalView personalView,
        PersonalPacing personalPacing,
        PromoScope promoScope,
        @Nullable UUID focusPlayer,
        boolean roofCutaway,
        // Category 1: Structure & cinematic packaging (6)
        boolean includeTitle,
        boolean includeReview,
        boolean includeVictoryMvp,
        boolean includeOutro,
        boolean includeLetterboxDof,
        boolean includeWatermark,
        // Category 2: Tactical HUD & anti-spoiler filters (6 + skill toasts)
        boolean showTimecode,
        boolean showAliveMatrix,
        boolean showKillFeed,
        boolean showTimeline,
        IdentityReveal identityReveal,
        ActorOverlayFilter trailFilter,
        ActorOverlayFilter nameplateFilter,
        boolean showSkillToasts,
        boolean splitScreenKillPov,
        // Category 3: Multi-track audio & Auto-Ducking
        float voiceVolume,
        float sfxVolume,
        ReplayMusicPlan.Level musicLevel,
        boolean autoDucking,
        // Category 4: Clip trim window in match ticks (trimEndTick < 0 means full duration)
        int trimStartTick,
        int trimEndTick
) {
    public static final float MIN_VOLUME = 0.0f;
    public static final float MAX_VOLUME = 2.0f;

    public ReplayExportContentOptions {
        mode = mode == null ? ExportMode.SHORT : mode;
        personalView = personalView == null ? PersonalView.TRUE_POV : personalView;
        personalPacing = personalPacing == null ? PersonalPacing.SPEED_1X : personalPacing;
        promoScope = promoScope == null ? PromoScope.GLOBAL_ENSEMBLE : promoScope;
        identityReveal = identityReveal == null ? IdentityReveal.OMNISCIENT : identityReveal;
        trailFilter = trailFilter == null ? ActorOverlayFilter.ALL : trailFilter;
        nameplateFilter = nameplateFilter == null ? ActorOverlayFilter.ALL : nameplateFilter;
        voiceVolume = clampVolume(voiceVolume);
        sfxVolume = clampVolume(sfxVolume);
        musicLevel = musicLevel == null ? ReplayMusicPlan.Level.OFF : musicLevel;
        trimStartTick = Math.max(0, trimStartTick);
        if (trimEndTick >= 0 && trimEndTick < trimStartTick) {
            trimEndTick = trimStartTick;
        }
    }

    /** Four top-level replay video export modes. */
    public enum ExportMode {
        SHORT("short"),
        PERSONAL("personal"),
        SANDBOX_FULL("sandbox_full"),
        CINEMATIC_PROMO("cinematic_promo");

        public final String id;

        ExportMode(String id) { this.id = id; }

        public ExportMode next() {
            ExportMode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static ExportMode fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (ExportMode m : values()) if (m.id.equals(norm)) return m;
            }
            return SHORT;
        }
    }

    /** Perspective option for {@link ExportMode#PERSONAL}. */
    public enum PersonalView {
        TRUE_POV("true_pov"),
        SANDBOX_FOLLOW("sandbox_follow");

        public final String id;

        PersonalView(String id) { this.id = id; }

        public PersonalView toggle() {
            return this == TRUE_POV ? SANDBOX_FOLLOW : TRUE_POV;
        }

        public static PersonalView fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (PersonalView v : values()) if (v.id.equals(norm)) return v;
            }
            return TRUE_POV;
        }
    }

    /** Speed and highlight editing option for {@link ExportMode#PERSONAL}. */
    public enum PersonalPacing {
        SPEED_1X("1x", 1.0),
        SPEED_2X("2x", 2.0),
        SPEED_4X("4x", 4.0),
        HIGHLIGHTS_ONLY("highlights", 1.0);

        public final String id;
        public final double speedMultiplier;

        PersonalPacing(String id, double speedMultiplier) {
            this.id = id;
            this.speedMultiplier = speedMultiplier;
        }

        public PersonalPacing next() {
            PersonalPacing[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static PersonalPacing fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (PersonalPacing p : values()) if (p.id.equals(norm)) return p;
            }
            return SPEED_1X;
        }
    }

    /** Scope selector for {@link ExportMode#CINEMATIC_PROMO}. */
    public enum PromoScope {
        GLOBAL_ENSEMBLE("global"),
        PERSONAL_MVP("personal_mvp");

        public final String id;

        PromoScope(String id) { this.id = id; }

        public PromoScope toggle() {
            return this == GLOBAL_ENSEMBLE ? PERSONAL_MVP : GLOBAL_ENSEMBLE;
        }

        public static PromoScope fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (PromoScope s : values()) if (s.id.equals(norm)) return s;
            }
            return GLOBAL_ENSEMBLE;
        }
    }

    /** Role &amp; faction reveal strategy (anti-spoiler control). */
    public enum IdentityReveal {
        /** Reveal all player roles &amp; faction colours from frame 0. */
        OMNISCIENT("omniscient"),
        /** Suspense fog: conceal hidden roles until the player acts violently, uses a role skill, or dies. */
        SUSPENSE_FOG("suspense_fog"),
        /** Keep roles hidden across the whole match action act (revealed only in victory/outro). */
        HIDDEN("hidden");

        public final String id;

        IdentityReveal(String id) { this.id = id; }

        public IdentityReveal next() {
            IdentityReveal[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static IdentityReveal fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (IdentityReveal r : values()) if (r.id.equals(norm)) return r;
            }
            return OMNISCIENT;
        }
    }

    /** Filter controlling which actors display trails or floating nameplates. */
    public enum ActorOverlayFilter {
        ALL("all"),
        FOCUS_AND_COMBAT("focus_combat"),
        FOCUS_ONLY("focus_only"),
        NONE("none");

        public final String id;

        ActorOverlayFilter(String id) { this.id = id; }

        public ActorOverlayFilter next() {
            ActorOverlayFilter[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public boolean matches(UUID actorId, @Nullable UUID focusPlayer, boolean involvedInCurrentBeat) {
            return switch (this) {
                case ALL -> true;
                case FOCUS_AND_COMBAT -> involvedInCurrentBeat || (focusPlayer != null && focusPlayer.equals(actorId));
                case FOCUS_ONLY -> focusPlayer != null && focusPlayer.equals(actorId);
                case NONE -> false;
            };
        }

        public static ActorOverlayFilter fromId(@Nullable String raw) {
            if (raw != null && !raw.isBlank()) {
                String norm = raw.trim().toLowerCase(Locale.ROOT);
                for (ActorOverlayFilter f : values()) if (f.id.equals(norm)) return f;
            }
            return ALL;
        }
    }

    /** Resolved inclusive match tick bounds {@code [startTick, endTick]} clamped to {@code [0, durationTicks]}. */
    public record TrimRange(int startTick, int endTick) {
        public int spanTicks() { return Math.max(0, endTick - startTick); }
        public boolean isTrimmed(int durationTicks) { return startTick > 0 || endTick < Math.max(0, durationTicks); }
    }

    public TrimRange resolveTrimRange(int durationTicks) {
        int dur = Math.max(0, durationTicks);
        int start = Math.clamp(trimStartTick, 0, dur);
        int end = trimEndTick < 0 ? dur : Math.clamp(trimEndTick, start, dur);
        return new TrimRange(start, end);
    }

    public boolean voiceEnabled() { return voiceVolume > 0.005f; }
    public boolean sfxEnabled() { return sfxVolume > 0.005f; }
    public boolean musicEnabled() { return musicLevel.enabled(); }
    public boolean showTrails() { return trailFilter != ActorOverlayFilter.NONE; }
    public boolean showNameplates() { return nameplateFilter != ActorOverlayFilter.NONE; }

    /** Whether a player's true role should be shown at {@code matchTick} given their first reveal tick. */
    public boolean isRoleRevealedAt(UUID actorId, double matchTick, int actorFirstRevealTick) {
        return switch (identityReveal) {
            case OMNISCIENT -> true;
            case HIDDEN -> focusPlayer != null && focusPlayer.equals(actorId);
            case SUSPENSE_FOG -> (focusPlayer != null && focusPlayer.equals(actorId))
                    || (actorFirstRevealTick >= 0 && matchTick >= actorFirstRevealTick);
        };
    }

    /** Optimal curated defaults per export mode. */
    public static ReplayExportContentOptions defaultsFor(ExportMode mode) {
        return defaultsFor(mode, null);
    }

    public static ReplayExportContentOptions defaultsFor(ExportMode mode, @Nullable UUID focusPlayer) {
        ExportMode m = mode == null ? ExportMode.SHORT : mode;
        return switch (m) {
            case SHORT -> new ReplayExportContentOptions(
                    ExportMode.SHORT, PersonalView.TRUE_POV, PersonalPacing.SPEED_1X, PromoScope.GLOBAL_ENSEMBLE,
                    focusPlayer, true,
                    true, true, true, true, true, true,
                    true, true, true, true, IdentityReveal.OMNISCIENT,
                    ActorOverlayFilter.ALL, ActorOverlayFilter.ALL, true, true,
                    1.0f, 1.0f, ReplayMusicPlan.Level.MEDIUM, true,
                    0, -1
            );
            case PERSONAL -> new ReplayExportContentOptions(
                    ExportMode.PERSONAL, PersonalView.TRUE_POV, PersonalPacing.SPEED_1X, PromoScope.PERSONAL_MVP,
                    focusPlayer, true,
                    true, false, true, false, false, true,
                    true, true, true, true, IdentityReveal.SUSPENSE_FOG,
                    ActorOverlayFilter.FOCUS_ONLY, ActorOverlayFilter.ALL, true, false,
                    1.0f, 1.0f, ReplayMusicPlan.Level.LOW, true,
                    0, -1
            );
            case SANDBOX_FULL -> new ReplayExportContentOptions(
                    ExportMode.SANDBOX_FULL, PersonalView.SANDBOX_FOLLOW, PersonalPacing.SPEED_1X, PromoScope.GLOBAL_ENSEMBLE,
                    focusPlayer, true,
                    true, true, true, true, false, true,
                    true, true, true, true, IdentityReveal.OMNISCIENT,
                    ActorOverlayFilter.ALL, ActorOverlayFilter.ALL, true, false,
                    1.0f, 1.0f, ReplayMusicPlan.Level.LOW, true,
                    0, -1
            );
            case CINEMATIC_PROMO -> new ReplayExportContentOptions(
                    ExportMode.CINEMATIC_PROMO, PersonalView.TRUE_POV, PersonalPacing.SPEED_1X,
                    focusPlayer != null ? PromoScope.PERSONAL_MVP : PromoScope.GLOBAL_ENSEMBLE,
                    focusPlayer, true,
                    true, true, true, true, true, true,
                    true, true, true, true, IdentityReveal.OMNISCIENT,
                    ActorOverlayFilter.FOCUS_AND_COMBAT, ActorOverlayFilter.FOCUS_AND_COMBAT, true, true,
                    1.0f, 1.0f, ReplayMusicPlan.Level.HIGH, true,
                    0, -1
            );
        };
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder(ExportMode mode) {
        return new Builder(defaultsFor(mode));
    }

    private static float clampVolume(float v) {
        if (!Float.isFinite(v)) return 1.0f;
        return Math.clamp(v, MIN_VOLUME, MAX_VOLUME);
    }

    public static final class Builder {
        private ExportMode mode;
        private PersonalView personalView;
        private PersonalPacing personalPacing;
        private PromoScope promoScope;
        private @Nullable UUID focusPlayer;
        private boolean roofCutaway;
        private boolean includeTitle;
        private boolean includeReview;
        private boolean includeVictoryMvp;
        private boolean includeOutro;
        private boolean includeLetterboxDof;
        private boolean includeWatermark;
        private boolean showTimecode;
        private boolean showAliveMatrix;
        private boolean showKillFeed;
        private boolean showTimeline;
        private IdentityReveal identityReveal;
        private ActorOverlayFilter trailFilter;
        private ActorOverlayFilter nameplateFilter;
        private boolean showSkillToasts;
        private boolean splitScreenKillPov;
        private float voiceVolume;
        private float sfxVolume;
        private ReplayMusicPlan.Level musicLevel;
        private boolean autoDucking;
        private int trimStartTick;
        private int trimEndTick;

        public Builder(ReplayExportContentOptions base) {
            this.mode = base.mode;
            this.personalView = base.personalView;
            this.personalPacing = base.personalPacing;
            this.promoScope = base.promoScope;
            this.focusPlayer = base.focusPlayer;
            this.roofCutaway = base.roofCutaway;
            this.includeTitle = base.includeTitle;
            this.includeReview = base.includeReview;
            this.includeVictoryMvp = base.includeVictoryMvp;
            this.includeOutro = base.includeOutro;
            this.includeLetterboxDof = base.includeLetterboxDof;
            this.includeWatermark = base.includeWatermark;
            this.showTimecode = base.showTimecode;
            this.showAliveMatrix = base.showAliveMatrix;
            this.showKillFeed = base.showKillFeed;
            this.showTimeline = base.showTimeline;
            this.identityReveal = base.identityReveal;
            this.trailFilter = base.trailFilter;
            this.nameplateFilter = base.nameplateFilter;
            this.showSkillToasts = base.showSkillToasts;
            this.splitScreenKillPov = base.splitScreenKillPov;
            this.voiceVolume = base.voiceVolume;
            this.sfxVolume = base.sfxVolume;
            this.musicLevel = base.musicLevel;
            this.autoDucking = base.autoDucking;
            this.trimStartTick = base.trimStartTick;
            this.trimEndTick = base.trimEndTick;
        }

        public Builder mode(ExportMode v) { this.mode = v; return this; }
        public Builder personalView(PersonalView v) { this.personalView = v; return this; }
        public Builder personalPacing(PersonalPacing v) { this.personalPacing = v; return this; }
        public Builder promoScope(PromoScope v) { this.promoScope = v; return this; }
        public Builder focusPlayer(@Nullable UUID v) { this.focusPlayer = v; return this; }
        public Builder roofCutaway(boolean v) { this.roofCutaway = v; return this; }
        public Builder includeTitle(boolean v) { this.includeTitle = v; return this; }
        public Builder includeReview(boolean v) { this.includeReview = v; return this; }
        public Builder includeVictoryMvp(boolean v) { this.includeVictoryMvp = v; return this; }
        public Builder includeOutro(boolean v) { this.includeOutro = v; return this; }
        public Builder includeLetterboxDof(boolean v) { this.includeLetterboxDof = v; return this; }
        public Builder includeWatermark(boolean v) { this.includeWatermark = v; return this; }
        public Builder showTimecode(boolean v) { this.showTimecode = v; return this; }
        public Builder showAliveMatrix(boolean v) { this.showAliveMatrix = v; return this; }
        public Builder showKillFeed(boolean v) { this.showKillFeed = v; return this; }
        public Builder showTimeline(boolean v) { this.showTimeline = v; return this; }
        public Builder identityReveal(IdentityReveal v) { this.identityReveal = v; return this; }
        public Builder trailFilter(ActorOverlayFilter v) { this.trailFilter = v; return this; }
        public Builder nameplateFilter(ActorOverlayFilter v) { this.nameplateFilter = v; return this; }
        public Builder showSkillToasts(boolean v) { this.showSkillToasts = v; return this; }
        public Builder splitScreenKillPov(boolean v) { this.splitScreenKillPov = v; return this; }
        public Builder voiceVolume(float v) { this.voiceVolume = v; return this; }
        public Builder sfxVolume(float v) { this.sfxVolume = v; return this; }
        public Builder musicLevel(ReplayMusicPlan.Level v) { this.musicLevel = v; return this; }
        public Builder autoDucking(boolean v) { this.autoDucking = v; return this; }
        public Builder trimRange(int startTick, int endTick) {
            this.trimStartTick = startTick;
            this.trimEndTick = endTick;
            return this;
        }

        public ReplayExportContentOptions build() {
            return new ReplayExportContentOptions(
                    mode, personalView, personalPacing, promoScope, focusPlayer, roofCutaway,
                    includeTitle, includeReview, includeVictoryMvp, includeOutro, includeLetterboxDof, includeWatermark,
                    showTimecode, showAliveMatrix, showKillFeed, showTimeline, identityReveal,
                    trailFilter, nameplateFilter, showSkillToasts, splitScreenKillPov,
                    voiceVolume, sfxVolume, musicLevel, autoDucking, trimStartTick, trimEndTick
            );
        }
    }
}
