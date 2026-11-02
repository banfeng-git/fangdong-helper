package com.rent.pc;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** 账单编辑窗口：水/电/燃气任意加减表、其他收费、实时表格图预览 */
public class BillDialog extends JDialog {

    private final Model.Settings settings;
    private final Model.Bill bill;

    private final JTextField fRoom = new JTextField();
    private final JTextField fTenant = new JTextField();
    private final JTextField fMonth = new JTextField();
    private final JTextField fNote = new JTextField();

    private final MeterModel waterModel;
    private final MeterModel elecModel;
    private final MeterModel gasModel;
    private final ExtraModel extraModel = new ExtraModel();

    private final JLabel preview = new JLabel();
    private boolean saved = false;

    public BillDialog(Frame owner, Model.Bill src, Model.Settings settings, String roomName) {
        super(owner, src.uid == null || src.uid.isEmpty() ? "新建账单" : "编辑账单", true);
        this.settings = settings;
        this.bill = src.copy();

        fRoom.setText(bill.room.isEmpty() ? roomName : bill.room);
        fTenant.setText(bill.tenant);
        fMonth.setText(bill.month.isEmpty() ? currentMonth() : bill.month);
        fNote.setText(bill.note);

        waterModel = new MeterModel(bill.waterMeters, settings.waterPrice);
        elecModel = new MeterModel(bill.meters, settings.elecPrice);
        gasModel = new MeterModel(bill.gasMeters, settings.gasPrice);
        extraModel.setExtras(bill.extras);

        setSize(1180, 780);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout());

        add(buildForm(), BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        refreshPreview();
    }

    public boolean isSaved() {
        return saved;
    }

    public Model.Bill getBill() {
        return bill;
    }

    // ---------------- 界面 ----------------

