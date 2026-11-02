package com.rent.utility.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.rent.utility.data.Bill;
import com.rent.utility.data.DbHelper;
import com.rent.utility.data.FeeItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成账单分享文本与“水电燃气费用收费明细表”表格图片（Canvas 绘制），
 * 并通过 FileProvider 分享到微信（图文），或保存到相册。
 */
public class BillShare {

    private static final int W = 1080;

    private static final int C_BLACK = Color.rgb(0x22, 0x22, 0x22);
    private static final int C_GRAY = Color.rgb(0x99, 0x99, 0x99);
    private static final int C_LINE = Color.rgb(0xBB, 0xBB, 0xBB);
    private static final int C_HEAD_BG = Color.rgb(0xE8, 0xF1, 0xEC);
    private static final int C_LABEL_BG = Color.rgb(0xF7, 0xF9, 0xFC);
    private static final int C_TOTAL_BG = Color.rgb(0xFE, 0xF3, 0xE2);
    private static final int C_PRIMARY = Color.rgb(0x2F, 0x6B, 0x4F);
    private static final int C_ACCENT = Color.rgb(0xE0, 0x6A, 0x1F);

    /** 表格列定义 */
    private static class Col {
        String title;
        String sub;
        String unit;
        Double start;   // 起码，null 表示无起止码（自定义项目）
        Double end;
        double usage;
        double fee;
        String kind = "";   // water / elec / gas / extra（用于“合计”标题动态组合）
    }

    private static List<Col> buildColumns(Context ctx, Bill b) {
        if (b.extras == null) b.extras = new ArrayList<>();
        List<FeeItem> fees = null;
        try {
            fees = new DbHelper(ctx).getFeeItems();
        } catch (Exception ignored) {
        }
        FeeItem wf = findBind(fees, "water");
        FeeItem gf = findBind(fees, "gas");
        FeeItem ef = findBind(fees, "elec");
        if (ef == null) ef = findBind(fees, "elec1"); // 兼容旧数据

        List<Col> cols = new ArrayList<>();
        // 水表：动态列表（单块列名“水”，多块“水表1/水表2…”）
        List<Bill.Meter> wm = b.waterMeters != null && !b.waterMeters.isEmpty()
                ? b.waterMeters : null;
        if (wm == null && (b.waterStart != 0 || b.waterEnd != 0 || b.waterUsage != 0 || b.waterFee != 0)) {
            wm = new ArrayList<>();
            wm.add(new Bill.Meter("水表1", b.waterStart, b.waterEnd, b.waterUsage, b.waterFee));
        }
        if (wm != null) {
            boolean single = wm.size() == 1;
            for (Bill.Meter m : wm) {
                if (m == null) continue;
                String title = single ? "水" : (m.label == null || m.label.trim().isEmpty()
                        ? "水表" : m.label.trim());
                cols.add(makeCol(title, wf, "吨", m.start, m.end, m.usage, m.fee, "water"));
            }
        }

        // 燃气表：动态列表（单块列名“燃气”，多块“燃气表1/燃气表2…”）
        List<Bill.Meter> gm = b.gasMeters != null && !b.gasMeters.isEmpty()
                ? b.gasMeters : null;
        if (gm == null && (b.gasStart != 0 || b.gasEnd != 0 || b.gasUsage != 0 || b.gasFee != 0)) {
            gm = new ArrayList<>();
            gm.add(new Bill.Meter("燃气表1", b.gasStart, b.gasEnd, b.gasUsage, b.gasFee));
        }
        if (gm != null) {
            boolean single = gm.size() == 1;
            for (Bill.Meter m : gm) {
                if (m == null) continue;
                String title = single ? "燃气" : (m.label == null || m.label.trim().isEmpty()
                        ? "燃气表" : m.label.trim());
                cols.add(makeCol(title, gf, "方", m.start, m.end, m.usage, m.fee, "gas"));
            }
        }

        if (b.meters == null || b.meters.isEmpty()) {
            // 极老数据：无动态电表列表时用兼容列；确没有电表数据则不显示电列
            boolean hasElec = b.elec1Start != 0 || b.elec1End != 0 || b.elec1Usage != 0;
            boolean two = b.elec2Start != 0 || b.elec2End != 0 || b.elec2Usage != 0;
            if (hasElec || two) {
                cols.add(makeCol(two ? "电表1" : "电", ef, "度", b.elec1Start, b.elec1End, b.elec1Usage,
                        ef != null ? BillCalculator.round2(b.elec1Usage * ef.unitPrice) : b.elecFee, "elec"));
                if (two) {
                    cols.add(makeCol("电表2", ef, "度", b.elec2Start, b.elec2End, b.elec2Usage,
                            ef != null ? BillCalculator.round2(b.elec2Usage * ef.unitPrice) : 0, "elec"));
                }
            }
        } else {
            // 与编辑页一致：只有一块电表时不显示“电表1”，列名用“电”；多块才显示 电表1/电表2…
            boolean single = b.meters.size() == 1;
            for (Bill.Meter m : b.meters) {
                if (m == null) continue;
                FeeItem fm = findByName(fees, m.label);
                String title = single ? "电" : (m.label == null || m.label.trim().isEmpty()
                        ? "电表" : m.label.trim());
                cols.add(makeCol(title, fm != null ? fm : ef, "度", m.start, m.end, m.usage, m.fee, "elec"));
            }
        }

        // 自定义“按用量”项目（如燃气）作为额外列
        for (Bill.ExtraAmount e : b.extras) {
            if (e == null || !e.usageBased) continue;
            FeeItem f = findByName(fees, e.name);
            Col c = new Col();
            c.title = e.name;
            c.unit = f != null && f.unitLabel != null && !f.unitLabel.isEmpty() ? f.unitLabel : "方";
            c.sub = f != null ? BillCalculator.usage(f.unitPrice) + "元/" + c.unit : "";
            c.start = null;
            c.end = null;
            c.usage = e.usage;
            c.fee = e.amount;
            c.kind = isGasName(e.name) ? "gas" : "extra";
            cols.add(c);
        }
        return cols;
    }

