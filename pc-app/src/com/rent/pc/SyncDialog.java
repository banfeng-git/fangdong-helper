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
            int n = MainFrame.mergeCloudBills(r.data);
            append("云端共 " + (int) Json.num(r.data, "count", 0) + " 条，已合并 " + n + " 条到本地。");
            if (onChanged != null) onChanged.run();
            persist();
        });
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
