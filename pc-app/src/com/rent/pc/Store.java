package com.rent.pc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本地数据存储：~/.fangdong-helper/data.json */
public final class Store {

    private final File dir;
    private final File file;

    public Store() {
        String home = System.getProperty("user.home");
        dir = new File(home, ".fangdong-helper");
        file = new File(dir, "data.json");
    }

    public File getDir() {
        return dir;
    }

    public Model.Data load() {
        Model.Data data = new Model.Data();
        if (!file.exists()) return data;
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = Json.parseObject(text);

            Map<String, Object> st = Json.obj(root.get("settings"));
            data.settings.waterPrice = Json.num(st, "waterPrice", 3.0);
            data.settings.elecPrice = Json.num(st, "elecPrice", 1.0);
            data.settings.gasPrice = Json.num(st, "gasPrice", 2.5);
            data.settings.server = Json.str(st, "server", "");
            data.settings.username = Json.str(st, "username", "");
            data.settings.token = Json.str(st, "token", "");
            data.settings.password = Json.str(st, "password", "");

            for (Object o : Json.arr(root, "rooms")) {
                Map<String, Object> rm = Json.obj(o);
                Model.Room room = new Model.Room();
                room.id = Json.str(rm, "id", UUID.randomUUID().toString());
                room.name = Json.str(rm, "name", "");
                room.tenant = Json.str(rm, "tenant", "");
                room.note = Json.str(rm, "note", "");
                for (Object bo : Json.arr(rm, "bills")) {
                    room.bills.add(Model.billFromJson(Json.obj(bo)));
                }
                data.rooms.add(room);
            }
        } catch (Exception e) {
            System.err.println("读取数据失败：" + e.getMessage());
        }
        return data;
    }

    public void save(Model.Data data) throws IOException {
        if (!dir.exists()) dir.mkdirs();
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> st = new LinkedHashMap<>();
        st.put("waterPrice", data.settings.waterPrice);
        st.put("elecPrice", data.settings.elecPrice);
        st.put("gasPrice", data.settings.gasPrice);
        st.put("server", data.settings.server);
        st.put("username", data.settings.username);
        st.put("token", data.settings.token);
        st.put("password", data.settings.password);
        root.put("settings", st);

        List<Object> rooms = new ArrayList<>();
        for (Model.Room r : data.rooms) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("id", r.id);
            rm.put("name", r.name);
            rm.put("tenant", r.tenant);
            rm.put("note", r.note);
            List<Object> bills = new ArrayList<>();
            for (Model.Bill b : r.bills) bills.add(Model.billToJson(b));
            rm.put("bills", bills);
            rooms.add(rm);
        }
        root.put("rooms", rooms);

        Files.write(file.toPath(), Json.stringify(root).getBytes(StandardCharsets.UTF_8));
    }
}