    /** 自定义项目名是否属于燃气类（如用户自建“煤气表”项目，也归入燃气） */
    private static boolean isGasName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return n.contains("燃气") || n.contains("煤气") || n.contains("天然气") || n.equals("气");
    }

    /**
     * 按表格里实际存在的收费项目拼接项目名（顺序：水 → 电 → 燃气），
     * 与表头“项目”文字保持同一套叫法。没有燃气就只有“水电”。
     */
    private static String utilityNames(List<Col> cols) {
        boolean w = false, e = false, g = false;
        for (Col c : cols) {
            if (c == null) continue;
            if ("water".equals(c.kind)) w = true;
            else if ("elec".equals(c.kind)) e = true;
            else if ("gas".equals(c.kind)) g = true;
        }
        StringBuilder sb = new StringBuilder();
        if (w) sb.append("水");
        if (e) sb.append("电");
        if (g) sb.append("燃气");
        return sb.toString();
    }

    /** “合计”行文字：与项目文字一致，如“水电燃气合计”“水电合计”“电合计”；无项目时退回“合计” */
    private static String subtotalLabel(List<Col> cols) {
        String names = utilityNames(cols);
        return names.isEmpty() ? "合计" : names + "合计";
    }

    /** 明细表标题：同样按项目动态拼接，如“水电气费用收费明细表”“水电费用收费明细表”“电费收费明细表” */
    private static String tableTitle(List<Col> cols) {
        String names = utilityNames(cols);
        if (names.isEmpty()) return "费用收费明细表";
        int kinds = 0;
        for (Col c : cols) {
            if (c == null) continue;
            if ("water".equals(c.kind)) kinds |= 1;
            else if ("elec".equals(c.kind)) kinds |= 2;
            else if ("gas".equals(c.kind)) kinds |= 4;
        }
        // 只有一类项目时读作“电费收费明细表”，多类时读作“水电费用收费明细表”
        int n = (kinds & 1) + ((kinds >> 1) & 1) + ((kinds >> 2) & 1);
        return n == 1 ? names + "费收费明细表" : names + "费用收费明细表";
    }

    /**
     * 是否显示“XX合计”行：
     * 1）必须有“其他收费”（固定金额）——否则合计等于总费用，重复；
     * 2）表格里至少有两列——只有一列时合计就是那一列金额，也重复。
     */
    private static boolean shouldShowSubtotal(List<Col> cols, List<Bill.ExtraAmount> fixed) {
        return !fixed.isEmpty() && cols.size() > 1;
    }

    /** 合计金额：表格内各（按用量）表费用之和 */
    private static double subtotalOf(List<Col> cols) {
        double s = 0;
        for (Col c : cols) {
            if (c != null) s += c.fee;
        }
        return BillCalculator.round2(s);
    }

    /** 是否有“其他收费”（固定金额项目）；没有则不显示合计行 */
    private static boolean hasOtherFee(Bill b) {
        if (b == null || b.extras == null) return false;
        for (Bill.ExtraAmount e : b.extras) {
            if (e != null && !e.usageBased) return true;
        }
        return false;
    }

    private static Col makeCol(String title, FeeItem f, String defUnit,
                               double start, double end, double usage, double fee) {
        return makeCol(title, f, defUnit, start, end, usage, fee, "");
    }

    private static Col makeCol(String title, FeeItem f, String defUnit,
                               double start, double end, double usage, double fee, String kind) {
        Col c = new Col();
        c.title = title;
        c.unit = f != null && f.unitLabel != null && !f.unitLabel.isEmpty() ? f.unitLabel : defUnit;
        c.sub = f != null ? BillCalculator.usage(f.unitPrice) + "元/" + c.unit : "";
        c.start = start;
        c.end = end;
        c.usage = usage;
        c.fee = fee;
        c.kind = kind;
        return c;
    }

    private static FeeItem findBind(List<FeeItem> fees, String bind) {
        if (fees == null) return null;
        for (FeeItem f : fees) {
            if (bind.equals(f.bindField)) return f;
        }
        return null;
    }

    private static FeeItem findByName(List<FeeItem> fees, String name) {
        if (fees == null) return null;
        for (FeeItem f : fees) {
            if (f.name.equals(name)) return f;
        }
        return null;
    }

    // ---------------- 文本 ----------------

    /** 生成分享文本（含起止码） */
    public static String buildText(Context ctx, Bill b) {
        List<Col> cols = buildColumns(ctx, b);
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(tableTitle(cols)).append("】\n");
        String room = b.room == null ? "" : b.room.trim();
        if (!room.isEmpty()) {
            sb.append("房间：").append(room);
        }
        if (b.tenant != null && !b.tenant.trim().isEmpty()) {
            if (!room.isEmpty()) sb.append("　");
            sb.append("租客：").append(b.tenant.trim());
        }
        sb.append("\n月份：").append(b.month == null ? "" : b.month).append("\n");
        sb.append("--------------------------------\n");

        // 表头
        StringBuilder head = new StringBuilder("项目");
        StringBuilder rStart = new StringBuilder("起码");
        StringBuilder rEnd = new StringBuilder("止码");
        StringBuilder rUsage = new StringBuilder("用量");
        StringBuilder rFee = new StringBuilder("费用");
        for (Col c : cols) {
            String h = c.title + (TextUtils.isEmpty(c.sub) ? "" : "(" + c.sub + ")");
            head.append("｜").append(h);
            rStart.append("｜").append(c.start == null ? "—" : BillCalculator.usage(c.start));
            rEnd.append("｜").append(c.end == null ? "—" : BillCalculator.usage(c.end));
            rUsage.append("｜").append(BillCalculator.usage(c.usage)).append(c.unit);
            rFee.append("｜").append(BillCalculator.money(c.fee)).append("元");
        }
        sb.append(head).append("\n");
        sb.append(rStart).append("\n");
        sb.append(rEnd).append("\n");
        sb.append(rUsage).append("\n");
        sb.append(rFee).append("\n");

        // 合计金额：表格内各表费用之和
        double subtotal = subtotalOf(cols);

        // 固定费用明细
        List<Bill.ExtraAmount> fixed = new ArrayList<>();
        if (b.extras != null) {
            for (Bill.ExtraAmount e : b.extras) {
                if (e != null && !e.usageBased) fixed.add(e);
            }
        }
        // 显示规则：有其他收费，且表格里至少两列（只有一列时合计就等于该列金额，不重复显示）
        boolean showSubtotal = shouldShowSubtotal(cols, fixed);
        if (showSubtotal) {
            sb.append("--------------------------------\n");
            sb.append(subtotalLabel(cols)).append("：")
                    .append(BillCalculator.money(subtotal)).append(" 元\n");
        }
        if (!fixed.isEmpty()) {
            if (!showSubtotal) sb.append("--------------------------------\n");
            sb.append("其他费用：\n");
            for (Bill.ExtraAmount e : fixed) {
                sb.append("　").append(e.name).append("：")
                        .append(BillCalculator.money(e.amount)).append("元\n");
            }
        }
        sb.append("--------------------------------\n");
        sb.append("总费用：").append(BillCalculator.money(b.total())).append(" 元\n");
        if (b.note != null && !b.note.trim().isEmpty()) {
            sb.append("备注：").append(b.note.trim()).append("\n");
        }
        return sb.toString();
    }

    /** 兼容旧签名 */
    public static String buildText(Bill b) {
        return buildText(null, b);
    }

    // ---------------- 图片 ----------------

    /** 生成表格图片 Bitmap */
    public static Bitmap buildImage(Context ctx, Bill b) {
        List<Col> cols = buildColumns(ctx, b);
        int n = Math.max(cols.size(), 1);
        String room = b.room == null ? "" : b.room.trim();
        String tenant = (b.tenant == null || b.tenant.trim().isEmpty()) ? "" : "　租客：" + b.tenant.trim();
        String note = (b.note == null || b.note.trim().isEmpty()) ? null : b.note.trim();
        String month = b.month == null ? "" : b.month;

        int pad = 36;
        int titleH = 110;
        int metaH = 92;
        int headerH = 96;
        int rowH = 88;
        int labelW = 170;
        int tableW = W - pad * 2;
        int metricW = Math.max((tableW - labelW) / n, 40);

        List<Bill.ExtraAmount> fixed = new ArrayList<>();
        for (Bill.ExtraAmount e : b.extras) {
            if (e != null && !e.usageBased) fixed.add(e);
        }
        // 合计行：仅当存在“其他收费”且表格至少两列时显示；标题按水、电、燃气实际有无动态组合
        boolean showSubtotal = shouldShowSubtotal(cols, fixed);
        String subLabel = subtotalLabel(cols);
        double subtotal = subtotalOf(cols);

        int subtotalH = showSubtotal ? 78 : 0;
        int fixedH = 0;
        if (!fixed.isEmpty()) {
            fixedH = 56 + fixed.size() * 60;
        }
        int totalH = 96;
        int noteH = note != null ? 80 : 16;

        int tableTop = titleH + metaH;
        int tableH = headerH + 4 * rowH;
        int subtotalTop = tableTop + tableH + 16;
        int fixedTop = subtotalTop + subtotalH + (fixed.isEmpty() || !showSubtotal ? 0 : 16);
        int totalTop = fixedTop + fixedH + 8;
        int H = totalTop + totalH + noteH + 16;

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
        bold.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        // 标题
        bold.setColor(C_BLACK);
        bold.setTextSize(46);
        bold.setTextAlign(Paint.Align.CENTER);
        c.drawText(tableTitle(cols), W / 2f, baseline(0, titleH, bold), bold);

        // 元信息
        p.setColor(C_BLACK);
        p.setTextSize(32);
        p.setTextAlign(Paint.Align.LEFT);
        String meta1 = room.isEmpty() ? (tenant.isEmpty() ? "" : tenant.trim()) : "房间：" + room + tenant;
        c.drawText(meta1, pad, titleH + 42, p);
        p.setColor(C_GRAY);
        c.drawText("月份：" + month, pad, titleH + 82, p);
        p.setColor(C_BLACK);

        // 表格背景
        p.setStyle(Paint.Style.FILL);
        p.setColor(C_HEAD_BG);
        c.drawRect(pad, tableTop, pad + tableW, tableTop + headerH, p);
        p.setColor(C_LABEL_BG);
        c.drawRect(pad, tableTop, pad + labelW, tableTop + tableH, p);

        // 表头文字
        float headerCenterY = tableTop + headerH / 2f;
        bold.setTextSize(34);
        bold.setColor(C_BLACK);
        bold.setTextAlign(Paint.Align.CENTER);
        c.drawText("项目", pad + labelW / 2f, headerCenterY - 12, bold);
        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setTextSize(22);
        sub.setColor(C_GRAY);
        sub.setTextAlign(Paint.Align.CENTER);
        c.drawText("单位/单价", pad + labelW / 2f, headerCenterY + 22, sub);
        for (int i = 0; i < n; i++) {
            Col col = cols.get(i);
            float cx = pad + labelW + i * metricW + metricW / 2f;
            bold.setTextSize(32);
            bold.setColor(C_BLACK);
            c.drawText(col.title, cx, headerCenterY - 14, bold);
            sub.setTextSize(22);
            sub.setColor(C_PRIMARY);
            c.drawText(TextUtils.isEmpty(col.sub) ? "—" : col.sub, cx, headerCenterY + 22, sub);
        }

        // 行
        String[] labels = {"起码", "止码", "用量", "费用"};
        for (int r = 0; r < 4; r++) {
            float cy = tableTop + headerH + r * rowH + rowH / 2f;
            bold.setTextSize(30);
            bold.setColor(C_BLACK);
            c.drawText(labels[r], pad + labelW / 2f, cy + 10, bold);
            for (int i = 0; i < n; i++) {
                Col col = cols.get(i);
                float cx = pad + labelW + i * metricW + metricW / 2f;
                String txt;
                if (r == 0) {
                    txt = col.start == null ? "—" : BillCalculator.usage(col.start);
                } else if (r == 1) {
                    txt = col.end == null ? "—" : BillCalculator.usage(col.end);
                } else if (r == 2) {
                    txt = BillCalculator.usage(col.usage) + col.unit;
                } else {
                    txt = BillCalculator.money(col.fee) + "元";
                }
                Paint use = (r == 3) ? bold : p;
                use.setTextSize(30);
                use.setTextAlign(Paint.Align.CENTER);
                use.setColor(r == 3 ? C_PRIMARY : C_BLACK);
                c.drawText(txt, cx, cy + 10, use);
            }
        }

        // 网格线
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(C_LINE);
        // 外框
        c.drawRect(pad, tableTop, pad + tableW, tableTop + tableH, p);
        // 横线
        for (int r = 0; r <= 4; r++) {
            float y = tableTop + headerH + r * rowH;
            c.drawLine(pad, y, pad + tableW, y, p);
        }
        // 竖线
        for (int i = 0; i <= n; i++) {
            float x = pad + labelW + i * metricW;
            c.drawLine(x, tableTop, x, tableTop + tableH, p);
        }

        // 合计条（绿色系，区别于橙色总费用条）：没有其他收费时不画
        if (showSubtotal) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(C_HEAD_BG);
            c.drawRect(pad, subtotalTop, pad + tableW, subtotalTop + subtotalH, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(C_PRIMARY);
            c.drawRect(pad, subtotalTop, pad + tableW, subtotalTop + subtotalH, p);
            bold.setColor(C_PRIMARY);
            bold.setTextSize(32);
            bold.setTextAlign(Paint.Align.LEFT);
            c.drawText(subLabel, pad + 28, subtotalTop + subtotalH / 2f + 11, bold);
            bold.setTextSize(40);
            bold.setTextAlign(Paint.Align.RIGHT);
            c.drawText(BillCalculator.money(subtotal) + " 元", pad + tableW - 28,
                    subtotalTop + subtotalH / 2f + 14, bold);
        }

        // 其他费用（固定项目）
        if (!fixed.isEmpty()) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(C_BLACK);
            p.setTextSize(30);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("其他费用", pad, fixedTop + 34, p);
            for (int i = 0; i < fixed.size(); i++) {
                Bill.ExtraAmount e = fixed.get(i);
                float y = fixedTop + 56 + i * 60 + 34;
                p.setColor(C_GRAY);
                c.drawText("· " + e.name, pad + 18, y, p);
                p.setColor(C_BLACK);
                p.setTextAlign(Paint.Align.RIGHT);
                c.drawText(BillCalculator.money(e.amount) + " 元", pad + tableW - 18, y, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
        }

        // 总费用条
        p.setStyle(Paint.Style.FILL);
        p.setColor(C_TOTAL_BG);
        c.drawRect(pad, totalTop, pad + tableW, totalTop + totalH, p);
        p.setStyle(Paint.Style.STROKE);
        p.setColor(C_ACCENT);
        p.setStrokeWidth(2);
        c.drawRect(pad, totalTop, pad + tableW, totalTop + totalH, p);
        bold.setColor(C_ACCENT);
        bold.setTextSize(36);
        bold.setTextAlign(Paint.Align.LEFT);
        c.drawText("总费用", pad + 28, totalTop + totalH / 2f + 13, bold);
        bold.setTextSize(44);
        bold.setTextAlign(Paint.Align.RIGHT);
        c.drawText(BillCalculator.money(b.total()) + " 元", pad + tableW - 28,
                totalTop + totalH / 2f + 15, bold);

        // 备注
        if (note != null) {
            p.setColor(C_GRAY);
            p.setTextSize(26);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText("备注：" + note, pad, totalTop + totalH + 48, p);
        }

        return bmp;
    }

    private static float baseline(int top, int bottom, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f;
    }

    // ---------------- 分享 / 保存 ----------------

    /** 图文分享到微信（含其他可接收图片的应用）；内部捕获所有异常，失败 Toast 提示 */
    public static boolean shareToWeChat(Context ctx, Bill b) {
        try {
            Bitmap bmp = buildImage(ctx, b);
            File dir = new File(ctx.getCacheDir(), "bill_images");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "bill_" + System.currentTimeMillis() + ".png");
            FileOutputStream fos = new FileOutputStream(file);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            fos.close();

            Uri uri = FileProvider.getUriForFile(ctx,
                    ctx.getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_TEXT, buildText(ctx, b));
            intent.putExtra(Intent.EXTRA_SUBJECT, tableTitle(buildColumns(ctx, b)));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(intent, "分享账单（可选择微信）");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Toast.makeText(ctx, "无法打开分享，请先安装微信，或点“存为表格图片”后发送图片",
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    /** 保存表格图片到相册（Downloads/Pictures），成功返回 true */
    public static boolean saveToGallery(Context ctx, Bill b) {
        Bitmap bmp = buildImage(ctx, b);
        String name = "bill_" + b.month + "_" + b.room + "_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/房东小助手");
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return false;
                OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                if (os != null) os.close();
                return true;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "房东小助手");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                FileOutputStream fos = new FileOutputStream(file);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
                fos.close();
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
