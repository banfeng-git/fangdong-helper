package com.rent.pc;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 主窗口 */
public class MainFrame extends JFrame {

    /** 供同步窗口合并数据使用（单实例应用，静态引用最省事） */
    static Model.Data currentData;

    private final Store store;
    private final Cloud cloud = new Cloud();
    private Model.Data data;

    private final DefaultListModel<Model.Room> roomModel = new DefaultListModel<>();
    private final JList<Model.Room> roomList = new JList<>(roomModel);
    private final BillTableModel billModel = new BillTableModel();
    private final JTable billTable = new JTable(billModel);
    private final JLabel status = new JLabel(" ");
    private final JLabel loginState = new JLabel("未登录");

    public MainFrame() {
        super("房东小助手 · 电脑版 2.10");

        store = new Store();
        data = store.load();
        currentData = data;
        cloud.setBase(data.settings.server);
        cloud.setToken(data.settings.token);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1120, 720);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        add(buildToolBar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        roomList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Model.Room) {
                    Model.Room r = (Model.Room) value;
                    setText(r.display() + "　(" + r.bills.size() + " 张)");
                }
                return this;
            }
        });
        roomList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshBills();
        });
        billTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editBill();
            }
        });

        refreshRooms();
        updateLoginState();
    }

    // ---------------- 界面搭建 ----------------

    private JComponent buildToolBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        bar.add(button("＋ 新建房间", e -> newRoom()));
        bar.add(button("－ 删除房间", e -> deleteRoom()));
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(button("＋ 新建账单", e -> newBill()));
        bar.add(button("编辑账单", e -> editBill()));
        bar.add(button("下月账单", e -> nextMonthBill()));
        bar.add(button("删除账单", e -> deleteBill()));
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(button("生成账单图片", e -> exportImage()));
        bar.add(button("复制分享文本", e -> copyText()));
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(button("单价设置", e -> openSettings()));
        bar.add(button("云端同步", e -> openSync()));
        return bar;
    }

    private JButton button(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.addActionListener(l);
        return b;
    }

    private JComponent buildBody() {
        JPanel left = new JPanel(new BorderLayout());
        left.setPreferredSize(new Dimension(240, 0));
        left.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("房间"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        left.add(new JScrollPane(roomList), BorderLayout.CENTER);

        String[] cols = {"月份", "房间", "租客", "其他收费", "总费用", "更新时间"};
        billTable.setRowHeight(28);
        billTable.getTableHeader().setReorderingAllowed(false);
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("账单"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        right.add(new JScrollPane(billTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(250);
        return split;
    }

    private JComponent buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        p.add(status, BorderLayout.WEST);
        loginState.setForeground(new Color(0x2F, 0x6B, 0x4F));
        p.add(loginState, BorderLayout.EAST);
        return p;
    }

    // ---------------- 数据刷新 ----------------

    private void refreshRooms() {
        Model.Room selected = roomList.getSelectedValue();
        roomModel.clear();
        for (Model.Room r : data.rooms) roomModel.addElement(r);
        if (selected != null) {
            for (int i = 0; i < roomModel.size(); i++) {
                if (roomModel.get(i).id.equals(selected.id)) {
                    roomList.setSelectedIndex(i);
                    break;
                }
            }
        }
        if (roomList.getSelectedIndex() < 0 && roomModel.size() > 0) roomList.setSelectedIndex(0);
        refreshBills();
        setStatus("共 " + data.rooms.size() + " 个房间、" + data.allBills().size() + " 张账单");
    }

    private void refreshBills() {
        Model.Room r = roomList.getSelectedValue();
        billModel.setRoom(r);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void updateLoginState() {
        String server = data.settings.server;
        boolean logged = data.settings.token != null && !data.settings.token.isEmpty();
        if (logged) {
            loginState.setText("云端：已登录 " + data.settings.username + "（" + server + "）");
        } else {
            loginState.setText(server == null || server.isEmpty() ? "云端：未配置（不影响本地记账）" : "云端：未登录");
        }
    }

    private void persist() {
        try {
            store.save(data);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "保存失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------- 房间操作 ----------------

    private void newRoom() {
        String name = JOptionPane.showInputDialog(this, "房间名称（如 526）", "新建房间", JOptionPane.PLAIN_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty()) return;
        String tenant = JOptionPane.showInputDialog(this, "租客姓名（可留空）", "新建房间", JOptionPane.PLAIN_MESSAGE);
        Model.Room r = new Model.Room();
        r.id = UUID.randomUUID().toString();
        r.name = name;
        r.tenant = tenant == null ? "" : tenant.trim();
        data.rooms.add(r);
        persist();
        refreshRooms();
        roomList.setSelectedIndex(roomModel.size() - 1);
        setStatus("已创建房间 " + name);
    }

    private void deleteRoom() {
        Model.Room r = roomList.getSelectedValue();
        if (r == null) {
            setStatus("先选一个房间");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "删除房间「" + r.name + "」及其中 " + r.bills.size() + " 张账单？此操作不可撤销。",
                "确认删除", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        data.rooms.remove(r);
        persist();
        refreshRooms();
        setStatus("已删除房间 " + r.name);
    }

    // ---------------- 账单操作 ----------------

    private Model.Room selectedRoom() {
        return roomList.getSelectedValue();
    }

    private Model.Bill selectedBill() {
        int row = billTable.getSelectedRow();
        if (row < 0) return null;
        return billModel.get(row);
    }

    private void newBill() {
        Model.Room r = selectedRoom();
        if (r == null) {
            setStatus("先建一个房间");
            return;
        }
        Model.Bill b = new Model.Bill();
        b.uid = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        b.room = r.name;
        b.tenant = r.tenant;
        b.month = currentMonth();
        b.waterMeters.add(new Model.Meter("水表1", 0, 0, 0, 0));
        b.meters.add(new Model.Meter("电表1", 0, 0, 0, 0));
        BillDialog d = new BillDialog(this, b, data.settings, r.name);
        d.setVisible(true);
        if (!d.isSaved()) return;
        r.bills.add(d.getBill());
        persist();
        refreshRooms();
        setStatus("已保存账单");
    }

    private void editBill() {
        Model.Bill b = selectedBill();
        Model.Room r = selectedRoom();
        if (b == null || r == null) {
            setStatus("先选一张账单");
            return;
        }
        BillDialog d = new BillDialog(this, b, data.settings, r.name);
        d.setVisible(true);
        if (!d.isSaved()) return;
        int idx = r.bills.indexOf(b);
        if (idx >= 0) r.bills.set(idx, d.getBill());
        persist();
        refreshRooms();
        setStatus("已更新账单");
    }

    private void nextMonthBill() {
        Model.Bill b = selectedBill();
        Model.Room r = selectedRoom();
        if (b == null || r == null) return;
        Model.Bill nb = b.copyForNextMonth();
        nb.uid = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        nb.room = r.name;
        nb.updatedAt = System.currentTimeMillis();
        r.bills.add(nb);
        persist();
        refreshRooms();
        setStatus("已生成下月账单（止码已转为起码）");
    }

    private void deleteBill() {
        Model.Bill b = selectedBill();
        Model.Room r = selectedRoom();
        if (b == null || r == null) return;
        int ok = JOptionPane.showConfirmDialog(this, "删除这张账单？", "确认", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        r.bills.remove(b);
        persist();
        refreshRooms();
        setStatus("已删除账单");
    }

    private void exportImage() {
        Model.Bill b = selectedBill();
        if (b == null) {
            setStatus("先选一张账单");
            return;
        }
        try {
            File dir = new File(store.getDir(), "账单图片");
            File f = BillImage.save(b, data.settings, dir);
            setStatus("图片已保存：" + f.getAbsolutePath());
            int open = JOptionPane.showConfirmDialog(this,
                    "图片已保存到：\n" + f.getAbsolutePath() + "\n\n要现在打开所在文件夹吗？",
                    "生成成功", JOptionPane.YES_NO_OPTION);
            if (open == JOptionPane.YES_OPTION && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "生成失败：" + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copyText() {
        Model.Bill b = selectedBill();
        if (b == null) {
            setStatus("先选一张账单");
            return;
        }
        String text = BillImage.buildText(b, data.settings);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        JTextArea area = new JTextArea(text, 16, 42);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "分享文本（已复制到剪贴板）",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void openSettings() {
        JPanel p = new JPanel(new GridLayout(0, 2, 8, 8));
        JTextField w = new JTextField(Model.money(data.settings.waterPrice));
        JTextField e = new JTextField(Model.money(data.settings.elecPrice));
        JTextField g = new JTextField(Model.money(data.settings.gasPrice));
        p.add(new JLabel("水费单价（元/吨）")); p.add(w);
        p.add(new JLabel("电费单价（元/度）")); p.add(e);
        p.add(new JLabel("燃气单价（元/方）")); p.add(g);
        int ok = JOptionPane.showConfirmDialog(this, p, "单价设置", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        data.settings.waterPrice = parse(w.getText(), data.settings.waterPrice);
        data.settings.elecPrice = parse(e.getText(), data.settings.elecPrice);
        data.settings.gasPrice = parse(g.getText(), data.settings.gasPrice);
        persist();
        setStatus("单价已更新（已保存的账单不会自动重算）");
    }

    private static double parse(String s, double def) {
        try {
            return Model.round2(Double.parseDouble(s.trim()));
        } catch (Exception e) {
            return def;
        }
    }

    private void openSync() {
        SyncDialog d = new SyncDialog(this, data.settings, cloud,
                () -> {
                    updateLoginState();
                    refreshRooms();
                    SwingUtilities.invokeLater(this::refreshBills);
                },
                () -> {
                    persist();
                    updateLoginState();
                });
        d.setVisible(true);
        updateLoginState();
        refreshRooms();
    }

    // ---------------- 云端合并 ----------------

    /** 把云端返回的账单合并进本地（按 uid，新时间覆盖旧时间），返回合并条数 */
    static int mergeCloudBills(Map<String, Object> dataMap) {
        if (currentData == null || dataMap == null) return 0;
        List<Map<String, Object>> incoming = new ArrayList<>();
        for (Object o : Json.arr(dataMap, "bills")) {
            if (o instanceof Map) incoming.add(Json.obj(o));
        }
        Set<String> seen = new HashSet<>();
        int merged = 0;
        for (Map<String, Object> m : incoming) {
            Model.Bill cb = Model.billFromJson(m);
            if (cb.uid.isEmpty()) continue;
            seen.add(cb.uid);
            merged++;

            Model.Bill local = currentData.findBill(cb.uid);
            if (local == null) {
                Model.Room room = findOrCreateRoom(currentData, cb.room);
                room.bills.add(cb);
            } else if (cb.updatedAt > local.updatedAt) {
                local.room = cb.room;
                local.tenant = cb.tenant;
                local.month = cb.month;
                local.note = cb.note;
                local.waterMeters = cb.waterMeters;
                local.gasMeters = cb.gasMeters;
                local.meters = cb.meters;
                local.extras = cb.extras;
                local.updatedAt = cb.updatedAt;
            }
        }
        return merged;
    }

    private static Model.Room findOrCreateRoom(Model.Data d, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "未命名房间";
        for (Model.Room r : d.rooms) if (r.name.equals(n)) return r;
        Model.Room r = new Model.Room();
        r.id = UUID.randomUUID().toString();
        r.name = n;
        d.rooms.add(r);
        return r;
    }

    private static String currentMonth() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + String.format("%02d", c.get(java.util.Calendar.MONTH) + 1);
    }

    // ---------------- 表格模型 ----------------

    static class BillTableModel extends AbstractTableModel {
        private Model.Room room;
        private final String[] cols = {"月份", "房间", "租客", "其他收费", "总费用(元)", "更新时间"};

        void setRoom(Model.Room r) {
            this.room = r;
            fireTableDataChanged();
        }

        Model.Bill get(int row) {
            if (room == null || row < 0 || row >= room.bills.size()) return null;
            return room.bills.get(row);
        }

        @Override public int getRowCount() { return room == null ? 0 : room.bills.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Object getValueAt(int r, int c) {
            Model.Bill b = room.bills.get(r);
            switch (c) {
                case 0: return b.month;
                case 1: return b.room;
                case 2: return b.tenant;
                case 3: return Model.money(b.otherFee());
                case 4: return Model.money(b.total());
                case 5: return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(b.updatedAt));
                default: return "";
            }
        }
    }
}
