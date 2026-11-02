package com.rent.pc;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 账单表格图片 + 分享文本（完全复刻安卓 v2.9 的版式与规则）
 *  - 标题、合计文字按实际存在的项目动态拼接
 *  - 没有其他收费 → 不画合计条
 *  - 只有一列 → 不画合计条
 */
public final class BillImage {

    private static final int W = 1080;
    private static final int PAD = 36;
    private static final int TITLE_H = 110;
    private static final int META_H = 108;
    private static final int HEADER_H = 96;
    private static final int ROW_H = 88;
    private static final int LABEL_W = 170;

    private static final Color C_BLACK = new Color(0x22, 0x22, 0x22);
    private static final Color C_GRAY = new Color(0x99, 0x99, 0x99);
    private static final Color C_LINE = new Color(0xBB, 0xBB, 0xBB);
    private static final Color C_HEAD_BG = new Color(0xE8, 0xF1, 0xEC);
    private static final Color C_LABEL_BG = new Color(0xF7, 0xF9, 0xFC);
    private static final Color C_TOTAL_BG = new Color(0xFE, 0xF3, 0xE2);
    private static final Color C_PRIMARY = new Color(0x2F, 0x6B, 0x4F);
    private static final Color C_ACCENT = new Color(0xE0, 0x6A, 0x1F);

    private static final String[] FONT_CANDIDATES = {
            "Microsoft YaHei", "微软雅黑", "PingFang SC", "Hiragino Sans GB",
            "Source Han Sans SC", "Noto Sans CJK SC", "WenQuanYi Micro Hei", "SimHei", "SansSerif"
    };

    private BillImage() { }

