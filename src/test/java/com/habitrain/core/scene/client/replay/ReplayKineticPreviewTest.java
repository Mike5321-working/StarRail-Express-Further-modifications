package com.habitrain.core.scene.client.replay;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders the kinetic (MV style) scenes with a Java2D pen. Always runs as a smoke test over every theme and act;
 * set {@code HABITRAIN_KINETIC_PREVIEW=<dir>} to also write contact sheets for visual review.
 */
class ReplayKineticPreviewTest {
    private static final int W = 1280, H = 720;

    @Test
    void everyThemeRendersEveryAct() throws Exception {
        String out = System.getenv("HABITRAIN_KINETIC_PREVIEW");
        for (ReplayKineticTheme th : ReplayKineticTheme.values()) {
            List<BufferedImage> frames = new ArrayList<>();
            for (Consumer<Java2DPen> scene : scenes(th)) {
                var pen = new Java2DPen();
                scene.accept(pen);
                frames.add(pen.img);
            }
            assertTrue(frames.size() > 10);
            if (out != null && !out.isBlank()) {
                Path dir = Path.of(out);
                Files.createDirectories(dir);
                ImageIO.write(sheet(frames), "png", dir.resolve("kinetic_" + th.name().toLowerCase() + ".png").toFile());
            }
        }
    }

