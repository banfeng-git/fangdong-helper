package com.rent.pc;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 云端同步窗口：填哪家的地址都能连（Cloudflare / 腾讯 EdgeOne / 阿里云 ESA 接口一致）。
 * 不登录也能正常记账，登录只用于多设备同步。
 */
public class SyncDialog extends JDialog {

    private final Model.Settings settings;
    private final Cloud cloud;
    private final Runnable onChanged;
    private final Runnable onPersist;

    private final JTextField fServer = new JTextField();
    private final JTextField fUser = new JTextField();
    private final JPasswordField fPass = new JPasswordField();
    private final JTextArea log = new JTextArea(8, 40);

    public SyncDialog(Frame owner, Model.Settings settings, Cloud cloud,
                      Runnable onChanged, Runnable onPersist) {
        super(owner, "云端同步", true);
        this.settings = settings;
        this.cloud = cloud;
        this.onChanged = onChanged;
        this.onPersist = onPersist;

        fServer.setText(settings.server);
        fUser.setText(settings.username);
        fPass.setText(settings.password);
        log.setEditable(false);
        log.setLineWrap(true);

        setSize(640, 520);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy = 0; c.gridx = 0; c.weightx = 0;
        form.add(new JLabel("服务端地址"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 3;
        form.add(fServer, c);
        c.gridwidth = 1;

        c.gridy = 1; c.gridx = 0; c.weightx = 0;
        form.add(new JLabel("账号"), c);
        c.gridx = 1; c.weightx = 1;
        form.add(fUser, c);
        c.gridx = 2; c.weightx = 0;
        form.add(new JLabel("密码"), c);
        c.gridx = 3; c.weightx = 1;
        form.add(fPass, c);

        c.gridy = 2; c.gridx = 1; c.gridwidth = 3;
        JLabel tip = new JLabel("<html><small>地址填到域名即可，例如 https://fd.xxx.workers.dev，会自动补 /api；"
                + "三家平台任选其一，账号互通只在同一台服务器内。</small></html>");
        tip.setForeground(new Color(0x66, 0x66, 0x66));
        form.add(tip, c);
        c.gridwidth = 1;

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        JButton reg = new JButton("注册");
        reg.addActionListener(e -> doRegister());
        JButton login = new JButton("登录");
        login.addActionListener(e -> doLogin());
        JButton logout = new JButton("退出登录");
        logout.addActionListener(e -> doLogout());
        JButton upload = new JButton("上传本地账单");
        upload.addActionListener(e -> doUpload());
        JButton download = new JButton("从云端下载");
        download.addActionListener(e -> doDownload());
        buttons.add(reg);
        buttons.add(login);
        buttons.add(logout);
        buttons.add(upload);
        buttons.add(download);

        JPanel top = new JPanel(new BorderLayout());
        top.add(form, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(log), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton close = new JButton("关闭");
        close.addActionListener(e -> {
            saveFields();
            dispose();
        });
        bottom.add(close);
        add(bottom, BorderLayout.SOUTH);

        append(settings.token == null || settings.token.isEmpty()
                ? "当前未登录，本地记账不受影响。"
                : "已保存登录状态，可直接上传/下载。");
    }

    private void saveFields() {
        settings.server = fServer.getText().trim();
        settings.username = fUser.getText().trim();
        settings.password = new String(fPass.getPassword());
        cloud.setBase(settings.server);
        cloud.setToken(settings.token);
        if (onPersist != null) onPersist.run();
    }

    private void append(String s) {
        log.append(s + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void doRegister() {
        saveFields();
        runAsync(() -> {
            Cloud.Res r = cloud.register(settings.username, new String(fPass.getPassword()));
            if (r.ok()) {
                settings.token = Json.str(r.data, "token", "");
                append("注册成功，已自动登录。");
                persist();
            } else {
                append("注册失败：" + r.msg);
            }
        });
    }

    private void doLogin() {
        saveFields();
        runAsync(() -> {
            Cloud.Res r = cloud.login(settings.username, new String(fPass.getPassword()));
            if (r.ok()) {
                settings.token = Json.str(r.data, "token", "");
                append("登录成功：" + Json.str(r.data, "username", settings.username));
                persist();
            } else {
                append("登录失败：" + r.msg);
            }
        });
    }

    private void doLogout() {
        saveFields();
        runAsync(() -> {
            try {
                if (!settings.token.isEmpty()) cloud.logout();
            } catch (Exception ignored) {
            }
            settings.token = "";
            append("已退出登录（云端账单仍在）。");
            persist();
        });
    }

    private void doUpload() {
        saveFields();
        runAsync(() -> {
            if (settings.token.isEmpty()) {
                append("请先登录再上传。");
                return;
            }
            List<Model.Bill> all = MainFrame.currentData.allBills();
            if (all.isEmpty()) {
                append("本地还没有账单可上传。");
                return;
            }
            Cloud.Res r = cloud.sync(all);
            if (!r.ok()) {
                append("上传失败：" + r.msg);
                return;
            }
            int n = (int) Json.num(r.data, "uploaded", 0);
            append("已上传 " + n + " 条，云端现有 " + (int) Json.num(r.data, "count", 0) + " 条。");
            MainFrame.mergeCloudBills(r.data);
            if (onChanged != null) onChanged.run();
            persist();
        });
    }

    private void doDownload() {
        saveFields();
        runAsync(() -> {
            if (settings.token.isEmpty()) {
                append("请先登录再下载。");
                return;
            }
            Cloud.Res r = cloud.list();
            if (!r.ok()) {
                append("下载失败：" + r.msg);
                return;
            }
            List<Map<String, Object>> bills = new ArrayList<>();
            for (Object o : Json.arr(r.data, "bills")) {
                if (o instanceof Map) bills.add(Json.obj(o));
            }
            SwingUtilities.invokeLater(() -> showCloudPicker(bills));
        });
    }

    /** 手动勾选：列出云端账单（含来源机型），可下载选中或删除选中 */
    private void showCloudPicker(List<Map<String, Object>> bills) {
        if (bills == null || bills.isEmpty()) {
            append("云端还没有账单。");
            return;
        }
        JDialog dlg = new JDialog(this, "选择云端账单（共 " + bills.size() + " 条）", true);
        dlg.setLayout(new BorderLayout(8, 8));

        JPanel list = new JPanel(new GridLayout(0, 1, 4, 4));
        List<JCheckBox> boxes = new ArrayList<>();
        for (Map<String, Object> b : bills) {
            String room = String.valueOf(b.getOrDefault("room", ""));
            String month = String.valueOf(b.getOrDefault("month", ""));
            String tenant = String.valueOf(b.getOrDefault("tenant", ""));
            double total = 0;
            for (String k : new String[]{"waterFee", "gasFee", "elecFee"}) {
                Object v = b.get(k);
                if (v instanceof Number) total += ((Number) v).doubleValue();
            }
            String device = String.valueOf(b.getOrDefault("device", ""));
            String label = room + " · " + month
                    + (tenant.isEmpty() ? "" : " · 租客 " + tenant)
                    + " · 合计 " + String.format("%.2f", total) + " 元"
                    + (device.isEmpty() ? "" : " · 来自 " + device);
            JCheckBox cb = new JCheckBox(label);
            boxes.add(cb);
            list.add(cb);
        }
        dlg.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        JButton downloadSel = new JButton("下载选中");
        JButton deleteSel = new JButton("删除选中");
        JButton cancel = new JButton("取消");
        btns.add(downloadSel);
        btns.add(deleteSel);
        btns.add(cancel);
        dlg.add(btns, BorderLayout.SOUTH);

        downloadSel.addActionListener(e -> {
            List<Map<String, Object>> picked = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isSelected()) picked.add(bills.get(i));
            }
            if (picked.isEmpty()) {
                append("没有勾选任何账单。");
                return;
            }
            dlg.dispose();
            runAsync(() -> {
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("bills", picked);
                int n = MainFrame.mergeCloudBills(payload);
                append("已下载 " + n + " 条账单到本机。");
                if (onChanged != null) onChanged.run();
                persist();
            });
        });

        deleteSel.addActionListener(e -> {
            List<String> uids = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                if (boxes.get(i).isSelected()) {
                    Object uid = bills.get(i).get("uid");
                    if (uid != null && !String.valueOf(uid).isEmpty()) uids.add(String.valueOf(uid));
                }
            }
            if (uids.isEmpty()) {
                append("没有勾选任何账单。");
                return;
            }
            int ok = JOptionPane.showConfirmDialog(dlg,
                    "确定从云端删除勾选的 " + uids.size() + " 条账单？本地副本不会被删除。",
                    "确认删除", JOptionPane.YES_NO_OPTION);
            if (ok != JOptionPane.YES_OPTION) return;
            dlg.dispose();
            runAsync(() -> {
                int done = 0, fail = 0;
                for (String uid : uids) {
                    try {
                        Cloud.Res r = cloud.delete(uid);
                        if (r.ok()) done++;
                        else fail++;
                    } catch (Exception ex) {
                        fail++;
                    }
                }
                append("已从云端删除 " + done + " 条" + (fail > 0 ? "，失败 " + fail + " 条。" : "。"));
            });
        });

        cancel.addActionListener(e -> dlg.dispose());

        dlg.setSize(640, 420);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    /** 网络请求放后台线程，避免界面卡死 */
    private void runAsync(Task task) {
        setBusy(true);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    task.run();
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> append("出错了：" + e.getMessage()));
                }
                return null;
            }

            @Override protected void done() {
                setBusy(false);
            }
        }.execute();
    }

    private void setBusy(boolean busy) {
        setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void persist() {
        if (onPersist != null) SwingUtilities.invokeLater(onPersist);
    }

    interface Task {
        void run() throws Exception;
    }
}
