package com.rent.utility.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条账单记录。
 * 每个表都有“起码 / 止码”，用量 = 止码 − 起码：
 * 水表 waterStart/waterEnd、电表1 elec1Start/elec1End、电表2 elec2Start/elec2End。
 * usage 字段由起止码自动计算（保留字段以兼容旧数据与表格显示）。
 */
public class Bill {

    public long id;

    public String month = "";
    public String room = "";
    public String tenant = "";

    // 水表（第 1 块镜像到 waterStart/waterEnd/waterUsage/waterFee 兼容列；多表时列表为准）
    public double waterStart;
    public double waterEnd;
    public double waterUsage;
    public double waterFee;
    /** 动态水表列表（每块水表：表名/起码/止码/用量/费用），至少一块 */
    public List<Meter> waterMeters = new ArrayList<>();

    // 燃气表（第 1 块镜像到 gasStart/gasEnd/gasUsage/gasFee 兼容列）
    public double gasStart;
    public double gasEnd;
    public double gasUsage;
    public double gasFee;
    /** 动态燃气表列表 */
    public List<Meter> gasMeters = new ArrayList<>();

    // 电表1（兼容列，动态电表由 meters 列表管理）
    public double elec1Start;
    public double elec1End;
    public double elec1Usage;

    // 电表2（兼容列）
    public double elec2Start;
    public double elec2End;
    public double elec2Usage;

    public double elecFee;

    /** 动态电表列表（每块电表：表名/起码/止码/用量/费用），至少一块 */
    public List<Meter> meters = new ArrayList<>();

    /** 其他费用明细（可含多个收费项目） */
    public List<ExtraAmount> extras = new ArrayList<>();

    public String note = "";

    /** 云端同步用：全局唯一标识（本机生成，换手机也不会重复） */
    public String uid = "";

    /** 上传设备标识（如 HONOR Magic6），仅用于云端列表区分来源 */
    public String device = "";

    /** 云端同步用：最后修改时间（毫秒时间戳），用于多端合并时判断新旧 */
    public long updatedAt = 0;

    /** 一块电表 */
    public static class Meter {
        public String label = "电表1";
        public double start;
        public double end;
        public double usage;
        public double fee;

        public Meter() {
        }