    private static BufferedImage sheet(List<BufferedImage> frames) {
        int cols = 4, tw = 480, th = 270, pad = 6;
        int rows = (frames.size() + cols - 1) / cols;
        var sheet = new BufferedImage(cols * (tw + pad) + pad, rows * (th + pad) + pad, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        for (int i = 0; i < frames.size(); i++) {
            g.drawImage(frames.get(i), pad + (i % cols) * (tw + pad), pad + (i / cols) * (th + pad), tw, th, null);
        }
        g.dispose();
        return sheet;
    }

    // ------------------------------------------------------------------ sample data

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private static ReplayKineticData.Person person(UUID id, String name, String role, int color, boolean mvp, boolean win) {
        return new ReplayKineticData.Person(id, Component.literal(name), Component.literal(role), color, mvp, win);
    }

    private static List<Consumer<Java2DPen>> scenes(ReplayKineticTheme th) {
        var killer = person(A, "\u5343\u65e9\u7231\u97f3", "\u6740\u624b", 0xFFFF4A52, true, true);
        var victim = person(B, "Steve_Builder", "\u4e58\u5ba2", 0xFF7FD1FF, false, false);
        var third = person(C, "\u5c0f\u6797", "\u4fa6\u63a2", 0xFF7FD1FF, false, false);
        var title = new ReplayKineticData.Title(Component.literal("\u82f9\u679c\u5217\u8f66 \u4e8c\u53f7\u8f66\u53a2"),
                Component.literal("\u82f9\u679c\u5217\u8f66\u8c0b\u6740\u6848"), "2026.11.24", "12:34", 8, "A1B2C3D4",
                th == ReplayKineticTheme.DOSSIER ? killer : null, "FIRST PERSON", "2X", 96, 18, 12);
        var kill = new ReplayKineticData.Kill("FIRST BLOOD", Component.literal("\u672c\u5c40\u9996\u6740  \u00b7  \u5343\u65e9\u7231\u97f3"),
                killer, victim, Component.literal("\u4e0b\u5c4a\u5200"), th.red, 0, 1, true);
        var hud = new ReplayKineticData.Hud("03:21", "\u00d72.0", 0, 6, 8,
                new int[]{0xFFFF4A52, 0xFF7FD1FF, 0xFF7FD1FF, 0xFF7FD1FF, 0xFF7FD1FF, 0xFF7FD1FF, 0xFF7FD1FF, 0xFF7FD1FF},
                new boolean[]{true, false, true, true, false, true, true, true}, 0.42, new double[]{0.2, 0.42, 0.7},
                new int[]{th.red, th.red, th.blue}, Component.literal("\u82f9\u679c\u5217\u8f66"), "12:34", killer);
        var feed = List.of(new ReplayKineticData.FeedRow(Component.literal("\u5343\u65e9\u7231\u97f3 \u7528 \u4e0b\u5c4a\u5200 \u51fb\u6740\u4e86 Steve_Builder"), th.red, 0.5),
                new ReplayKineticData.FeedRow(Component.literal("\u5c0f\u6797 \u6253\u5f00\u4e86\u95e8\u9501"), th.blue, 1.4));
        var chrome = new ReplayKineticData.Chrome(th.code + ".02", "ACTION", "03:21  //  120 BPM", "\u82f9\u679c\u5217\u8f66", 0.42, th.red);
        var stats = List.of(new ReplayKineticData.Stat(Component.literal("\u5bf9\u5c40\u65f6\u957f"), "12:34", th.ink),
                new ReplayKineticData.Stat(Component.literal("\u603b\u51fb\u6740"), "5", th.red),
                new ReplayKineticData.Stat(Component.literal("\u53c2\u8d5b\u4e58\u5ba2"), "8", th.blue),
                new ReplayKineticData.Stat(Component.literal("\u9ad8\u5149\u65f6\u523b"), "7", th.ink));
        var victory = new ReplayKineticData.Victory(Component.literal("\u6740\u624b\u9635\u8425\u83b7\u80dc"), Component.literal("\u6240\u6709\u4e58\u5ba2\u5747\u5df2\u906d\u9047\u4e0d\u6d4b"),
                0xFFFF4A52, List.of(killer, third), th == ReplayKineticTheme.DOSSIER ? victim : null,
                th == ReplayKineticTheme.DOSSIER ? Boolean.FALSE : null);
        var mvp = new ReplayKineticData.Mvp(killer, stats, new double[]{1, 0.8, 0.6, 0.3});
        List<ReplayKineticData.Row> rows = new ArrayList<>();
        for (int i = 0; i < 8; i++) rows.add(new ReplayKineticData.Row(i + 1, i % 3 == 0 ? killer : i % 3 == 1 ? victim : third, 3 - i / 3, 120 - i * 9, "08:1" + i, i == 2));
        var outro = new ReplayKineticData.Outro(rows, 0, 1, 1, 0, true, Component.literal("\u9996\u6740\uff1a\u5343\u65e9\u7231\u97f3 \u2192 Steve_Builder"),
                Component.literal("\u54c8\u6bd4\u5217\u8f66 \u00b7 \u5bf9\u5c40\u56de\u653e\u751f\u6210\u4e8e 2026.11.24"), Component.literal("\u4e58\u5ba2\u540d\u5355"));

        List<Consumer<Java2DPen>> list = new ArrayList<>();
        for (double k : new double[]{0.3, 0.75, 1.3, 1.9, 2.5, 3.15}) {
            list.add(p -> {
                ReplayKineticBackdrops.action(p, th, k);
                fakeBoard(p);
                ReplayKineticTitles.title(p, th, k, title);
                ReplayKineticBackdrops.chrome(p, th, chrome, ReplayKineticTitles.covers(k), 0.85);
            });
        }
        for (double u : new double[]{0.03, 0.5, 1.4}) {
            list.add(p -> {
                ReplayKineticBackdrops.action(p, th, 10);
                fakeBoard(p);
                if (th == ReplayKineticTheme.DOSSIER) ReplayKineticAction.viewfinder(p, th, 10, 201, 2, th.red, 1);
                if (th == ReplayKineticTheme.DOSSIER) ReplayKineticAction.orbitMarker(p, th, 700, 420, killer.name(), th.red, 10, 1);
                ReplayKineticAction.hud(p, th, hud, 1, 10, true, true, true);
                ReplayKineticAction.feed(p, th, feed, 1);
                ReplayKineticAction.mark(p, th, 560, 400, "Steve_Builder", th.red, u, true, 0, W - 24);
                ReplayKineticAction.headline(p, th, kill, u);
                ReplayKineticAction.impact(p, th, u, 0.85);
                ReplayKineticBackdrops.chrome(p, th, chrome, false, 0.85);
            });
        }
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 10);
            fakeBoard(p);
            ReplayKineticAction.splitSeam(p, th, 700, 650, killer.name(), 1);
            if (th == ReplayKineticTheme.MUSIC_VIDEO) ReplayKineticAction.heroLyric(p, th, "\u51fb\u6740 \u00b7 Steve_Builder", "FIRST BLOOD", th.red, 0.9, 1);
            else ReplayKineticAction.lyricLine(p, th, Component.literal("\u5343\u65e9\u7231\u97f3 \u7528 \u4e0b\u5c4a\u5200 \u51fb\u6740\u4e86 Steve"), th.red, 0.5, 1);
        });
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 10);
            fakeBoard(p);
            ReplayKineticAction.apex(p, th, "MULTI KILL", 0.6, 3.3, 1);
        });
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 10);
            fakeBoard(p);
            ReplayKineticBackdrops.chapterCard(p, th, 0.5, 1, 5, "DOUBLE KILL", "\u5343\u65e9\u7231\u97f3 \u53cc\u6740");
        });
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 10);
            fakeBoard(p);
            ReplayKineticBackdrops.polarityWipe(p, th, 0.42, "03", "OVERVIEW");
        });
        list.add(p -> {
            ReplayKineticBackdrops.paper(p, th, 2, "03");
            fakeBoard(p);
            ReplayKineticFinale.review(p, th, 1.6, 4, stats, "\u5168\u5c40\u590d\u76d8 \u00b7 \u5168\u5458\u8f68\u8ff9");
            ReplayKineticBackdrops.chrome(p, th, chrome, true, 0.85);
        });
        for (double k : new double[]{0.2, 0.6, 1.0, 2.6}) {
            list.add(p -> {
                ReplayKineticBackdrops.paper(p, th, k, "04");
                fakeBoard(p);
                ReplayKineticFinale.victory(p, th, k, 4, victory, k);
            });
        }
        list.add(p -> {
            ReplayKineticBackdrops.paper(p, th, 2, "05");
            fakeBoard(p);
            ReplayKineticFinale.mvp(p, th, 2.2, 6, mvp, 2.2);
        });
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 2);
            fakeBoard(p);
            ReplayKineticFinale.outro(p, th, 2.4, 6.4, outro, 2.4);
        });
        list.add(p -> {
            ReplayKineticBackdrops.action(p, th, 2);
            ReplayKineticFinale.outro(p, th, 6.0, 6.4, outro, 6.0);
        });
        return list;
    }

    /** Stand-in for the 3D diorama so overlay readability can be judged. */
    private static void fakeBoard(ReplayKineticPen p) {
        p.quad(380, 300, 0xFF6B5A44, 260, 520, 0xFF4A3D2E, 1020, 520, 0xFF4A3D2E, 900, 300, 0xFF6B5A44);
        p.quad(420, 330, 0xFF9C8B70, 330, 480, 0xFF7E6E57, 950, 480, 0xFF7E6E57, 860, 330, 0xFF9C8B70);
        for (int i = 0; i < 6; i++) {
            float x = 450 + i * 70;
            p.quad(x, 360, 0xFF3355AA, x, 400, 0xFF223377, x + 14, 400, 0xFF223377, x + 14, 360, 0xFF3355AA);
        }
    }

    // ------------------------------------------------------------------ Java2D pen

    static final class Java2DPen implements ReplayKineticPen {
        private static Font bebas, noto, oswald;
        final BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        private final Graphics2D g = img.createGraphics();
        private final Deque<AffineTransform> stack = new ArrayDeque<>();

        Java2DPen() {
            loadFonts();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, W, H);
        }

        private static synchronized void loadFonts() {
            if (bebas != null) return;
            bebas = font("bebas.ttf");
            noto = font("noto_sans_sc_bold.ttf");
            oswald = font("oswald.ttf");
        }

        private static Font font(String name) {
            try (InputStream in = Java2DPen.class.getResourceAsStream("/assets/habitrain_core/font/replay/" + name)) {
                return Font.createFont(Font.TRUETYPE_FONT, in);
            } catch (Exception e) {
                return new Font(Font.SANS_SERIF, Font.BOLD, 12);
            }
        }

        private static Color col(int argb) { return new Color(argb, true); }

        @Override public float width() { return W; }

        @Override public float height() { return H; }

        @Override
        public void quad(float x0, float y0, int c0, float x1, float y1, int c1, float x2, float y2, int c2, float x3, float y3, int c3) {
            if (((c0 | c1 | c2 | c3) >>> 24) == 0) return;
            Path2D.Float path = new Path2D.Float();
            path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.closePath();
            if (c0 == c1 && c1 == c2 && c2 == c3) g.setPaint(col(c0));
            else if (c0 == c3 && c1 == c2) g.setPaint(new GradientPaint((x0 + x3) / 2, (y0 + y3) / 2, col(c0), (x1 + x2) / 2, (y1 + y2) / 2, col(c1)));
            else g.setPaint(new GradientPaint((x0 + x1) / 2, (y0 + y1) / 2, col(c0), (x2 + x3) / 2, (y2 + y3) / 2, col(c3)));
            g.fill(path);
        }

        @Override public void polyline(float[] xy, int n, float lineWidth, int color, boolean closed) {
            if ((color >>> 24) == 0 || n < 2) return;
            Path2D.Float path = new Path2D.Float();
            path.moveTo(xy[0], xy[1]);
            for (int i = 1; i < n; i++) path.lineTo(xy[i * 2], xy[i * 2 + 1]);
            if (closed) path.closePath();
            g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g.setPaint(col(color));
            g.draw(path);
        }

        private record Run(String text, Font font) {}

        private List<Run> runs(String s, Face face, float px) {
            List<Run> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            Font curFont = null;
            for (int cp : s.codePoints().toArray()) {
                boolean ascii = cp >= 0x20 && cp < 0x7F || cp == 0xD7;
                Font f = switch (face) {
                    case DISPLAY -> ascii ? bebas.deriveFont(px * 11f / 9f) : noto.deriveFont(px);
                    case TEXT -> noto.deriveFont(px);
                    case MONO -> ascii ? oswald.deriveFont(px) : noto.deriveFont(px * 8f / 9f);
                };
                if (curFont != null && !f.equals(curFont)) { out.add(new Run(cur.toString(), curFont)); cur.setLength(0); }
                curFont = f;
                cur.appendCodePoint(cp);
            }
            if (curFont != null) out.add(new Run(cur.toString(), curFont));
            return out;
        }

        @Override public void text(Component text, Face face, float x, float y, float px, int color, int align) {
            if ((color >>> 24) < 6 || px <= 0) return;
            String s = text.getString();
            float wv = textWidth(text, face, px);
            float cx = align < 0 ? x : align == 0 ? x - wv / 2 : x - wv;
            g.setPaint(col(color));
            for (Run r : runs(s, face, px)) {
                g.setFont(r.font());
                FontMetrics fm = g.getFontMetrics();
                float base = y + (fm.getAscent() - fm.getDescent()) * 0.42f;
                g.drawString(r.text(), cx, base);
                cx += fm.stringWidth(r.text());
            }
        }

        @Override public float textWidth(Component text, Face face, float px) {
            float wv = 0;
            for (Run r : runs(text.getString(), face, px)) wv += g.getFontMetrics(r.font()).stringWidth(r.text());
            return wv;
        }

        @Override public void push() { stack.push(g.getTransform()); }

        @Override public void pop() { g.setTransform(stack.pop()); }

        @Override public void translate(float x, float y) { g.translate(x, y); }

        @Override public void rotate(float radians) { g.rotate(radians); }

        @Override public void scale(float sx, float sy) { g.scale(sx, sy); }

        @Override public void face(UUID player, float x, float y, float size, int tint) {
            int h = player.hashCode();
            for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) {
                int c = 0xFF000000 | (((h >> (i * 3 + j)) & 1) == 1 ? 0xC69C6D : 0x7A4E2D);
                if (j == 1 && (i == 1 || i == 2)) c = 0xFF2B2B5A;
                g.setPaint(col((c & 0xFFFFFF) | (tint & 0xFF000000)));
                g.fill(new Rectangle2D.Float(x + i * size / 4, y + j * size / 4, size / 4 + 0.5f, size / 4 + 0.5f));
            }
        }

        @Override public void invert(float x0, float y0, float x1, float y1, double amount) {
            AffineTransform t = g.getTransform();
            Point2D a = t.transform(new Point2D.Float(x0, y0), null), b = t.transform(new Point2D.Float(x1, y1), null);
            int ix0 = (int) Math.max(0, Math.min(a.getX(), b.getX())), ix1 = (int) Math.min(W, Math.max(a.getX(), b.getX()));
            int iy0 = (int) Math.max(0, Math.min(a.getY(), b.getY())), iy1 = (int) Math.min(H, Math.max(a.getY(), b.getY()));
            double k = Math.clamp(amount, 0, 1);
            for (int yy = iy0; yy < iy1; yy++) for (int xx = ix0; xx < ix1; xx++) {
                int c = img.getRGB(xx, yy);
                int r = (c >> 16) & 0xFF, gg = (c >> 8) & 0xFF, bb = c & 0xFF;
                r = (int) (r + (255 - 2 * r) * k); gg = (int) (gg + (255 - 2 * gg) * k); bb = (int) (bb + (255 - 2 * bb) * k);
                img.setRGB(xx, yy, 0xFF000000 | (r << 16) | (gg << 8) | bb);
            }
        }

        @Override public void glow(float cx, float cy, float w, float h, int color) {
            float r = Math.max(w, h) / 2;
            if (r <= 1) return;
            var saved = g.getTransform();
            g.translate(cx, cy);
            g.scale(w / (2 * r), h / (2 * r));
            g.setPaint(new RadialGradientPaint(0, 0, r, new float[]{0f, 1f}, new Color[]{col(color), col(color & 0xFFFFFF)}));
            g.fill(new Rectangle2D.Float(-r, -r, 2 * r, 2 * r));
            g.setTransform(saved);
        }

        @Override public void vignette(double alpha) {
            int a = (int) (Math.clamp(alpha, 0, 1) * 200);
            g.setPaint(new RadialGradientPaint(W / 2f, H / 2f, W * 0.75f, new float[]{0.55f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, a)}));
            g.fill(new Rectangle2D.Float(0, 0, W, H));
        }

        @Override public void grain(double alpha, int frame) {
            g.setComposite(AlphaComposite.SrcOver);
        }
    }
}
