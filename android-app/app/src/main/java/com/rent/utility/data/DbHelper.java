package com.rent.utility.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite 数据层：收费项目表 + 账单表 + 房间表。
 * 首次创建时预置：水费(3元/吨)、电表1(1元/度)、电表2(1元/度)、燃气费(3元/方)，
 * 以及一条示例账单（来自用户的水电费账单.csv）。
 * v2：账单增加每个表的“起码/止码”，用量=止码−起码。
 * v3：升级时自愈补齐 bills / fee_items 可能缺失的列（兼容更早版本建的表）。
 * v4：账单增加 meter_count（电表数量，1=一块电表，2=两块电表）。
 * v5：账单增加 meters_json（动态电表列表，支持 1..N 块）与燃气表（gas_*）列；
 *     收费项目补充内置“燃气费”。
 * v6：新增 rooms 表（房间名/租客/租房时间/到期时间）；删除房间级联删除该房间全部账单；
 *     保存账单时自动登记房间；历史账单按房间组织（房间 → 账单 二级导航）。
 * v8：水表、燃气表也支持多块（water_meters_json / gas_meters_json 动态列表，兼容列存第 1 块）；
 *     收费项目内置合并为 水费 / 电表 / 燃气费 三项（去掉“电表1”“电表2”两个条目，统一按“电表”计费）。
 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "rent_utility.db";
    private static final int DB_VERSION = 9;

    public DbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        // 升级前先整库备份一份，万一迁移出问题还能捞回来
        backupBeforeUpgrade(context);
    }

    /** 数据库版本变化时，把旧库复制到 files/db_backup/（最多保留 5 份） */
    private static void backupBeforeUpgrade(Context ctx) {
        try {
            java.io.File dbFile = ctx.getDatabasePath(DB_NAME);
            if (!dbFile.exists()) return;
            int oldVer;
            SQLiteDatabase probe = null;
            try {
                probe = SQLiteDatabase.openDatabase(dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                oldVer = probe.getVersion();
            } finally {
                if (probe != null) probe.close();
            }
            if (oldVer <= 0 || oldVer >= DB_VERSION) return;
            java.io.File dir = new java.io.File(ctx.getFilesDir(), "db_backup");
            if (!dir.exists() && !dir.mkdirs()) return;
            java.io.File out = new java.io.File(dir, "rent_utility_v" + oldVer + "_" + System.currentTimeMillis() + ".db");
            java.io.InputStream in = new java.io.FileInputStream(dbFile);
            java.io.OutputStream os = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
                try { os.close(); } catch (Exception ignored) {}
            }
            java.io.File[] all = dir.listFiles();
            if (all != null && all.length > 5) {
                java.util.Arrays.sort(all, new java.util.Comparator<java.io.File>() {
                    public int compare(java.io.File a, java.io.File b) {
                        return Long.compare(a.lastModified(), b.lastModified());
                    }
                });
                for (int i = 0; i < all.length - 5; i++) all[i].delete();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE fee_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "type INTEGER NOT NULL DEFAULT 0," +
                "unit_price REAL NOT NULL DEFAULT 0," +
                "unit_label TEXT DEFAULT ''," +
                "bind_field TEXT," +
                "enabled INTEGER NOT NULL DEFAULT 1)");

        db.execSQL("CREATE TABLE bills (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "month TEXT NOT NULL," +
                "room TEXT NOT NULL," +
                "tenant TEXT DEFAULT ''," +
                "meter_count INTEGER DEFAULT 2," +
                "water_start REAL DEFAULT 0," +
                "water_end REAL DEFAULT 0," +
                "water_usage REAL DEFAULT 0," +
                "water_fee REAL DEFAULT 0," +
                "gas_start REAL DEFAULT 0," +
                "gas_end REAL DEFAULT 0," +
                "gas_usage REAL DEFAULT 0," +
                "gas_fee REAL DEFAULT 0," +
                "elec1_start REAL DEFAULT 0," +
                "elec1_end REAL DEFAULT 0," +
                "elec1_usage REAL DEFAULT 0," +
                "elec2_start REAL DEFAULT 0," +
                "elec2_end REAL DEFAULT 0," +
                "elec2_usage REAL DEFAULT 0," +
                "elec_fee REAL DEFAULT 0," +
                "meters_json TEXT DEFAULT '[]'," +
                "water_meters_json TEXT DEFAULT '[]'," +
                "gas_meters_json TEXT DEFAULT '[]'," +
                "extra_items TEXT DEFAULT '[]'," +
                "note TEXT DEFAULT ''," +
                "created_at INTEGER DEFAULT 0," +
                "uid TEXT DEFAULT ''," +
                "updated_at INTEGER DEFAULT 0)");

        db.execSQL("CREATE TABLE rooms (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "tenant TEXT DEFAULT ''," +
                "rent_start TEXT DEFAULT ''," +
                "rent_end TEXT DEFAULT ''," +
                "created_at INTEGER DEFAULT 0)");

        seed(db);
    }

    private void seed(SQLiteDatabase db) {
        // 只预置默认收费项目，不再插入示例账单/示例房间（全新安装=空白开始）
        insertFeeRow(db, "水费", FeeItem.TYPE_USAGE, 3.0, "吨", "water");
        insertFeeRow(db, "电表", FeeItem.TYPE_USAGE, 1.0, "度", "elec");
        insertFeeRow(db, "燃气费", FeeItem.TYPE_USAGE, 3.0, "方", "gas");
    }

    private void insertFeeRow(SQLiteDatabase db, String name, int type, double price, String unit, String bind) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("type", type);
        v.put("unit_price", price);
        v.put("unit_label", unit);
        v.put("bind_field", bind);
        v.put("enabled", 1);
        db.insert("fee_items", null, v);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        try {
            // 无论从哪个旧版本升级：先自愈补齐 bills / fee_items 缺失列，再修正旧数据，
            // 避免早期版本建的表缺列导致查询崩溃
            ensureColumns(db);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 补齐两表所有可能缺失的列（幂等：已存在的列会被 addColumn 忽略） */
    private void ensureColumns(SQLiteDatabase db) {
        addColumn(db, "bills", "uid", "TEXT DEFAULT ''");
        addColumn(db, "bills", "updated_at", "INTEGER DEFAULT 0");
        addColumn(db, "bills", "tenant", "TEXT DEFAULT ''");
        addColumn(db, "bills", "meter_count", "INTEGER DEFAULT 2");
        addColumn(db, "bills", "water_start", "REAL DEFAULT 0");
        addColumn(db, "bills", "water_end", "REAL DEFAULT 0");
        addColumn(db, "bills", "water_usage", "REAL DEFAULT 0");
        addColumn(db, "bills", "water_fee", "REAL DEFAULT 0");
        addColumn(db, "bills", "gas_start", "REAL DEFAULT 0");
        addColumn(db, "bills", "gas_end", "REAL DEFAULT 0");
        addColumn(db, "bills", "gas_usage", "REAL DEFAULT 0");
        addColumn(db, "bills", "gas_fee", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec1_start", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec1_end", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec1_usage", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec2_start", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec2_end", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec2_usage", "REAL DEFAULT 0");
        addColumn(db, "bills", "elec_fee", "REAL DEFAULT 0");
        addColumn(db, "bills", "meters_json", "TEXT DEFAULT '[]'");
        addColumn(db, "bills", "water_meters_json", "TEXT DEFAULT '[]'");
        addColumn(db, "bills", "gas_meters_json", "TEXT DEFAULT '[]'");
        addColumn(db, "bills", "extra_items", "TEXT DEFAULT '[]'");
        addColumn(db, "bills", "note", "TEXT DEFAULT ''");
        addColumn(db, "bills", "created_at", "INTEGER DEFAULT 0");

        addColumn(db, "fee_items", "unit_label", "TEXT DEFAULT ''");
        addColumn(db, "fee_items", "bind_field", "TEXT");
        addColumn(db, "fee_items", "enabled", "INTEGER NOT NULL DEFAULT 1");

        // 旧数据只有用量：默认起码 0、止码=原用量，保证用量不变
        try {
            db.execSQL("UPDATE bills SET water_end=water_usage WHERE water_start=0 AND water_end=0");
            db.execSQL("UPDATE bills SET elec1_end=elec1_usage WHERE elec1_start=0 AND elec1_end=0");
            db.execSQL("UPDATE bills SET elec2_end=elec2_usage WHERE elec2_start=0 AND elec2_end=0");
            db.execSQL("UPDATE bills SET gas_end=gas_usage WHERE gas_start=0 AND gas_end=0");
        } catch (Exception ignored) {
        }

        // 老库补充内置“燃气费”（幂等：已存在则不重复插入）
        try {
            Cursor c = db.rawQuery("SELECT COUNT(*) FROM fee_items WHERE bind_field='gas'", null);
            boolean exists = false;
            if (c.moveToFirst()) exists = c.getInt(0) > 0;
            c.close();
            if (!exists) {
                ContentValues v = new ContentValues();
                v.put("name", "燃气费");
                v.put("type", FeeItem.TYPE_USAGE);
                v.put("unit_price", 3.0);
                v.put("unit_label", "方");
                v.put("bind_field", "gas");
                v.put("enabled", 1);
                db.insert("fee_items", null, v);
            }
        } catch (Exception ignored) {
        }

        // 燃气单位统一为“方”（老数据为“立方米”）
        try {
            db.execSQL("UPDATE fee_items SET unit_label='方' WHERE bind_field='gas'");
        } catch (Exception ignored) {
        }

        // 有旧电表数据但无 meters_json：从 elec1/elec2 迁移生成动态电表列表
        try {
            Cursor c = db.rawQuery(
                    "SELECT id, elec1_start, elec1_end, elec1_usage, elec2_start, elec2_end, elec2_usage, elec_fee, meter_count " +
                            "FROM bills WHERE meters_json IS NULL OR meters_json='' OR meters_json='[]'", null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                org.json.JSONArray arr = new org.json.JSONArray();
                org.json.JSONObject m1 = new org.json.JSONObject();
                m1.put("label", "电表1");
                m1.put("start", c.getDouble(1));
                m1.put("end", c.getDouble(2));
                m1.put("usage", c.getDouble(3));
                m1.put("fee", 0);
                arr.put(m1);
                boolean two = c.getInt(8) != 1;
                if (two || c.getDouble(4) != 0 || c.getDouble(5) != 0 || c.getDouble(6) != 0) {
                    org.json.JSONObject m2 = new org.json.JSONObject();
                    m2.put("label", "电表2");
                    m2.put("start", c.getDouble(4));
                    m2.put("end", c.getDouble(5));
                    m2.put("usage", c.getDouble(6));
                    m2.put("fee", 0);
                    arr.put(m2);
                }
                db.execSQL("UPDATE bills SET meters_json=? WHERE id=?",
                        new Object[]{arr.toString(), id});
            }
            c.close();
        } catch (Exception ignored) {
        }

        // v8：有旧水表数据但无 water_meters_json：从兼容列生成动态水表列表；燃气同理
        try {
            Cursor c = db.rawQuery(
                    "SELECT id, water_start, water_end, water_usage, water_fee " +
                            "FROM bills WHERE water_meters_json IS NULL OR water_meters_json='' OR water_meters_json='[]'",
                    null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                org.json.JSONArray arr = new org.json.JSONArray();
                org.json.JSONObject m = new org.json.JSONObject();
                m.put("label", "水表1");
                m.put("start", c.getDouble(1));
                m.put("end", c.getDouble(2));
                m.put("usage", c.getDouble(3));
                m.put("fee", c.getDouble(4));
                arr.put(m);
                db.execSQL("UPDATE bills SET water_meters_json=? WHERE id=?",
                        new Object[]{arr.toString(), id});
            }
            c.close();
        } catch (Exception ignored) {
        }
        try {
            Cursor c = db.rawQuery(
                    "SELECT id, gas_start, gas_end, gas_usage, gas_fee " +
                            "FROM bills WHERE gas_meters_json IS NULL OR gas_meters_json='' OR gas_meters_json='[]'",
                    null);
            while (c.moveToNext()) {
                long id = c.getLong(0);
                org.json.JSONArray arr = new org.json.JSONArray();
                org.json.JSONObject m = new org.json.JSONObject();
                m.put("label", "燃气表1");
                m.put("start", c.getDouble(1));
                m.put("end", c.getDouble(2));
                m.put("usage", c.getDouble(3));
                m.put("fee", c.getDouble(4));
                arr.put(m);
                db.execSQL("UPDATE bills SET gas_meters_json=? WHERE id=?",
                        new Object[]{arr.toString(), id});
            }
            c.close();
        } catch (Exception ignored) {
        }

        // v8：收费项目内置合并：电表1/电表2 → 一条“电表”（bind=elec），保留 id 最小的那条的单价
        try {
            Cursor c = db.rawQuery("SELECT id, bind_field, unit_price FROM fee_items " +
                    "WHERE bind_field IN ('elec1','elec2') ORDER BY id", null);
            long keepId = -1;
            double keepPrice = 1.0;
            java.util.List<Long> dropIds = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                if (keepId < 0) {
                    keepId = c.getLong(0);
                    keepPrice = c.getDouble(2);
                } else {
                    dropIds.add(c.getLong(0));
                }
            }
            c.close();
            if (keepId > 0) {
                ContentValues v = new ContentValues();
                v.put("name", "电表");
                v.put("bind_field", "elec");
                v.put("unit_label", "度");
                v.put("unit_price", keepPrice);
                db.update("fee_items", v, "id=?", new String[]{String.valueOf(keepId)});
                for (long drop : dropIds) {
                    db.delete("fee_items", "id=?", new String[]{String.valueOf(drop)});
                }
            }
        } catch (Exception ignored) {
        }

        // v6：确保 rooms 表存在，并从既有账单迁移房间
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS rooms (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT NOT NULL UNIQUE," +
                    "tenant TEXT DEFAULT ''," +
                    "rent_start TEXT DEFAULT ''," +
                    "rent_end TEXT DEFAULT ''," +
                    "created_at INTEGER DEFAULT 0)");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("INSERT OR IGNORE INTO rooms(name, created_at) " +
                    "SELECT DISTINCT room, MAX(created_at) FROM bills " +
                    "WHERE room IS NOT NULL AND room<>'' GROUP BY room");
        } catch (Exception ignored) {
        }

        // v7：移除旧版预置的示例账单（备注含“示例账单”），并清理因此变空的房间
        try {
            db.delete("bills", "note LIKE '%示例账单%'", null);
            db.execSQL("DELETE FROM rooms WHERE name NOT IN " +
                    "(SELECT DISTINCT room FROM bills WHERE room IS NOT NULL AND room<>'')");
        } catch (Exception ignored) {
        }
        try {
            db.execSQL("UPDATE rooms SET tenant=COALESCE((" +
                    "SELECT b.tenant FROM bills b WHERE b.room=rooms.name AND b.tenant<>'' " +
                    "ORDER BY b.month DESC, b.id DESC LIMIT 1), '') WHERE tenant=''");
        } catch (Exception ignored) {
        }
    }

    private void addColumn(SQLiteDatabase db, String table, String col, String def) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + def);
        } catch (Exception ignored) {
            // 列已存在则忽略
        }
    }

    // ---------- 收费项目 ----------

    public List<FeeItem> getFeeItems() {
        List<FeeItem> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,name,type,unit_price,unit_label,bind_field,enabled FROM fee_items ORDER BY id", null);
            while (c.moveToNext()) {
                FeeItem f = new FeeItem();
                f.id = c.getLong(0);
                f.name = c.getString(1);
                f.type = c.getInt(2);
                f.unitPrice = c.getDouble(3);
                f.unitLabel = c.getString(4);
                f.bindField = c.getString(5);
                f.enabled = c.getInt(6) == 1;
                list.add(f);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    public long saveFeeItem(FeeItem f) {
        ContentValues v = new ContentValues();
        v.put("name", f.name);
        v.put("type", f.type);
        v.put("unit_price", f.unitPrice);
        v.put("unit_label", f.unitLabel);
        v.put("bind_field", f.bindField);
        v.put("enabled", f.enabled ? 1 : 0);
        if (f.id > 0) {
            getWritableDatabase().update("fee_items", v, "id=?", new String[]{String.valueOf(f.id)});
            return f.id;
        }
        return getWritableDatabase().insert("fee_items", null, v);
    }

    public void deleteFeeItem(long id) {
        getWritableDatabase().delete("fee_items", "id=?", new String[]{String.valueOf(id)});
    }

    // ---------- 账单 ----------

    private static final String COLS =
            "id,month,room,tenant,water_start,water_end,water_usage,water_fee," +
            "gas_start,gas_end,gas_usage,gas_fee," +
            "elec1_start,elec1_end,elec1_usage,elec2_start,elec2_end,elec2_usage,elec_fee," +
            "meters_json,water_meters_json,gas_meters_json,extra_items,note," +
            // 注意：新增列必须同时加进 COLS，否则下标会错位（v2.10 曾因漏加导致账单读不出来）
            "uid,updated_at";

    private Bill fromCursor(Cursor c) {
        Bill b = new Bill();
        b.id = c.getLong(0);
        b.month = safe(c.getString(1));
        b.room = safe(c.getString(2));
        b.tenant = safe(c.getString(3));
        b.waterStart = c.getDouble(4);
        b.waterEnd = c.getDouble(5);
        b.waterUsage = c.getDouble(6);
        b.waterFee = c.getDouble(7);
        b.gasStart = c.getDouble(8);
        b.gasEnd = c.getDouble(9);
        b.gasUsage = c.getDouble(10);
        b.gasFee = c.getDouble(11);
        b.elec1Start = c.getDouble(12);
        b.elec1End = c.getDouble(13);
        b.elec1Usage = c.getDouble(14);
        b.elec2Start = c.getDouble(15);
        b.elec2End = c.getDouble(16);
        b.elec2Usage = c.getDouble(17);
        b.elecFee = c.getDouble(18);
        // 按列名读取，避免以后加减列时下标错位（越界会让整张列表变空）
        int iUid = c.getColumnIndex("uid");
        int iUpdated = c.getColumnIndex("updated_at");
        b.uid = iUid >= 0 ? safe(c.getString(iUid)) : "";
        b.updatedAt = iUpdated >= 0 ? c.getLong(iUpdated) : 0;
        b.meters = Bill.parseMeters(c.getString(19), "电表");
        b.waterMeters = Bill.parseWaterMeters(c.getString(20));
        b.gasMeters = Bill.parseGasMeters(c.getString(21));
        // 兼容：water_meters_json 为空（极老数据）时用兼容列兜底
        if (b.waterMeters.isEmpty()) {
            if (b.waterStart != 0 || b.waterEnd != 0 || b.waterUsage != 0 || b.waterFee != 0) {
                b.waterMeters.add(new Bill.Meter("水表1", b.waterStart, b.waterEnd, b.waterUsage, b.waterFee));
            }
        }
        if (b.gasMeters.isEmpty()) {
            if (b.gasStart != 0 || b.gasEnd != 0 || b.gasUsage != 0 || b.gasFee != 0) {
                b.gasMeters.add(new Bill.Meter("燃气表1", b.gasStart, b.gasEnd, b.gasUsage, b.gasFee));
            }
        }
        // 兼容：meters_json 为空（极老数据）时用 elec1/elec2 列兜底
        if (b.meters.isEmpty()) {
            b.meters.add(new Bill.Meter("电表1", b.elec1Start, b.elec1End, b.elec1Usage, 0));
            if (b.elec2Start != 0 || b.elec2End != 0 || b.elec2Usage != 0) {
                b.meters.add(new Bill.Meter("电表2", b.elec2Start, b.elec2End, b.elec2Usage, 0));
            }
            // 起止码全 0 说明确实没有电表：清空（不显示）
            if (b.meters.size() == 1
                    && b.elec1Start == 0 && b.elec1End == 0 && b.elec1Usage == 0) {
                b.meters.clear();
            }
        }
        b.extras = Bill.parseExtras(c.getString(22));
        b.note = safe(c.getString(23));
        return b;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public List<Bill> getBills() {
        return getBills(null);
    }

    public List<Bill> getBills(String month) {
        return getBills(null, month, null);
    }

    /** 按 年/月/关键词 筛选账单；year/month 传 null 表示不限，keyword 传 null/空 表示不限 */
    public List<Bill> getBills(String year, String month, String keyword) {
        List<Bill> list = new ArrayList<>();
        Cursor c = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM bills WHERE 1=1");
            List<String> args = new ArrayList<>();
            if (year != null && !year.isEmpty()) {
                sql.append(" AND substr(month,1,4)=?");
                args.add(year);
            }
            if (month != null && !month.isEmpty()) {
                sql.append(" AND substr(month,6,2)=?");
                args.add(month.length() == 1 ? "0" + month : month);
            }
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = "%" + keyword.trim() + "%";
                sql.append(" AND (room LIKE ? OR tenant LIKE ? OR note LIKE ?)");
                args.add(kw);
                args.add(kw);
                args.add(kw);
            }
            sql.append(" ORDER BY month DESC, id DESC");
            c = db.rawQuery(sql.toString(), args.toArray(new String[0]));
            while (c.moveToNext()) list.add(fromCursor(c));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    /** 获取账单中出现过的年份（降序），用于年份筛选 */
    public List<String> getYears() {
        List<String> list = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT DISTINCT substr(month,1,4) AS y FROM bills " +
                            "WHERE month GLOB '[0-9][0-9][0-9][0-9]-*' ORDER BY y DESC", null);
            while (c.moveToNext()) list.add(c.getString(0));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    public Bill getBill(long id) {
        Cursor c = null;
        Bill b = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM bills WHERE id=?", new String[]{String.valueOf(id)});
            if (c.moveToFirst()) b = fromCursor(c);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return b;
    }

    public long saveBill(Bill b) {
        return saveBillInternal(b, true);
    }

    /** 云端合并专用：保留服务端给的 updatedAt，避免刚拉下来的账单又被标成“刚刚修改” */
    public long saveBillFromCloud(Bill b) {
        return saveBillInternal(b, false);
    }

    private long saveBillInternal(Bill b, boolean touchTime) {
        if (b.uid == null || b.uid.isEmpty()) {
            b.uid = newUid();
        }
        if (touchTime || b.updatedAt <= 0) {
            b.updatedAt = System.currentTimeMillis();
        }
        ContentValues v = new ContentValues();
        v.put("uid", b.uid);
        v.put("updated_at", b.updatedAt);
        v.put("month", b.month);
        v.put("room", b.room);
        v.put("tenant", b.tenant);
        v.put("water_start", b.waterStart);
        v.put("water_end", b.waterEnd);
        v.put("water_usage", b.waterUsage);
        v.put("water_fee", b.waterFee);
        v.put("gas_start", b.gasStart);
        v.put("gas_end", b.gasEnd);
        v.put("gas_usage", b.gasUsage);
        v.put("gas_fee", b.gasFee);
        // 同步动态水表/燃气表到兼容列（第 1 块），保证旧版/CSV 兼容
        b.syncWaterGasFromMeters();
        v.put("water_start", b.waterStart);
        v.put("water_end", b.waterEnd);
        v.put("water_usage", b.waterUsage);
        v.put("gas_start", b.gasStart);
        v.put("gas_end", b.gasEnd);
        v.put("gas_usage", b.gasUsage);
        // 同步动态电表到兼容列（前两块），保证旧版/CSV 兼容
        Bill.Meter m1 = b.meters != null && b.meters.size() > 0 ? b.meters.get(0) : null;
        Bill.Meter m2 = b.meters != null && b.meters.size() > 1 ? b.meters.get(1) : null;
        v.put("elec1_start", m1 != null ? m1.start : 0);
        v.put("elec1_end", m1 != null ? m1.end : 0);
        v.put("elec1_usage", m1 != null ? m1.usage : 0);
        v.put("elec2_start", m2 != null ? m2.start : 0);
        v.put("elec2_end", m2 != null ? m2.end : 0);
        v.put("elec2_usage", m2 != null ? m2.usage : 0);
        v.put("elec_fee", b.elecFee);
        v.put("meters_json", b.toMetersJson());
        v.put("water_meters_json", b.toWaterMetersJson());
        v.put("gas_meters_json", b.toGasMetersJson());
        v.put("extra_items", b.toExtrasJson());
        v.put("note", b.note);
        if (b.id > 0) {
            getWritableDatabase().update("bills", v, "id=?", new String[]{String.valueOf(b.id)});
            upsertRoomFromBill(b.room, b.tenant);
            return b.id;
        }
        v.put("created_at", System.currentTimeMillis());
        long newId = getWritableDatabase().insert("bills", null, v);
        // 保存账单时自动登记房间，否则云端下载/导入的账单不会出现在房间列表里
        upsertRoomFromBill(b.room, b.tenant);
        return newId;
    }

    /** 按云端 uid 查本地账单（同步合并用） */
    public Bill findByUid(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM bills WHERE uid=?", new String[]{uid});
            if (c.moveToFirst()) return fromCursor(c);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    /** 生成 24 位十六进制 uid */
    public static String newUid() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) {
            sb.append(Integer.toHexString(r.nextInt(16)));
        }
        return sb.toString();
    }

    public void deleteBill(long id) {
        getWritableDatabase().delete("bills", "id=?", new String[]{String.valueOf(id)});
    }

    /** 获取全部已出现的月份（降序），用于月度筛选 */
    public List<String> getMonths() {
        List<String> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT month FROM bills ORDER BY month DESC", null);
        while (c.moveToNext()) list.add(c.getString(0));
        c.close();
        return list;
    }

    // ---------- 房间 ----------

    /** 保存账单后登记房间（房间名已存在则保留其信息，仅更新租客），返回房间 id */
    public long upsertRoomFromBill(String room, String tenant) {
        if (room == null || room.trim().isEmpty()) return -1;
        String name = room.trim();
        SQLiteDatabase db = getWritableDatabase();
        Room r = getRoom(name);
        if (r != null) {
            if (tenant != null && !tenant.trim().isEmpty() && !tenant.trim().equals(r.tenant)) {
                ContentValues v = new ContentValues();
                v.put("tenant", tenant.trim());
                db.update("rooms", v, "name=?", new String[]{name});
            }
            return r.id;
        }
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("tenant", tenant == null ? "" : tenant.trim());
        v.put("created_at", System.currentTimeMillis());
        return db.insert("rooms", null, v);
    }

    private static final String ROOM_COLS = "id,name,tenant,rent_start,rent_end";

    private Room roomFromCursor(Cursor c) {
        Room r = new Room();
        r.id = c.getLong(0);
        r.name = safe(c.getString(1));
        r.tenant = safe(c.getString(2));
        r.rentStart = safe(c.getString(3));
        r.rentEnd = safe(c.getString(4));
        return r;
    }

    /** 全部房间（带统计：账单数/最新月份/最新合计/全部合计），按名称排序 */
    public List<Room> getRooms() {
        List<Room> rooms = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + ROOM_COLS + " FROM rooms ORDER BY name COLLATE NOCASE", null);
            while (c.moveToNext()) rooms.add(roomFromCursor(c));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        // 汇总统计
        List<Bill> all = getBills();
        for (Room r : rooms) {
            double totalAll = 0;
            for (Bill b : all) {
                if (b.room != null && b.room.equals(r.name)) {
                    totalAll += b.total();
                    r.billCount++;
                    if (r.latestMonth.isEmpty() || compareMonth(b.month, r.latestMonth) > 0) {
                        r.latestMonth = b.month;
                        r.latestTotal = b.total();
                    }
                }
            }
            r.totalAll = totalAll;
        }
        return rooms;
    }

    private static int compareMonth(String a, String b) {
        String na = a == null ? "" : a.replace("-", "");
        String nb = b == null ? "" : b.replace("-", "");
        return na.compareTo(nb);
    }

    public Room getRoom(String name) {
        if (name == null) return null;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + ROOM_COLS + " FROM rooms WHERE name=?", new String[]{name});
            if (c.moveToFirst()) return roomFromCursor(c);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public void updateRoom(Room r) {
        ContentValues v = new ContentValues();
        v.put("name", r.name);
        v.put("tenant", r.tenant);
        v.put("rent_start", r.rentStart);
        v.put("rent_end", r.rentEnd);
        getWritableDatabase().update("rooms", v, "id=?", new String[]{String.valueOf(r.id)});
    }

    /** 删除房间：同时级联删除该房间的全部账单 */
    public void deleteRoom(String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("bills", "room=?", new String[]{name});
        db.delete("rooms", "name=?", new String[]{name});
    }

    /** 某房间全部账单（按月份倒序） */
    public List<Bill> getBillsByRoom(String room) {
        List<Bill> list = new ArrayList<>();
        if (room == null) return list;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT " + COLS + " FROM bills WHERE room=? ORDER BY month DESC, id DESC",
                    new String[]{room});
            while (c.moveToNext()) list.add(fromCursor(c));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return list;
    }

    /** 某房间最新一条账单（“当前账单”），无则 null */
    public Bill getRoomLatestBill(String room) {
        List<Bill> bills = getBillsByRoom(room);
        return bills.isEmpty() ? null : bills.get(0);
    }

    // ---------- 导入 / 局域网同步 ----------

    /** 本机是否已存在同房间同月份的账单 */
    public boolean billExists(String room, String month) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM bills WHERE room=? AND month=?",
                    new String[]{room == null ? "" : room, month == null ? "" : month});
            if (c.moveToFirst()) return c.getInt(0) > 0;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (c != null) c.close();
        }
        return false;
    }

    /** 房间不存在则插入；存在时仅补充本机缺失的租客/租期信息 */
    public long insertRoomIfAbsent(String name, String tenant, String rentStart, String rentEnd) {
        if (name == null || name.trim().isEmpty()) return -1;
        String n = name.trim();
        Room r = getRoom(n);
        if (r != null) {
            boolean changed = false;
            if (tenant != null && !tenant.trim().isEmpty()
                    && (r.tenant == null || r.tenant.isEmpty())) {
                r.tenant = tenant.trim();
                changed = true;
            }
            if (rentStart != null && !rentStart.trim().isEmpty()
                    && (r.rentStart == null || r.rentStart.isEmpty())) {
                r.rentStart = rentStart.trim();
                changed = true;
            }
            if (rentEnd != null && !rentEnd.trim().isEmpty()
                    && (r.rentEnd == null || r.rentEnd.isEmpty())) {
                r.rentEnd = rentEnd.trim();
                changed = true;
            }
            if (changed) updateRoom(r);
            return r.id;
        }
        ContentValues v = new ContentValues();
        v.put("name", n);
        v.put("tenant", tenant == null ? "" : tenant.trim());
        v.put("rent_start", rentStart == null ? "" : rentStart.trim());
        v.put("rent_end", rentEnd == null ? "" : rentEnd.trim());
        v.put("created_at", System.currentTimeMillis());
        return getWritableDatabase().insert("rooms", null, v);
    }
}
