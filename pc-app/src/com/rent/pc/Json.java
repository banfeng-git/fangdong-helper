package com.rent.pc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写（不引第三方库，保证 PC 端双击即可运行）。
 * 解析结果：Map / List / String / Double / Boolean / null
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    public static Object parse(String text) {
        if (text == null) return null;
        Json p = new Json(text);
        p.ws();
        if (p.pos >= p.src.length()) return null;
        Object v = p.value();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    // ---------------- 解析 ----------------

    private void ws() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
            else break;
        }
    }

    private Object value() {
        char c = src.charAt(pos);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (c == 't' || c == 'f') return bool();
        if (c == 'n') { pos += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++; // {
        ws();
        if (pos < src.length() && src.charAt(pos) == '}') { pos++; return m; }
        while (pos < src.length()) {
            ws();
            String k = string();
            ws();
            if (pos < src.length() && src.charAt(pos) == ':') pos++;
            ws();
            m.put(k, value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == '}') { pos++; break; }
            break;
        }
        return m;
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++; // [
        ws();
        if (pos < src.length() && src.charAt(pos) == ']') { pos++; return list; }
        while (pos < src.length()) {
            ws();
            list.add(value());
            ws();
            if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
            if (pos < src.length() && src.charAt(pos) == ']') { pos++; break; }
            break;
        }
        return list;
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++; // "
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') break;
            if (c == '\\' && pos < src.length()) {
                char e = src.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (pos + 4 <= src.length()) {
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Boolean bool() {
        if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
        pos += 5;
        return Boolean.FALSE;
    }

    private Double number() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) pos++;
            else break;
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (Exception e) {
            return 0d;
        }
    }

    // ---------------- 序列化 ----------------

    public static String stringify(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object v : (List<Object>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o instanceof String) { quote(sb, (String) o); return; }
        if (o instanceof Boolean) { sb.append(o.toString()); return; }
        if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
            return;
        }
        quote(sb, o.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------- 取值助手 ----------------

    public static String str(Map<String, Object> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null ? def : String.valueOf(v);
    }

    public static double num(Map<String, Object> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try { return Double.parseDouble((String) v); } catch (Exception ignored) { }
        }
        return def;
    }

    public static boolean bool(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof Boolean ? (Boolean) v : "true".equals(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String k) {
        Object v = m == null ? null : m.get(k);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
