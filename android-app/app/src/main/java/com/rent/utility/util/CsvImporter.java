package com.rent.utility.util;

import com.rent.utility.data.Bill;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 CsvExporter 导出的 CSV 还原账单数据（与导出格式一一对应，可完整回导）。
 * 列：月份,房间,租客,水起码,水止码,水用量,水费,
 *     燃气起码,燃气止码,燃气用量,燃气费,
 *     电表1起码,电表1止码,电表1用量,电表1费用,电表2起码,电表2止码,电表2用量,电表2费用,
 *     电费,其他费用,其他费用明细,合计,备注
 * 第 2 块及以上水表/燃气表、第 3 块及以上电表在“其他费用明细”中按 表名=金额 记录，
 * 导入时还原为对应的动态表。
 */
public class CsvImporter {

    public static List<Bill> parseCsv(String content) {
        List<Bill> bills = new ArrayList<>();
        if (content == null || content.trim().isEmpty()) return bills;
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (i == 0) {
                // 表头行跳过
                if (line.contains("月份") && line.contains("房间")) continue;
            }
            List<String> c = parseLine(line);
            if (c.size() < 20) continue;
            Bill b = new Bill();
            b.month = cell(c, 0);
            b.room = cell(c, 1);
            b.tenant = cell(c, 2);
            double ws = d(c, 3), we = d(c, 4), wu = d(c, 5), wf = d(c, 6);
            double gs = d(c, 7), ge = d(c, 8), gu = d(c, 9), gf = d(c, 10);
            double e1s = d(c, 11), e1e = d(c, 12), e1u = d(c, 13), e1f = d(c, 14);
            double e2s = d(c, 15), e2e = d(c, 16), e2u = d(c, 17), e2f = d(c, 18);
            b.elecFee = d(c, 19);
            String detail = cell(c, 21);
            b.note = cell(c, 23);
            if (b.month.isEmpty() || !b.month.matches("\\d{4}-\\d{1,2}")) continue;

            // 第 1 块水表/燃气表（有起止码或费用才登记）
            if (ws != 0 || we != 0 || wu != 0 || wf != 0) {
                b.waterMeters.add(new Bill.Meter("水表1", ws, we, wu, 0));
            }
            if (gs != 0 || ge != 0 || gu != 0 || gf != 0) {
                b.gasMeters.add(new Bill.Meter("燃气表1", gs, ge, gu, 0));
            }
            boolean hasE1 = e1s != 0 || e1e != 0 || e1u != 0 || e1f != 0;
            boolean hasE2 = e2s != 0 || e2e != 0 || e2u != 0 || e2f != 0;
            if (hasE1) b.meters.add(new Bill.Meter("电表1", e1s, e1e, e1u, e1f));
            if (hasE2) b.meters.add(new Bill.Meter("电表2", e2s, e2e, e2u, e2f));
            if (!detail.isEmpty()) {
                for (String item : detail.split(";")) {
                    int eq = item.indexOf('=');
                    if (eq <= 0) continue;
                    String nm = item.substring(0, eq).trim();
                    double amt = tryParse(item.substring(eq + 1));
                    if (nm.startsWith("水表") && !nm.equals("水表1")) {
                        b.waterMeters.add(new Bill.Meter(nm, 0, 0, 0, amt));
                    } else if (nm.startsWith("燃气表") && !nm.equals("燃气表1")) {
                        b.gasMeters.add(new Bill.Meter(nm, 0, 0, 0, amt));
                    } else if (nm.startsWith("电表") && !nm.equals("电表1") && !nm.equals("电表2")) {
                        b.meters.add(new Bill.Meter(nm, 0, 0, 0, amt));
                    } else if (!nm.isEmpty()) {
                        b.extras.add(new Bill.ExtraAmount(nm, false, 0, amt));
                    }
                }
            }
            // 费用合计：水费/燃气费列本身是全部表合计，直接采用
            b.waterFee = wf;
            b.gasFee = gf;
            // 电费合计：若明细还原出多块电表，重新汇总
            double es = 0;
            for (Bill.Meter m : b.meters) {
                if (m != null) es += m.fee;
            }
            if (es > 0) b.elecFee = es;
            b.recalcUsageFromCodes();
            b.syncWaterGasFromMeters();
            bills.add(b);
        }
        return bills;
    }

    /** 按逗号拆分一行，支持 "..." 引号包裹（含逗号/换行内转义） */
    private static List<String> parseLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQ = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQ) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQ = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else {
                if (ch == '"') {
                    inQ = true;
                } else if (ch == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(ch);
                }
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String cell(List<String> c, int idx) {
        return idx < c.size() ? c.get(idx).trim() : "";
    }

    private static double d(List<String> c, int idx) {
        return tryParse(cell(c, idx));
    }

    private static double tryParse(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
