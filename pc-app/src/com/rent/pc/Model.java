package com.rent.pc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据模型 + 与安卓 v2.9 完全一致的业务规则 */
public final class Model {

    public static final class Meter {
        public String label = "";
        public double start, end, usage, fee;

        public Meter() { }

        public Meter(String label, double start, double end, double usage, double fee) {
            this.label = label;
            this.start = start;
            this.end = end;
            this.usage = usage;
            this.fee = fee;
        }
    }

    public static final class Extra {
        public String name = "";
        public boolean usageBased;
        public double usage, amount;

        public Extra() { }

        public Extra(String name, boolean usageBased, double usage, double amount) {
            this.name = name;
            this.usageBased = usageBased;
            this.usage = usage;
            this.amount = amount;
        }
    }

    /** 表格列 */
    public static final class Col {
        public String title = "";
        public String sub = "";
        public String unit = "";
        public Double start;   // null 表示无起止码
        public Double end;
        public double usage;
        public double fee;
        public String kind = "";   // water / elec / gas / extra
    }

    public static final class Bill {
        public String uid = "";
        public String room = "";
        public String tenant = "";
        public String month = "";
        public String note = "";
        public List<Meter> waterMeters = new ArrayList<>();
        public List<Meter> gasMeters = new ArrayList<>();
        public List<Meter> meters = new ArrayList<>();
        public List<Extra> extras = new ArrayList<>();
        public long updatedAt = System.currentTimeMillis();

        public double waterFee() { return sum(waterMeters); }
        public double gasFee() { return sum(gasMeters); }
        public double elecFee() { return sum(meters); }

        public double otherFee() {
            double s = 0;
            for (Extra e : extras) s += e.amount;
            return round2(s);
        }

        public double total() {
            return round2(waterFee() + gasFee() + elecFee() + otherFee());
        }

        public Bill copy() {
            Bill b = new Bill();
            b.uid = uid; b.room = room; b.tenant = tenant; b.month = month; b.note = note;
            b.waterMeters = copyMeters(waterMeters);
            b.gasMeters = copyMeters(gasMeters);
            b.meters = copyMeters(meters);
            for (Extra e : extras) b.extras.add(new Extra(e.name, e.usageBased, e.usage, e.amount));
            b.updatedAt = updatedAt;
            return b;
        }

        /** 下个月账单：止码→起码，止码/用量/费用清零，其他收费保留项目清空金额 */
        public Bill copyForNextMonth() {
            Bill b = copy();
            b.uid = "";
            b.month = nextMonth(month);
            for (Meter m : b.waterMeters) resetMeter(m);
            for (Meter m : b.gasMeters) resetMeter(m);
            for (Meter m : b.meters) resetMeter(m);
            for (Extra e : b.extras) e.amount = 0;
            b.updatedAt = System.currentTimeMillis();
            return b;
        }

        private static void resetMeter(Meter m) {
            m.start = m.end;
            m.end = 0;
            m.usage = 0;
            m.fee = 0;
        }

        private static double sum(List<Meter> list) {
            double s = 0;
            for (Meter m : list) s += m.fee;
            return round2(s);
        }

        private static List<Meter> copyMeters(List<Meter> src) {
            List<Meter> out = new ArrayList<>();
            for (Meter m : src) out.add(new Meter(m.label, m.start, m.end, m.usage, m.fee));
            return out;
        }
    }

    public static final class Room {
        public String id = "";
        public String name = "";
        public String tenant = "";
        public String note = "";
        public List<Bill> bills = new ArrayList<>();

        public String display() {
            return tenant == null || tenant.trim().isEmpty() ? name : name + "（" + tenant + "）";
        }
    }

    /** 全局设置：单价 + 同步配置 */
    public static final class Settings {
        public double waterPrice = 3.0;
        public double elecPrice = 1.0;
        public double gasPrice = 2.5;
        public String server = "";
        public String username = "";
        public String token = "";
        public String password = "";   // 仅用于登录时快速填充，不参与同步
    }

    public static final class Data {
        public List<Room> rooms = new ArrayList<>();
        public Settings settings = new Settings();

        public Room findRoom(String id) {
            for (Room r : rooms) if (r.id.equals(id)) return r;
            return null;
        }

        public Bill findBill(String uid) {
            for (Room r : rooms) {
                for (Bill b : r.bills) if (b.uid.equals(uid)) return b;
            }
            return null;
        }