        public Meter(String label, double start, double end, double usage, double fee) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.usage = usage;
            this.fee = fee;
        }
    }

    /** 单条其他费用 */
    public static class ExtraAmount {
        public String name;
        public boolean usageBased; // true=按用量（用量×单价），false=固定金额
        public double usage;       // 用量（仅 usageBased 时使用）
        public double amount;      // 金额（元）

        public ExtraAmount(String name, boolean usageBased, double usage, double amount) {
            this.name = name;
            this.usageBased = usageBased;
            this.usage = usage;
            this.amount = amount;
        }
    }

    /** 由起止码计算用量：止码−起码（负数按 0 处理） */
    public static double use(double start, double end) {
        double d = end - start;
        return d < 0 ? 0 : d;
    }

    /** 根据起止码刷新水/燃气/各电表用量 */
    public void recalcUsageFromCodes() {
        waterUsage = use(waterStart, waterEnd);
        gasUsage = use(gasStart, gasEnd);
        if (waterMeters != null) {
            for (Meter m : waterMeters) {
                if (m != null) m.usage = use(m.start, m.end);
            }
        }
        if (gasMeters != null) {
            for (Meter m : gasMeters) {
                if (m != null) m.usage = use(m.start, m.end);
            }
        }
        for (Meter m : meters) {
            if (m != null) m.usage = use(m.start, m.end);
        }
    }

    /** 是否录入了起止码 */
    public boolean hasWaterCode() {
        return waterStart != 0 || waterEnd != 0;
    }

    public boolean hasGasCode() {
        return gasStart != 0 || gasEnd != 0;
    }

    public boolean hasElec1Code() {
        return elec1Start != 0 || elec1End != 0;
    }

    public boolean hasElec2Code() {
        return elec2Start != 0 || elec2End != 0;
    }

    /** 水表是否有数据（任一字段非 0，含多表） */
    public boolean hasWaterData() {
        if (waterStart != 0 || waterEnd != 0 || waterUsage != 0 || waterFee != 0) return true;
        if (waterMeters != null) {
            for (Meter m : waterMeters) {
                if (m != null && (m.start != 0 || m.end != 0 || m.usage != 0 || m.fee != 0)) return true;
            }
        }
        return false;
    }

    /** 燃气表是否有数据（含多表） */
    public boolean hasGasData() {
        if (gasStart != 0 || gasEnd != 0 || gasUsage != 0 || gasFee != 0) return true;
        if (gasMeters != null) {
            for (Meter m : gasMeters) {
                if (m != null && (m.start != 0 || m.end != 0 || m.usage != 0 || m.fee != 0)) return true;
            }
        }
        return false;
    }

    /** 水表总用量（各水表用量之和） */
    public double waterUsageTotal() {
        double s = 0;
        if (waterMeters != null) {
            for (Meter m : waterMeters) {
                if (m != null) s += m.usage;
            }
        }
        return s;
    }

    /** 燃气表总用量（各燃气表用量之和） */
    public double gasUsageTotal() {
        double s = 0;
        if (gasMeters != null) {
            for (Meter m : gasMeters) {
                if (m != null) s += m.usage;
            }
        }
        return s;
    }

    /** 把第 1 块水表/燃气表镜像到兼容字段（waterStart 等），列表为空则清 0 */
    public void syncWaterGasFromMeters() {
        if (waterMeters != null && !waterMeters.isEmpty()) {
            Meter m = waterMeters.get(0);
            if (m != null) {
                waterStart = m.start;
                waterEnd = m.end;
                waterUsage = m.usage;
            }
        } else {
            waterStart = 0;
            waterEnd = 0;
            waterUsage = 0;
        }
        if (gasMeters != null && !gasMeters.isEmpty()) {
            Meter m = gasMeters.get(0);
            if (m != null) {
                gasStart = m.start;
                gasEnd = m.end;
                gasUsage = m.usage;
            }
        } else {
            gasStart = 0;
            gasEnd = 0;
            gasUsage = 0;
        }
    }

    /** 电表总用量（各电表用量之和） */
    public double elecUsage() {
        double s = 0;
        for (Meter m : meters) {
            if (m != null) s += m.usage;
        }
        return s;
    }

    /** 其他费用合计 */
    public double otherFee() {
        double s = 0;
        for (ExtraAmount e : extras) {
            s += e.amount;
        }
        return s;
    }

    /** 账单合计 */
    public double total() {
        return waterFee + gasFee + elecFee + otherFee();
    }

    /** 生成“下个月”的账单副本：月份+1，本月止码 → 下月起码，止码/用量/费用清零（其他费用项目保留但清空金额） */
    public Bill copyForNextMonth() {
        Bill b2 = new Bill();
        b2.month = nextMonth(month);
        b2.room = room;
        b2.tenant = tenant;
        b2.waterStart = waterEnd;
        b2.waterEnd = 0;
        b2.waterUsage = 0;
        b2.waterFee = 0;
        if (waterMeters != null) {
            for (Meter m : waterMeters) {
                if (m == null) continue;
                b2.waterMeters.add(new Meter(m.label, m.end, 0, 0, 0));
            }
        }
        b2.gasStart = gasEnd;
        b2.gasEnd = 0;
        b2.gasUsage = 0;
        b2.gasFee = 0;
        if (gasMeters != null) {
            for (Meter m : gasMeters) {
                if (m == null) continue;
                b2.gasMeters.add(new Meter(m.label, m.end, 0, 0, 0));
            }
        }
        for (Meter m : meters) {
            if (m == null) continue;
            Meter n = new Meter(m.label, m.end, 0, 0, 0);
            b2.meters.add(n);
        }
        b2.elecFee = 0;
        b2.note = "";
        b2.extras = new ArrayList<>();
        if (extras != null) {
            for (ExtraAmount e : extras) {
                b2.extras.add(new ExtraAmount(e.name, e.usageBased, 0, 0));
            }
        }
        return b2;
    }

    /** 月份字符串（yyyy-MM）加一个月，如 2025-12 → 2026-01 */
    public static String nextMonth(String ym) {
        if (ym == null || !ym.matches("\\d{4}-\\d{1,2}")) {
            return ym;
        }
        try {
            String[] p = ym.split("-");
            int y = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            m++;
            if (m > 12) {
                m = 1;
                y++;
            }
            return y + "-" + (m < 10 ? "0" + m : String.valueOf(m));
        } catch (Exception e) {
            return ym;
        }
    }

    /** 序列化动态水表列表为 JSON 字符串 */
    public String toWaterMetersJson() {
        return metersToJson(waterMeters);
    }

    /** 从 JSON 字符串解析动态水表列表 */
    public static List<Meter> parseWaterMeters(String json) {
        return parseMeters(json, "水表");
    }

    /** 序列化动态燃气表列表为 JSON 字符串 */
    public String toGasMetersJson() {
        return metersToJson(gasMeters);
    }

    /** 从 JSON 字符串解析动态燃气表列表 */
    public static List<Meter> parseGasMeters(String json) {
        return parseMeters(json, "燃气表");
    }

    private static String metersToJson(List<Meter> list) {
        JSONArray arr = new JSONArray();
        if (list != null) {
            for (Meter m : list) {
                if (m == null) continue;
                try {
                    JSONObject o = new JSONObject();
                    o.put("label", m.label);
                    o.put("start", m.start);
                    o.put("end", m.end);
                    o.put("usage", m.usage);
                    o.put("fee", m.fee);
                    arr.put(o);
                } catch (JSONException ignored) {
                }
            }
        }
        return arr.toString();
    }

    static List<Meter> parseMeters(String json, String defaultPrefix) {
        List<Meter> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Meter m = new Meter();
                m.label = o.optString("label", defaultPrefix + (i + 1));
                m.start = o.optDouble("start", 0);
                m.end = o.optDouble("end", 0);
                m.usage = o.optDouble("usage", 0);
                m.fee = o.optDouble("fee", 0);
                list.add(m);
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 序列化动态电表列表为 JSON 字符串 */
    public String toMetersJson() {
        return metersToJson(meters);
    }

    /** 序列化其他费用明细为 JSON 字符串 */
    public String toExtrasJson() {
        JSONArray arr = new JSONArray();
        for (ExtraAmount e : extras) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("usageBased", e.usageBased);
                o.put("usage", e.usage);
                o.put("amount", e.amount);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    /** 从 JSON 字符串解析其他费用明细 */
    public static List<ExtraAmount> parseExtras(String json) {
        List<ExtraAmount> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new ExtraAmount(
                        o.optString("name", ""),
                        o.optBoolean("usageBased", false),
                        o.optDouble("usage", 0),
                        o.optDouble("amount", 0)));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }
}