    private JComponent buildForm() {
        JPanel top = new JPanel(new GridBagLayout());
        top.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        addField(top, c, 0, 0, "房间", fRoom, "租客", fTenant);
        addField(top, c, 1, 0, "月份", fMonth, "备注", fNote);

        JPanel meters = new JPanel(new GridBagLayout());
        meters.setBorder(BorderFactory.createTitledBorder("表计（可多块，起止码自动算用量和费用）"));
        GridBagConstraints mc = new GridBagConstraints();
        mc.insets = new Insets(2, 4, 6, 4);
        mc.fill = GridBagConstraints.BOTH;
        mc.weightx = 1;

        mc.gridy = 0; mc.gridx = 0;
        meters.add(new JLabel("水表（" + Model.money(settings.waterPrice) + " 元/吨）"), mc);
        mc.gridy = 1;
        meters.add(meterPanel(waterModel, "水表"), mc);

        mc.gridy = 2; mc.gridx = 0;
        meters.add(new JLabel("电表（" + Model.money(settings.elecPrice) + " 元/度）"), mc);
        mc.gridy = 3;
        meters.add(meterPanel(elecModel, "电表"), mc);

        mc.gridy = 4;
        meters.add(new JLabel("燃气表（" + Model.money(settings.gasPrice) + " 元/方）"), mc);
        mc.gridy = 5;
        meters.add(meterPanel(gasModel, "燃气表"), mc);

        mc.gridy = 6;
        meters.add(new JLabel("其他收费（卫生费、网费等；勾选“按用量”会作为一列计入表格）"), mc);
        mc.gridy = 7; mc.weighty = 1;
        meters.add(extraPanel(), mc);

        JScrollPane metersScroll = new JScrollPane(meters);
        metersScroll.setBorder(BorderFactory.createEmptyBorder());
        metersScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel center = new JPanel(new GridLayout(1, 2, 10, 0));
        center.add(metersScroll);

        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createTitledBorder("表格图片预览"));
        preview.setHorizontalAlignment(SwingConstants.CENTER);
        JScrollPane sp = new JScrollPane(preview);
        right.add(sp, BorderLayout.CENTER);
        center.add(right);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        return root;
    }

    private void addField(JPanel p, GridBagConstraints c, int row, int col,
                          String l1, JTextField f1, String l2, JTextField f2) {
        c.gridy = row; c.gridx = col * 3; c.weightx = 0;
        p.add(new JLabel(l1), c);
        c.gridx = col * 3 + 1; c.weightx = 1;
        p.add(f1, c);
        c.gridx = col * 3 + 2; c.weightx = 0;
        p.add(new JLabel("  " + l2), c);
        c.gridx = col * 3 + 3; c.weightx = 1;
        p.add(f2, c);
    }

    private JComponent meterPanel(MeterModel model, String prefix) {
        JTable table = new JTable(model);
        table.setRowHeight(26);
        TableColumn c0 = table.getColumnModel().getColumn(0);
        c0.setPreferredWidth(110);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(460, 108));

        JButton add = new JButton("添加" + prefix);
        add.addActionListener(e -> {
            int idx = model.getRowCount() + 1;
            model.addMeter(new Model.Meter(prefix + idx, 0, 0, 0, 0));
            refreshPreview();
        });
        JButton del = new JButton("删除选中");
        del.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                model.removeMeter(r);
                refreshPreview();
            }
        });
        model.setListener(this::refreshPreview);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(add);
        btns.add(del);

        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.add(sp, BorderLayout.CENTER);
        wrap.add(btns, BorderLayout.SOUTH);
        return wrap;
    }

    private JComponent extraPanel() {
        JTable table = new JTable(extraModel);
        table.setRowHeight(26);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(460, 96));

        JButton add = new JButton("添加收费项");
        add.addActionListener(e -> {
            extraModel.addExtra(new Model.Extra("卫生费", false, 0, 10));
            refreshPreview();
        });
        JButton del = new JButton("删除选中");
        del.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                extraModel.removeExtra(r);
                refreshPreview();
            }
        });
        extraModel.setListener(this::refreshPreview);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        btns.add(add);
        btns.add(del);

        JPanel wrap = new JPanel(new BorderLayout(0, 4));
        wrap.add(sp, BorderLayout.CENTER);
        wrap.add(btns, BorderLayout.SOUTH);
        return wrap;
    }

    private JComponent buildButtons() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton refresh = new JButton("刷新预览");
        refresh.addActionListener(e -> refreshPreview());
        JButton save = new JButton("保存");
        save.addActionListener(e -> onSave());
        JButton cancel = new JButton("取消");
        cancel.addActionListener(e -> dispose());
        p.add(refresh);
        p.add(cancel);
        p.add(save);
        getRootPane().setDefaultButton(save);
        return p;
    }

    // ---------------- 逻辑 ----------------

    private void onSave() {
        Model.Bill out = collect();
        bill.uid = out.uid;
        bill.room = out.room;
        bill.tenant = out.tenant;
        bill.month = out.month;
        bill.note = out.note;
        bill.waterMeters = out.waterMeters;
        bill.gasMeters = out.gasMeters;
        bill.meters = out.meters;
        bill.extras = out.extras;
        bill.updatedAt = System.currentTimeMillis();
        saved = true;
        dispose();
    }

    private Model.Bill collect() {
        Model.Bill b = new Model.Bill();
        b.uid = bill.uid;
        b.room = fRoom.getText().trim();
        b.tenant = fTenant.getText().trim();
        b.month = fMonth.getText().trim();
        b.note = fNote.getText().trim();
        b.waterMeters = waterModel.getMeters();
        b.meters = elecModel.getMeters();
        b.gasMeters = gasModel.getMeters();
        b.extras = extraModel.getExtras();
        b.updatedAt = System.currentTimeMillis();
        return b;
    }

    private void refreshPreview() {
        try {
            Model.Bill b = collect();
            if (b.month.isEmpty() && b.room.isEmpty()) {
                preview.setIcon(null);
                preview.setText("填写内容后这里会显示表格图片");
                return;
            }
            BufferedImage img = BillImage.build(b, settings);
            int panelW = Math.max(preview.getWidth(), 420);
            preview.setIcon(new ImageIcon(BillImage.scaled(img, panelW - 20)));
        } catch (Exception e) {
            preview.setText("预览失败：" + e.getMessage());
        }
        revalidate();
    }

    private static String currentMonth() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-"
                + String.format("%02d", c.get(java.util.Calendar.MONTH) + 1);
    }

    // ---------------- 表模型 ----------------

    /** 名称 / 起码 / 止码 可编辑，用量与费用自动算 */
    static class MeterModel extends AbstractTableModel {
        private final List<Model.Meter> meters = new ArrayList<>();
        private final double price;
        private Runnable listener;

        MeterModel(List<Model.Meter> src, double price) {
            this.price = price;
            for (Model.Meter m : src) meters.add(new Model.Meter(m.label, m.start, m.end, m.usage, m.fee));
        }

        void setListener(Runnable r) {
            this.listener = r;
        }

        String[] cols = {"名称", "起码", "止码", "用量", "费用(元)"};

        @Override public int getRowCount() { return meters.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public boolean isCellEditable(int r, int c) { return c <= 2; }

        @Override public Object getValueAt(int r, int c) {
            Model.Meter m = meters.get(r);
            switch (c) {
                case 0: return m.label;
                case 1: return trim(m.start);
                case 2: return trim(m.end);
                case 3: return trim(m.usage) + " " + unit();
                case 4: return Model.money(m.fee);
                default: return "";
            }
        }

        private String unit() {
            return "吨/度/方".length() > 0 && price == 0 ? "" : "";
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Model.Meter m = meters.get(r);
            String s = v == null ? "" : v.toString().trim().replace("，", "").replace(",", "");
            if (c == 0) {
                m.label = s;
            } else if (c == 1) {
                m.start = parse(s);
            } else if (c == 2) {
                m.end = parse(s);
            }
            m.usage = Math.max(0, Model.round2(m.end - m.start));
            m.fee = Model.round2(m.usage * price);
            fireTableRowsUpdated(r, r);
            if (listener != null) listener.run();
        }

        private static double parse(String s) {
            try {
                return Double.parseDouble(s);
            } catch (Exception e) {
                return 0;
            }
        }

        private static String trim(double d) {
            return Model.usage(d);
        }

        void addMeter(Model.Meter m) {
            meters.add(m);
            fireTableDataChanged();
        }

        void removeMeter(int i) {
            if (i >= 0 && i < meters.size()) {
                meters.remove(i);
                fireTableDataChanged();
            }
        }

        List<Model.Meter> getMeters() {
            List<Model.Meter> out = new ArrayList<>();
            for (Model.Meter m : meters) out.add(new Model.Meter(m.label, m.start, m.end, m.usage, m.fee));
            return out;
        }
    }

    /** 其他收费：名称 / 按用量 / 用量 / 金额 */
    static class ExtraModel extends AbstractTableModel {
        private final List<Model.Extra> extras = new ArrayList<>();
        private Runnable listener;

        String[] cols = {"名称", "按用量", "用量", "金额(元)"};

        void setListener(Runnable r) {
            this.listener = r;
        }

        void setExtras(List<Model.Extra> list) {
            extras.clear();
            for (Model.Extra e : list) extras.add(new Model.Extra(e.name, e.usageBased, e.usage, e.amount));
            fireTableDataChanged();
        }

        List<Model.Extra> getExtras() {
            List<Model.Extra> out = new ArrayList<>();
            for (Model.Extra e : extras) out.add(new Model.Extra(e.name, e.usageBased, e.usage, e.amount));
            return out;
        }

        @Override public int getRowCount() { return extras.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return c == 1 ? Boolean.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            Model.Extra e = extras.get(r);
            switch (c) {
                case 0: return e.name;
                case 1: return e.usageBased;
                case 2: return Model.usage(e.usage);
                case 3: return Model.money(e.amount);
                default: return "";
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Model.Extra e = extras.get(r);
            String s = v == null ? "" : v.toString().trim().replace(",", "");
            switch (c) {
                case 0: e.name = s; break;
                case 1: e.usageBased = v instanceof Boolean ? (Boolean) v : "true".equals(s); break;
                case 2: try { e.usage = Double.parseDouble(s); } catch (Exception ignored) { e.usage = 0; } break;
                case 3: try { e.amount = Model.round2(Double.parseDouble(s)); } catch (Exception ignored) { e.amount = 0; } break;
                default: break;
            }
            fireTableRowsUpdated(r, r);
            if (listener != null) listener.run();
        }

        void addExtra(Model.Extra e) {
            extras.add(e);
            fireTableDataChanged();
        }

        void removeExtra(int i) {
            if (i >= 0 && i < extras.size()) {
                extras.remove(i);
                fireTableDataChanged();
            }
        }
    }
}