    public static Font font(int style, int size) {
        for (String name : FONT_CANDIDATES) {
            Font f = new Font(name, style, size);
            if (f.canDisplay('水') && f.canDisplay('账')) return f;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    // ==================== 文本 ====================

    public static String buildText(Model.Bill b, Model.Settings s) {
        List<Model.Col> cols = Model.buildColumns(b, s);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(Model.tableTitle(cols)).append("】\n");

        String room = b.room == null ? "" : b.room.trim();
        if (!room.isEmpty()) sb.append("房间：").append(room);
        if (b.tenant != null && !b.tenant.trim().isEmpty()) {
            if (!room.isEmpty()) sb.append("　");
            sb.append("租客：").append(b.tenant.trim());
        }
        sb.append("\n月份：").append(b.month == null ? "" : b.month).append("\n");
        sb.append("--------------------------------\n");

        StringBuilder head = new StringBuilder("项目");
        StringBuilder rStart = new StringBuilder("起码");
        StringBuilder rEnd = new StringBuilder("止码");
        StringBuilder rUsage = new StringBuilder("用量");
        StringBuilder rFee = new StringBuilder("费用");
        for (Model.Col c : cols) {
            head.append("｜").append(c.title).append(c.sub.isEmpty() ? "" : "(" + c.sub + ")");
            rStart.append("｜").append(c.start == null ? "—" : Model.usage(c.start));
            rEnd.append("｜").append(c.end == null ? "—" : Model.usage(c.end));
            rUsage.append("｜").append(Model.usage(c.usage)).append(c.unit);
            rFee.append("｜").append(Model.money(c.fee)).append("元");
        }
        sb.append(head).append("\n").append(rStart).append("\n").append(rEnd).append("\n")
                .append(rUsage).append("\n").append(rFee).append("\n");

        double subtotal = Model.subtotalOf(cols);
        List<Model.Extra> fixed = Model.fixedExtras(b);
        boolean showSubtotal = Model.shouldShowSubtotal(cols, fixed);

        if (showSubtotal) {
            sb.append("--------------------------------\n");
            sb.append(Model.subtotalLabel(cols)).append("：").append(Model.money(subtotal)).append(" 元\n");
        }
        if (!fixed.isEmpty()) {
            if (!showSubtotal) sb.append("--------------------------------\n");
            sb.append("其他费用：\n");
            for (Model.Extra e : fixed) {
                sb.append("　").append(e.name).append("：").append(Model.money(e.amount)).append("元\n");
            }
        }
        sb.append("--------------------------------\n");
        sb.append("总费用：").append(Model.money(b.total())).append(" 元\n");
        if (b.note != null && !b.note.trim().isEmpty()) {
            sb.append("备注：").append(b.note.trim()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 图片 ====================

    public static BufferedImage build(Model.Bill b, Model.Settings s) {
        List<Model.Col> cols = Model.buildColumns(b, s);
        int n = Math.max(cols.size(), 1);

        String room = b.room == null ? "" : b.room.trim();
        String tenant = (b.tenant == null || b.tenant.trim().isEmpty()) ? "" : "　租客：" + b.tenant.trim();
        String note = (b.note == null || b.note.trim().isEmpty()) ? null : b.note.trim();
        String month = b.month == null ? "" : b.month;

        int tableW = W - PAD * 2;
        int metricW = Math.max((tableW - LABEL_W) / n, 40);

        List<Model.Extra> fixed = Model.fixedExtras(b);
        boolean showSubtotal = Model.shouldShowSubtotal(cols, fixed);
        String subLabel = Model.subtotalLabel(cols);
        double subtotal = Model.subtotalOf(cols);

        int subtotalH = showSubtotal ? 78 : 0;
        int fixedH = fixed.isEmpty() ? 0 : 56 + fixed.size() * 60;
        int totalH = 96;
        int noteH = note != null ? 80 : 16;

        int tableTop = TITLE_H + META_H;
        int tableH = HEADER_H + 4 * ROW_H;
        int subtotalTop = tableTop + tableH + 16;
        int fixedTop = subtotalTop + subtotalH + ((fixed.isEmpty() || !showSubtotal) ? 0 : 16);
        int totalTop = fixedTop + fixedH + 8;
        int H = totalTop + totalH + noteH + 22;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);

        // 标题
        g.setColor(C_BLACK);
        g.setFont(font(Font.BOLD, 46));
        drawCentered(g, Model.tableTitle(cols), W / 2, TITLE_H / 2);

        // 元信息
        g.setColor(C_BLACK);
        g.setFont(font(Font.PLAIN, 32));
        String meta1 = room.isEmpty() ? tenant.trim() : "房间：" + room + tenant;
        drawLeft(g, meta1, PAD, TITLE_H + 42);
        g.setColor(C_GRAY);
        drawLeft(g, "月份：" + month, PAD, TITLE_H + 82);
        g.setColor(C_BLACK);

        // 表格底色
        g.setColor(C_HEAD_BG);
        g.fillRect(PAD, tableTop, tableW, HEADER_H);
        g.setColor(C_LABEL_BG);
        g.fillRect(PAD, tableTop, LABEL_W, tableH);

        // 表头
        float headerCenterY = tableTop + HEADER_H / 2f;
        g.setFont(font(Font.BOLD, 34));
        g.setColor(C_BLACK);
        drawCentered(g, "项目", PAD + LABEL_W / 2, headerCenterY - 12);
        g.setFont(font(Font.PLAIN, 22));
        g.setColor(C_GRAY);
        drawCentered(g, "单位/单价", PAD + LABEL_W / 2, headerCenterY + 22);

        for (int i = 0; i < n; i++) {
            Model.Col col = cols.get(i);
            float cx = PAD + LABEL_W + i * metricW + metricW / 2f;
            g.setFont(font(Font.BOLD, 32));
            g.setColor(C_BLACK);
            drawCentered(g, col.title, cx, headerCenterY - 14);
            g.setFont(font(Font.PLAIN, 22));
            g.setColor(C_PRIMARY);
            drawCentered(g, col.sub.isEmpty() ? "—" : col.sub, cx, headerCenterY + 22);
        }

        // 四行数据
        String[] labels = {"起码", "止码", "用量", "费用"};
        for (int r = 0; r < 4; r++) {
            float cy = tableTop + HEADER_H + r * ROW_H + ROW_H / 2f;
            g.setFont(font(Font.BOLD, 30));
            g.setColor(C_BLACK);
            drawCentered(g, labels[r], PAD + LABEL_W / 2, cy + 10);
            for (int i = 0; i < n; i++) {
                Model.Col col = cols.get(i);
                float cx = PAD + LABEL_W + i * metricW + metricW / 2f;
                String txt;
                if (r == 0) txt = col.start == null ? "—" : Model.usage(col.start);
                else if (r == 1) txt = col.end == null ? "—" : Model.usage(col.end);
                else if (r == 2) txt = Model.usage(col.usage) + col.unit;
                else txt = Model.money(col.fee) + "元";

                if (r == 3) {
                    g.setFont(font(Font.BOLD, 30));
                    g.setColor(C_PRIMARY);
                } else {
                    g.setFont(font(Font.PLAIN, 30));
                    g.setColor(C_BLACK);
                }
                drawCentered(g, txt, cx, cy + 10);
            }
        }

        // 网格线
        g.setColor(C_LINE);
        g.setStroke(new BasicStroke(2));
        g.drawRect(PAD, tableTop, tableW, tableH);
        for (int r = 0; r <= 4; r++) {
            int y = tableTop + HEADER_H + r * ROW_H;
            g.drawLine(PAD, y, PAD + tableW, y);
        }
        for (int i = 0; i <= n; i++) {
            int x = PAD + LABEL_W + i * metricW;
            g.drawLine(x, tableTop, x, tableTop + tableH);
        }

        // 合计条
        if (showSubtotal) {
            g.setColor(C_HEAD_BG);
            g.fillRect(PAD, subtotalTop, tableW, subtotalH);
            g.setColor(C_PRIMARY);
            g.setStroke(new BasicStroke(2));
            g.drawRect(PAD, subtotalTop, tableW, subtotalH);
            g.setColor(C_PRIMARY);
            g.setFont(font(Font.BOLD, 32));
            drawLeft(g, subLabel, PAD + 28, subtotalTop + subtotalH / 2f + 11);
            g.setFont(font(Font.BOLD, 40));
            drawRight(g, Model.money(subtotal) + " 元", PAD + tableW - 28, subtotalTop + subtotalH / 2f + 14);
        }

        // 其他费用
        if (!fixed.isEmpty()) {
            g.setColor(C_BLACK);
            g.setFont(font(Font.PLAIN, 30));
            drawLeft(g, "其他费用", PAD, fixedTop + 34);
            for (int i = 0; i < fixed.size(); i++) {
                Model.Extra e = fixed.get(i);
                float y = fixedTop + 56 + i * 60 + 34;
                g.setColor(C_GRAY);
                drawLeft(g, "· " + e.name, PAD + 18, y);
                g.setColor(C_BLACK);
                drawRight(g, Model.money(e.amount) + " 元", PAD + tableW - 18, y);
            }
        }

        // 总费用条
        g.setColor(C_TOTAL_BG);
        g.fillRect(PAD, totalTop, tableW, totalH);
        g.setColor(C_ACCENT);
        g.setStroke(new BasicStroke(2));
        g.drawRect(PAD, totalTop, tableW, totalH);
        g.setColor(C_ACCENT);
        g.setFont(font(Font.BOLD, 36));
        drawLeft(g, "总费用", PAD + 28, totalTop + totalH / 2f + 13);
        g.setFont(font(Font.BOLD, 44));
        drawRight(g, Model.money(b.total()) + " 元", PAD + tableW - 28, totalTop + totalH / 2f + 15);

        // 备注
        if (note != null) {
            g.setColor(C_GRAY);
            g.setFont(font(Font.PLAIN, 26));
            drawLeft(g, "备注：" + note, PAD, totalTop + totalH + 48);
        }

        g.dispose();
        return img;
    }

    public static File save(Model.Bill b, Model.Settings s, File dir) throws IOException {
        if (!dir.exists()) dir.mkdirs();
        String name = "账单_" + (b.month == null || b.month.isEmpty() ? "" : b.month + "_")
                + (b.room == null || b.room.isEmpty() ? "" : b.room + "_")
                + System.currentTimeMillis() + ".png";
        File f = new File(dir, name);
        ImageIO.write(build(b, s), "png", f);
        return f;
    }

    // ---------------- 绘制助手 ----------------

    private static void drawCentered(Graphics2D g, String text, float cx, float cy) {
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, cx - w / 2f, cy + baselineOffset(g, cy));
    }

    private static void drawLeft(Graphics2D g, String text, float x, float cy) {
        g.drawString(text, x, cy + baselineOffset(g, cy));
    }

    private static void drawRight(Graphics2D g, String text, float x, float cy) {
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, x - w, cy + baselineOffset(g, cy));
    }

    /** 让文字在 cy 附近垂直居中（对应安卓的 descent 补偿） */
    private static float baselineOffset(Graphics2D g, float cy) {
        FontMetrics fm = g.getFontMetrics();
        return (fm.getAscent() - fm.getDescent()) / 2f - 6;
    }

    /** 供 UI 预览用的等比缩小图 */
    public static BufferedImage scaled(BufferedImage src, int maxW) {
        int w = Math.min(maxW, src.getWidth());
        int h = (int) (src.getHeight() * (w * 1.0 / src.getWidth()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