        public List<Bill> allBills() {
            List<Bill> out = new ArrayList<>();
            for (Room r : rooms) for (Bill b : r.bills) out.add(b);
            return out;
        }
    }

    // ==================== 业务规则（与安卓 v2.9 一致） ====================

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 金额两位小数字符串 */
    public static String money(double v) {
        return String.format("%.2f", round2(v));
    }

    /** 用量/起止码：整数不带小数，否则保留两位 */
    public static String usage(double v) {
        double r = round2(v);
        if (Math.abs(r - Math.rint(r)) < 0.0001) return String.valueOf((long) Math.rint(r));
        return String.format("%.2f", r);
    }

    /** 自定义项目名是否属于燃气类 */
    public static boolean isGasName(String name) {
        if (name == null) return false;
        String n = name.trim();
        return n.contains("燃气") || n.contains("煤气") || n.contains("天然气") || n.equals("气");
    }

    /**
     * 生成表格列。顺序固定：水 → 电 → 燃气（燃气类自定义项目追加在最后）。
     * 单块表列名用“水/电/燃气”，多块表用“水表1/电表1/燃气表1”。
     */
    public static List<Col> buildColumns(Bill b, Settings s) {
        List<Col> cols = new ArrayList<>();
        if (b == null) return cols;

        // 水
        List<Meter> wm = b.waterMeters.isEmpty() ? null : b.waterMeters;
        if (wm == null && b.waterFee() != 0) {
            wm = new ArrayList<>();
            wm.add(new Meter("水表1", 0, 0, 0, b.waterFee()));
        }
        if (wm != null) {
            boolean single = wm.size() == 1;
            for (Meter m : wm) {
                if (m == null) continue;
                String title = single ? "水" : (m.label == null || m.label.trim().isEmpty() ? "水表" : m.label.trim());
                cols.add(makeCol(title, s.waterPrice, "吨", m.start, m.end, m.usage, m.fee, "water"));
            }
        }

        // 电
        List<Meter> em = b.meters.isEmpty() ? null : b.meters;
        if (em == null && b.elecFee() != 0) {
            em = new ArrayList<>();
            em.add(new Meter("电表1", 0, 0, 0, b.elecFee()));
        }
        if (em != null) {
            boolean single = em.size() == 1;
            for (Meter m : em) {
                if (m == null) continue;
                String title = single ? "电" : (m.label == null || m.label.trim().isEmpty() ? "电表" : m.label.trim());
                cols.add(makeCol(title, s.elecPrice, "度", m.start, m.end, m.usage, m.fee, "elec"));
            }
        }

        // 燃气
        List<Meter> gm = b.gasMeters.isEmpty() ? null : b.gasMeters;
        if (gm == null && b.gasFee() != 0) {
            gm = new ArrayList<>();
            gm.add(new Meter("燃气表1", 0, 0, 0, b.gasFee()));
        }
        if (gm != null) {
            boolean single = gm.size() == 1;
            for (Meter m : gm) {
                if (m == null) continue;
                String title = single ? "燃气" : (m.label == null || m.label.trim().isEmpty() ? "燃气表" : m.label.trim());
                cols.add(makeCol(title, s.gasPrice, "方", m.start, m.end, m.usage, m.fee, "gas"));
            }
        }

        // 自定义“按用量”项目（如用户自建的煤气表）作为额外列
        for (Extra e : b.extras) {
            if (e == null || !e.usageBased) continue;
            Col c = new Col();
            c.title = e.name;
            c.unit = "方";
            c.sub = "";
            c.usage = e.usage;
            c.fee = e.amount;
            c.kind = isGasName(e.name) ? "gas" : "extra";
            cols.add(c);
        }
        return cols;
    }

    private static Col makeCol(String title, double price, String defUnit,
                               double start, double end, double usage, double fee, String kind) {
        Col c = new Col();
        c.title = title;
        c.unit = defUnit;
        c.sub = price > 0 ? money(price) + "元/" + defUnit : "";
        c.start = start;
        c.end = end;
        c.usage = usage;
        c.fee = fee;
        c.kind = kind;
        return c;
    }

