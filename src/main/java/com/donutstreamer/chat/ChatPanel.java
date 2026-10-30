package com.donutstreamer.chat;

import com.donutstreamer.Look;
import com.donutstreamer.Ui;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-screen Twitch / YouTube chat (a normal HUD widget: draggable, resizable, toggleable).
 * <p>
 * Messages are word-wrapped, so you always see the whole message (up to 500 characters) instead of a cut-off line.
 * Everything about the look comes from /ds -> Chat: width, number of rows, fade time, spacing, platform bars,
 * timestamps, bold names, viewer colours, shading, header, alignment, hiding when idle, hiding bots.
 * Wrapping is done once per message (and again only when the width or an option changes) and cached.
 */
public final class ChatPanel {
    private ChatPanel() {}

    public static final int ACCENT = 0xFF9146FF;

    private static final int LABEL = 0xFF8B949E;
    private static final int TEXT = 0xFFE6EDF3;
    private static final int TWITCH = 0xFF9146FF;
    private static final int YOUTUBE = 0xFFFF3B3B;
    private static final int PAD = 6;
    private static final int HEADER_H = 14;

    private static final ChatMessage[] VIEW = new ChatMessage[200];   // bigger than the ring buffer in StreamChat
    private static String hint = "";
    private static String hintFor = null;

    // ---------------- size (read by Widgets) ----------------

    public static int width() {
        return Look.d.chatWidth;
    }

    public static int width(TextRenderer tr) {
        return Look.d.chatWidth;
    }

    private static int rowH() {
        return 9 + Look.d.chatSpacing;
    }

    public static int height() {
        Look.Data d = Look.d;
        return (d.chatHeader ? HEADER_H + 3 : 4) + d.chatLines * rowH() + 4;
    }

    /** Whether the HUD should draw the panel at all right now. */
    public static boolean visible() {
        if (!StreamChat.configured()) return false;
        Look.Data d = Look.d;
        if (!d.chatHideIdle) return true;
        long last = StreamChat.lastMessageTime();
        if (last == 0L) return false;
        long hold = d.chatFade > 0 ? d.chatFade * 1000L : 60_000L;
        return System.currentTimeMillis() - last < hold;
    }

