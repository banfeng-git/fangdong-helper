package com.rent.utility.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.rent.utility.data.Bill;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 云端同步客户端（可选功能，不登录也能正常记账）。
 *
 * 服务端地址由用户自己填，Cloudflare Workers / 腾讯 EdgeOne / 阿里云 ESA
 * 三套后端接口完全一致，填任意一家的域名都能用。
 */
public class CloudSync {

    private static final String PREF = "cloud_sync";
    private static final String K_SERVER = "server";
    private static final String K_USER = "username";
    private static final String K_TOKEN = "token";

    public static class Res {
        public int code = -1;
        public String msg = "";
        public JSONObject data = new JSONObject();

        public boolean ok() {
            return code == 0;
        }
    }

    // ---------------- 本地保存的配置 ----------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static String getServer(Context ctx) {
        return prefs(ctx).getString(K_SERVER, "");
    }

    public static void setServer(Context ctx, String server) {
        prefs(ctx).edit().putString(K_SERVER, normalize(server)).apply();
    }

    public static String getUsername(Context ctx) {
        return prefs(ctx).getString(K_USER, "");
    }

    public static String getToken(Context ctx) {
        return prefs(ctx).getString(K_TOKEN, "");
    }

    public static boolean isLoggedIn(Context ctx) {
        return !getToken(ctx).isEmpty();
    }

    public static void saveLogin(Context ctx, String username, String token) {
        prefs(ctx).edit().putString(K_USER, username).putString(K_TOKEN, token).apply();
    }

    public static void clearLogin(Context ctx) {
        prefs(ctx).edit().remove(K_TOKEN).apply();
    }

    /** 用户只填域名也能用：自动补 /api，去掉多余的斜杠 */
    public static String normalize(String server) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) return "";
        if (!s.endsWith("/api")) s = s + "/api";
        return s;
    }

    // ---------------- 接口 ----------------

    public static Res register(Context ctx, String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        return post(ctx, "/register", body);
    }

    public static Res login(Context ctx, String username, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("username", username);
        body.put("password", password);
        return post(ctx, "/login", body);
    }

    public static Res logout(Context ctx) throws Exception {
        return post(ctx, "/logout", new JSONObject());
    }

    public static Res list(Context ctx) throws Exception {
        return get(ctx, "/bills");
    }

    /** 上传本地账单，服务端返回合并后的全量账单 */
    public static Res sync(Context ctx, List<Bill> bills) throws Exception {
        JSONArray arr = new JSONArray();
        for (Bill b : bills) arr.put(toJson(b));
        JSONObject body = new JSONObject();
        body.put("bills", arr);
        return post(ctx, "/bills/sync", body);
    }

    public static Res delete(Context ctx, String uid) throws Exception {
        JSONObject body = new JSONObject();
        body.put("uid", uid);
        return post(ctx, "/bills/delete", body);
    }

    // ---------------- HTTP ----------------

    private static Res get(Context ctx, String path) throws Exception {
        return request(ctx, "GET", path, null);
    }

    private static Res post(Context ctx, String path, JSONObject body) throws Exception {
        return request(ctx, "POST", path, body == null ? "{}" : body.toString());
    }

    private static Res request(Context ctx, String method, String path, String json) throws Exception {
        String base = getServer(ctx);
        if (base.isEmpty()) throw new Exception("请先填写服务端地址");

        URL url = new URL(base + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        String token = getToken(ctx);
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

        if (json != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
        }

        int status = conn.getResponseCode();
        String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();

        Res res = new Res();
        if (text.isEmpty()) {
            res.msg = "服务器无响应（HTTP " + status + "）";
            return res;
        }
        JSONObject root = new JSONObject(text);
        res.code = root.optInt("code", -1);
        res.msg = root.optString("msg", "");
        res.data = root.optJSONObject("data");
        if (res.data == null) res.data = new JSONObject();
        return res;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---------------- 账单 <-> JSON ----------------

    public static JSONObject toJson(Bill b) throws Exception {
        JSONObject o = new JSONObject();
        o.put("uid", b.uid);
        o.put("room", b.room);
        o.put("tenant", b.tenant);
        o.put("month", b.month);
        o.put("note", b.note);
        o.put("waterMeters", metersToJson(b.waterMeters));
        o.put("gasMeters", metersToJson(b.gasMeters));
        o.put("meters", metersToJson(b.meters));
        o.put("waterFee", round2(b.waterFee));
        o.put("gasFee", round2(b.gasFee));
        o.put("elecFee", round2(b.elecFee));
        JSONArray ex = new JSONArray();
        if (b.extras != null) {
            for (Bill.ExtraAmount e : b.extras) {
                if (e == null) continue;
                JSONObject em = new JSONObject();
                em.put("name", e.name);
                em.put("usageBased", e.usageBased);
                em.put("usage", round2(e.usage));
                em.put("amount", round2(e.amount));
                ex.put(em);
            }
        }
        o.put("extras", ex);
        o.put("updatedAt", b.updatedAt > 0 ? b.updatedAt : System.currentTimeMillis());
        return o;
    }

    private static JSONArray metersToJson(List<Bill.Meter> list) throws Exception {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (Bill.Meter m : list) {
            if (m == null) continue;
            JSONObject o = new JSONObject();
            o.put("label", m.label == null ? "" : m.label);
            o.put("start", round2(m.start));
            o.put("end", round2(m.end));
            o.put("usage", round2(m.usage));
            o.put("fee", round2(m.fee));
            arr.put(o);
        }
        return arr;
    }

    public static Bill fromJson(JSONObject o) {
        Bill b = new Bill();
        b.uid = o.optString("uid", "");
        b.room = o.optString("room", "");
        b.tenant = o.optString("tenant", "");
        b.month = o.optString("month", "");
        b.note = o.optString("note", "");
        b.waterMeters = metersFromJson(o.optJSONArray("waterMeters"));
        b.gasMeters = metersFromJson(o.optJSONArray("gasMeters"));
        b.meters = metersFromJson(o.optJSONArray("meters"));
        JSONArray ex = o.optJSONArray("extras");
        if (ex != null) {
            for (int i = 0; i < ex.length(); i++) {
                JSONObject em = ex.optJSONObject(i);
                if (em == null) continue;
                b.extras.add(new Bill.ExtraAmount(
                        em.optString("name", ""),
                        em.optBoolean("usageBased", false),
                        em.optDouble("usage", 0),
                        em.optDouble("amount", 0)));
            }
        }
        // 金额：服务端是独立字段，必须读，否则下载回来的账单金额全是 0
        b.waterFee = o.optDouble("waterFee", 0);
        b.gasFee = o.optDouble("gasFee", 0);
        b.elecFee = o.optDouble("elecFee", 0);
        // 兜底：服务端没给金额但有表计时，按表计费用汇总
        if (b.waterFee == 0) {
            double s0 = 0;
            for (Bill.Meter m : b.waterMeters) s0 += m.fee;
            if (s0 != 0) b.waterFee = round2(s0);
        }
        if (b.gasFee == 0) {
            double s0 = 0;
            for (Bill.Meter m : b.gasMeters) s0 += m.fee;
            if (s0 != 0) b.gasFee = round2(s0);
        }
        if (b.elecFee == 0) {
            double s0 = 0;
            for (Bill.Meter m : b.meters) s0 += m.fee;
            if (s0 != 0) b.elecFee = round2(s0);
        }
        b.updatedAt = o.optLong("updatedAt", System.currentTimeMillis());
        b.month = b.month.trim();
        b.room = b.room.trim();
        return b;
    }

    private static List<Bill.Meter> metersFromJson(JSONArray arr) {
        List<Bill.Meter> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            list.add(new Bill.Meter(
                    o.optString("label", ""),
                    o.optDouble("start", 0),
                    o.optDouble("end", 0),
                    o.optDouble("usage", 0),
                    o.optDouble("fee", 0)));
        }
        return list;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