    /** 项目名（水 → 电 → 燃气） */
    public static String utilityNames(List<Col> cols) {
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

    /** 合计行文字 */
    public static String subtotalLabel(List<Col> cols) {
        String names = utilityNames(cols);
        return names.isEmpty() ? "合计" : names + "合计";
    }

    /** 明细表标题：只有一类 → “电费收费明细表”，多类 → “水电费用收费明细表” */
    public static String tableTitle(List<Col> cols) {
        String names = utilityNames(cols);
        if (names.isEmpty()) return "费用收费明细表";
        int kinds = 0;
        for (Col c : cols) {
            if (c == null) continue;
            if ("water".equals(c.kind)) kinds |= 1;
            else if ("elec".equals(c.kind)) kinds |= 2;
            else if ("gas".equals(c.kind)) kinds |= 4;
        }
        int n = (kinds & 1) + ((kinds >> 1) & 1) + ((kinds >> 2) & 1);
        return n == 1 ? names + "费收费明细表" : names + "费用收费明细表";
    }

    public static List<Extra> fixedExtras(Bill b) {
        List<Extra> out = new ArrayList<>();
        for (Extra e : b.extras) if (e != null && !e.usageBased) out.add(e);
        return out;
    }

    /** 有其他收费，且表格至少两列时才显示合计 */
    public static boolean shouldShowSubtotal(List<Col> cols, List<Extra> fixed) {
        return !fixed.isEmpty() && cols.size() > 1;
    }

    public static double subtotalOf(List<Col> cols) {
        double s = 0;
        for (Col c : cols) if (c != null) s += c.fee;
        return round2(s);
    }

    // ==================== 序列化 ====================

    public static Map<String, Object> billToJson(Bill b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uid", b.uid);
        m.put("room", b.room);
        m.put("tenant", b.tenant);
        m.put("month", b.month);
        m.put("note", b.note);
        m.put("waterMeters", metersToJson(b.waterMeters));
        m.put("gasMeters", metersToJson(b.gasMeters));
        m.put("meters", metersToJson(b.meters));
        m.put("waterFee", b.waterFee());
        m.put("gasFee", b.gasFee());
        m.put("elecFee", b.elecFee());
        List<Object> ex = new ArrayList<>();
        for (Extra e : b.extras) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("name", e.name);
            em.put("usageBased", e.usageBased);
            em.put("usage", e.usage);
            em.put("amount", e.amount);
            ex.add(em);
        }
        m.put("extras", ex);
        m.put("updatedAt", b.updatedAt);
        m.put("device", "PC (Windows)");
        return m;
    }

    public static Bill billFromJson(Map<String, Object> m) {
        Bill b = new Bill();
        b.uid = Json.str(m, "uid", "");
        b.room = Json.str(m, "room", "");
        b.tenant = Json.str(m, "tenant", "");
        b.month = Json.str(m, "month", "");
        b.note = Json.str(m, "note", "");
        b.waterMeters = metersFromJson(Json.arr(m, "waterMeters"));
        b.gasMeters = metersFromJson(Json.arr(m, "gasMeters"));
        b.meters = metersFromJson(Json.arr(m, "meters"));
        for (Object o : Json.arr(m, "extras")) {
            Map<String, Object> em = Json.obj(o);
            b.extras.add(new Extra(
                    Json.str(em, "name", ""),
                    Json.bool(em, "usageBased"),
                    Json.num(em, "usage", 0),
                    Json.num(em, "amount", 0)));
        }
        b.updatedAt = (long) Json.num(m, "updatedAt", System.currentTimeMillis());
        return b;
    }

    private static List<Object> metersToJson(List<Meter> list) {
        List<Object> out = new ArrayList<>();
        for (Meter m : list) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("label", m.label);
            mm.put("start", m.start);
            mm.put("end", m.end);
            mm.put("usage", m.usage);
            mm.put("fee", m.fee);
            out.add(mm);
        }
        return out;
    }

    private static List<Meter> metersFromJson(List<Object> arr) {
        List<Meter> out = new ArrayList<>();
        for (Object o : arr) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> mm = Json.obj(o);
            out.add(new Meter(
                    Json.str(mm, "label", ""),
                    Json.num(mm, "start", 0),
                    Json.num(mm, "end", 0),
                    Json.num(mm, "usage", 0),
                    Json.num(mm, "fee", 0)));
        }
        return out;
    }

    public static String nextMonth(String month) {
        if (month == null) return "";
        String t = month.trim();
        try {
            if (t.contains("-")) {
                String[] p = t.split("-");
                int y = Integer.parseInt(p[0]);
                int mo = Integer.parseInt(p[1]);
                mo++;
                if (mo > 12) { mo = 1; y++; }
                return y + "-" + (mo < 10 ? "0" + mo : String.valueOf(mo));
            }
        } catch (Exception ignored) { }
        return t;
    }
}
