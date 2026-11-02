package com.rent.pc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云端同步客户端：只是普通 HTTPS 调用。
 * 服务端地址由用户自己填，Cloudflare / 腾讯 EdgeOne / 阿里云 ESA 任选其一，
 * 三者 API 完全一致，所以这里只认“地址 + /api”。
 */
public final class Cloud {

    public static final class Res {
        public int code = -1;
        public String msg = "";
        public Map<String, Object> data = new LinkedHashMap<>();

        public boolean ok() {
            return code == 0;
        }
    }

    private String base = "";
    private String token = "";

    public void setBase(String server) {
        String s = server == null ? "" : server.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.isEmpty()) {
            base = "";
            return;
        }
        if (!s.endsWith("/api")) s = s + "/api";
        base = s;
    }

    public String getBase() {
        return base;
    }

    public void setToken(String t) {
        token = t == null ? "" : t;
    }

    public String getToken() {
        return token;
    }

    public static class CloudException extends Exception {
        public CloudException(String m) { super(m); }
    }

    // ---------------- 接口 ----------------

    public Res register(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return post("/register", body);
    }

    public Res login(String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        return post("/login", body);
    }

    public Res logout() throws Exception {
        return post("/logout", new LinkedHashMap<>());
    }

    public Res list() throws Exception {
        return get("/bills");
    }

    /** 上传本地账单，返回云端合并后的全量账单 */
    public Res sync(List<Model.Bill> bills) throws Exception {
        List<Object> arr = new ArrayList<>();
        for (Model.Bill b : bills) arr.add(Model.billToJson(b));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bills", arr);
        return post("/bills/sync", body);
    }

    public Res delete(String uid) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", uid);
        return post("/bills/delete", body);
    }

    // ---------------- HTTP ----------------

    private Res get(String path) throws Exception {
        return request("GET", path, null);
    }

    private Res post(String path, Map<String, Object> body) throws Exception {
        return request("POST", path, Json.stringify(body));
    }

    private Res request(String method, String path, String json) throws Exception {
        if (base.isEmpty()) throw new CloudException("请先填写服务端地址");
        URL url = new URL(base + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);

        if (json != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }

        int status = conn.getResponseCode();
        String text = readAll(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();

        Res res = new Res();
        if (text == null || text.isEmpty()) {
            res.msg = "服务器无响应（HTTP " + status + "）";
            return res;
        }
        try {
            Map<String, Object> root = Json.parseObject(text);
            res.code = (int) Json.num(root, "code", -1);
            res.msg = Json.str(root, "msg", "");
            res.data = Json.obj(root.get("data"));
        } catch (Exception e) {
            res.msg = "返回内容无法解析：" + text.substring(0, Math.min(120, text.length()));
        }
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
}
