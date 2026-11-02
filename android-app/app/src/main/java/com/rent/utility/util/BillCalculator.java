package com.rent.utility.util;

import com.rent.utility.data.Bill;
import com.rent.utility.data.FeeItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 算费逻辑：水费=水用量×水价；电费=电表1用量×电价1 + 电表2用量×电价2；
 * 其他项目：固定金额直接带入，按用量项目金额=用量×单价。
 */
public class BillCalculator {

    /** 保留两位小数 */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 用量格式化：整数不带小数点，小数最多两位并去掉末尾 0 */
    public static String usage(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        String s = String.format(Locale.CHINA, "%.2f", v);
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }

    /** 金额格式化：1234.5 -> "1234.50"；非法值按 0 处理 */
    public static String money(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
        return String.format(Locale.CHINA, "%.2f", v);
    }

    /** 先按起止码算用量，再按收费标准计算水费、燃气费与电费（其他费用明细不做改动） */
    public static void applyStandards(Bill b, List<FeeItem> items) {
        b.recalcUsageFromCodes();
        b.waterFee = 0;
        b.gasFee = 0;
        b.elecFee = 0;
        for (FeeItem f : items) {
            if (!f.enabled || f.type != FeeItem.TYPE_USAGE) continue;
            if ("water".equals(f.bindField)) {
                // 水费 = 各水表用量之和 × 水价
                b.waterFee = round2(b.waterUsageTotal() * f.unitPrice);
            } else if ("gas".equals(f.bindField)) {
                // 燃气费 = 各燃气表用量之和 × 燃气价
                b.gasFee = round2(b.gasUsageTotal() * f.unitPrice);
            } else if ("elec".equals(f.bindField)) {
                // 统一“电表”单价：所有电表 × 同一电价
                for (Bill.Meter m : b.meters) {
                    if (m != null) m.fee = round2(m.usage * f.unitPrice);
                }
            } else if ("elec1".equals(f.bindField) || "elec2".equals(f.bindField)) {
                // 兼容旧数据：按表名匹配单价（“电表1”→fee_items 的电表1，其余同理）
                for (Bill.Meter m : b.meters) {
                    if (m != null && f.name != null && f.name.equals(m.label)) {
                        m.fee = round2(m.usage * f.unitPrice);
                    }
                }
            }
        }
        // 汇总电费 = 各电表费用之和
        double es = 0;
        for (Bill.Meter m : b.meters) {
            if (m != null) es += m.fee;
        }
        b.elecFee = round2(es);
    }

    /** 新建账单时按收费标准预填其他费用明细（固定金额直接带入；按用量项目用量为 0） */
    public static List<Bill.ExtraAmount> defaultExtras(List<FeeItem> items) {
        List<Bill.ExtraAmount> list = new ArrayList<>();
        for (FeeItem f : items) {
            if (!f.enabled || f.isBuiltin()) continue;
            if (f.type == FeeItem.TYPE_FIXED) {
                list.add(new Bill.ExtraAmount(f.name, false, 0, round2(f.unitPrice)));
            } else {
                list.add(new Bill.ExtraAmount(f.name, true, 0, 0));
            }
        }
        return list;
    }

    /** 查找某收费项目（按名称，用于行内自动算费） */
    public static FeeItem findByName(List<FeeItem> items, String name) {
        for (FeeItem f : items) {
            if (f.name != null && f.name.equals(name)) return f;
        }
        return null;
    }
}