    // ---------------- layout ----------------

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth("..."))) + "...";
    }

    private static String clock(long ms) {
        java.time.LocalTime t = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault());
        return String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Greedy word wrap. The first line may be narrower (it starts after the name). Very long words are broken. */
    private static List<String> wrap(TextRenderer tr, String text, int firstW, int nextW) {
        List<String> out = new ArrayList<>();
        String cur = "";
        int avail = Math.max(10, firstW);
        nextW = Math.max(10, nextW);
        for (String word : text.split(" +")) {
            if (word.isEmpty()) continue;
            String cand = cur.isEmpty() ? word : cur + " " + word;
            if (tr.getWidth(cand) <= avail) {
                cur = cand;
                continue;
            }
            if (!cur.isEmpty()) {
                out.add(cur);
                cur = "";
                avail = nextW;
            }
            while (tr.getWidth(word) > avail) {
                String part = tr.trimToWidth(word, avail);
                if (part.isEmpty()) part = word.substring(0, 1);
                out.add(part);
                word = word.substring(part.length());
                avail = nextW;
            }
            cur = word;
        }
        if (!cur.isEmpty() || out.isEmpty()) out.add(cur);
        return out;
    }

    private static void layout(TextRenderer tr, ChatMessage m, int w) {
        Look.Data d = Look.d;
        long key = (long) w * 1_000_003L + Look.rev;
        if (m.laidKey == key) return;
        m.laidKey = key;

        int textX0 = PAD + (d.chatBadge ? 5 : 0);
        int innerW = Math.max(40, w - textX0 - PAD);

        m.nameShown = fit(tr, m.name, Math.min(110, innerW * 2 / 5));
        m.nameText = d.chatBold ? Text.literal(m.nameShown).formatted(Formatting.BOLD) : null;
        m.nameWidth = m.nameText != null ? tr.getWidth(m.nameText) : tr.getWidth(m.nameShown);
        m.colonWidth = tr.getWidth(": ");
        if (d.chatTime) {
            m.timeText = clock(m.time) + " ";
            m.timeWidth = tr.getWidth(m.timeText);
        } else {
            m.timeText = "";
            m.timeWidth = 0;
        }

        int firstW = innerW - m.timeWidth - m.nameWidth - m.colonWidth;
        List<String> lines;
        if (!d.chatWrap) {
            lines = List.of(fit(tr, m.text, Math.max(10, firstW)));
        } else if (firstW < 40) {
            // name is huge: put the text on its own rows under it
            lines = new ArrayList<>();
            lines.add("");
            lines.addAll(wrap(tr, m.text, innerW, innerW));
        } else {
            lines = wrap(tr, m.text, firstW, innerW);
        }
        m.lines = lines;
    }

    private static int alphaFor(long ageMs, int fadeSec) {
        if (fadeSec <= 0) return 255;
        long fadeMs = fadeSec * 1000L;
        long start = fadeMs * 7 / 10;
        if (ageMs <= start) return 255;
        if (ageMs >= fadeMs) return 0;
        return 255 - (int) ((ageMs - start) * 255L / (fadeMs - start));
    }

    // ---------------- drawing ----------------

    private static void drawHeader(DrawContext ctx, TextRenderer tr, int w) {
        ctx.fill(2, 1, w - 2, HEADER_H - 1, 0x12FFFFFF);
        int mx = 8;
        if (StreamChat.twitchOn()) {
            ctx.fill(mx, 5, mx + 4, 9, TWITCH);
            mx += 6;
        }
        if (StreamChat.youtubeOn()) {
            ctx.fill(mx, 5, mx + 4, 9, YOUTUBE);
            mx += 6;
        }
        if (mx == 8) {
            ctx.fill(mx, 5, mx + 4, 9, 0x55FFFFFF);
            mx += 6;
        }
        ctx.drawTextWithShadow(tr, "STREAM CHAT", mx + 2, 3, 0xFFB794F6);
        ctx.fill(6, HEADER_H - 1, w - 6, HEADER_H, 0x18FFFFFF);
    }

    /** Draws inside the widget box (origin = top-left; the card background is already drawn). */
    public static void draw(DrawContext ctx, TextRenderer tr, int w, int h) {
        Look.Data d = Look.d;
        long now = System.currentTimeMillis();
        int rowH = rowH();

        int top = 3;
        if (d.chatHeader) {
            drawHeader(ctx, tr, w);
            top = HEADER_H + 3;
        }
        int area = d.chatLines * rowH;

        int n = StreamChat.newest(VIEW);
        if (n == 0) {
            String status = StreamChat.configured() ? StreamChat.statusLine() : "Set up: /ds chat twitch <channel>";
            if (!status.equals(hintFor)) {
                hintFor = status;
                hint = fit(tr, status, w - 16);
            }
            ctx.drawTextWithShadow(tr, hint, 8, top + 3, LABEL);
            if (!StreamChat.configured()) {
                ctx.drawTextWithShadow(tr, "or open /ds -> Chat", 8, top + 15, LABEL);
            }
            return;
        }

        // pick the newest messages that fit in the area (older ones scroll off the top)
        int first = n;
        int used = 0;
        int clipRows = -1;
        for (int i = n - 1; i >= 0; i--) {
            ChatMessage m = VIEW[i];
            if (d.chatFade > 0 && now - m.time >= d.chatFade * 1000L) break;
            layout(tr, m, w);
            int mh = m.lines.size() * rowH;
            int need = used + (used > 0 ? d.chatGap : 0) + mh;
            if (need > area) {
                if (first == n) {   // one huge message: show its first rows
                    first = i;
                    used = area;
                    clipRows = area / rowH;
                }
                break;
            }
            used = need;
            first = i;
        }

        if (first < n) {
            int y = d.chatBottom ? top + (area - used) : top;
            int textX0 = PAD + (d.chatBadge ? 5 : 0);
            for (int i = first; i < n; i++) {
                ChatMessage m = VIEW[i];
                int rows = m.lines.size();
                if (i == first && clipRows >= 0) rows = Math.min(rows, clipRows);
                int mh = rows * rowH;
                int alpha = alphaFor(now - m.time, d.chatFade);
                if (alpha >= 12) {
                    int platform = m.platform == ChatMessage.YOUTUBE ? YOUTUBE : TWITCH;
                    if (d.chatStripes && ((n - 1 - i) & 1) == 0) {
                        ctx.fill(3, y - 1, w - 3, y + mh - d.chatSpacing, Ui.withAlpha(0xFFFFFF, Math.max(4, 0x12 * alpha / 255)));
                    }
                    if (d.chatBadge) {
                        ctx.fill(PAD, y, PAD + 2, y + mh - d.chatSpacing, Ui.withAlpha(platform, alpha));
                    }
                    int x = textX0;
                    if (!m.timeText.isEmpty()) {
                        ctx.drawTextWithShadow(tr, m.timeText, x, y, Ui.withAlpha(LABEL, alpha));
                        x += m.timeWidth;
                    }
                    int nameColor = d.chatViewerColors ? m.color : platform;
                    if (m.nameText != null) {
                        ctx.drawTextWithShadow(tr, m.nameText, x, y, Ui.withAlpha(nameColor, alpha));
                    } else {
                        ctx.drawTextWithShadow(tr, m.nameShown, x, y, Ui.withAlpha(nameColor, alpha));
                    }
                    ctx.drawTextWithShadow(tr, ": ", x + m.nameWidth, y, Ui.withAlpha(LABEL, alpha));
                    int firstX = x + m.nameWidth + m.colonWidth;
                    int textColor = Ui.withAlpha(TEXT, alpha);
                    for (int r = 0; r < rows; r++) {
                        String line = m.lines.get(r);
                        if (!line.isEmpty()) {
                            ctx.drawTextWithShadow(tr, line, r == 0 ? firstX : textX0, y + r * rowH, textColor);
                        }
                    }
                }
                y += mh + d.chatGap;
            }
        }
        for (int i = 0; i < n; i++) VIEW[i] = null;
    }
}
