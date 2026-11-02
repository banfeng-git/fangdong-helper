package com.rent.utility;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.rent.utility.data.Bill;
import com.rent.utility.data.DbHelper;
import com.rent.utility.data.FeeItem;
import com.rent.utility.util.BillCalculator;
import com.rent.utility.util.BillShare;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 账单新建/编辑页。
 * 水表、燃气表、电表都支持多块（每类可点＋添加，可单独删除），每块填“起码/止码”，
 * 用量=止码−起码自动计算，水费/燃气费/电费按收费标准自动计算；其他费用明细可自定义；
 * 支持分享到微信（文+表格图）、保存表格图片到相册、复制文本；支持一键“记下月”。
 */
public class BillEditFragment extends Fragment {

    public interface SaveListener {
        void onBillSaved();

        void onBillDeleted();
    }

    private static final int REQ_STORAGE = 100;
    private static final int TYPE_WATER = 0;
    private static final int TYPE_GAS = 1;
    private static final int TYPE_ELEC = 2;

    private SaveListener saveListener;
    private DbHelper db;

    private long billId = -1;
    private String presetRoom = null;
    private Bill bill = new Bill();
    private List<FeeItem> feeItems = new ArrayList<>();
    private Map<String, FeeItem> feeByName = new HashMap<>();

    private MaterialToolbar toolbar;
    private EditText inMonth, inRoom, inTenant, inNote;
    private TextView txtTotal;
    private LinearLayout extraContainer;
    private LinearLayout meterContainer, waterSection, gasSection, waterContainer, gasContainer;
    private MaterialButton btnDelete, btnAddMeter, btnAddWater, btnAddGas;

    /** 动态表行（水/燃气/电表通用） */
    private static class MeterRowHolder {
        View root;
        TextView labelTv;
        EditText startEt, endEt, usageEt, feeEt;
        TextInputLayout usageLayout;
        ImageButton remove;
        Bill.Meter model;
    }

    private final List<MeterRowHolder> waterRows = new ArrayList<>();
    private final List<MeterRowHolder> gasRows = new ArrayList<>();
    private final List<MeterRowHolder> meterRows = new ArrayList<>();

    private static class RowHolder {
        View root;
        EditText usageEt;
        EditText amountEt;
        TextView unitTv;
        Bill.ExtraAmount model;
    }

    private final List<RowHolder> rows = new ArrayList<>();

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof SaveListener) {
            saveListener = (SaveListener) context;
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            billId = getArguments().getLong("billId", -1);
            presetRoom = getArguments().getString("presetRoom");
        }
    }

    /**
     * 页面每次回到前台都重新加载“收费项目与标准”的最新单价并同步到费用计算：
     * 用户在设置里改价后回到本页，新价格立即生效（无需重新进入）。
     * 新建账单自动按最新单价重算各表费用；编辑旧账单只刷新单价、不覆盖已保存的历史费用。
     */
    @Override
    public void onResume() {
        super.onResume();
        try {
            refreshFeesFromDb();
        } catch (Exception ignored) {
        }
    }

    private void refreshFeesFromDb() {
        if (db == null) return;
        List<FeeItem> items = db.getFeeItems();
        if (items == null) return;
        feeItems = items;
        feeByName.clear();
        for (FeeItem f : items) {
            if (f != null && f.name != null && !f.name.trim().isEmpty()) {
                feeByName.put(f.name, f);
            }
        }
        // 新建账单：按最新单价重算全部表费用与按用量计费的其他费用
        if (billId <= 0) {
            recalcAllFeesWithLatestPrices();
        }
    }

    /** 按最新收费标准重算：水表/燃气表/各电表费用、按用量计费的其他费用、合计 */
    private void recalcAllFeesWithLatestPrices() {
        for (MeterRowHolder h : waterRows) {
            recalcMeterFeeWithLatest(h, "水费");
        }
        for (MeterRowHolder h : gasRows) {
            recalcMeterFeeWithLatest(h, "燃气费");
        }
        for (MeterRowHolder h : meterRows) {
            recalcMeterFeeWithLatest(h, elecFeeName());
        }
        for (RowHolder h : rows) {
            if (h.model != null && h.model.usageBased) {
                FeeItem f = feeByName.get(h.model.name);
                if (f != null && f.enabled && f.type == FeeItem.TYPE_USAGE) {
                    h.model.amount = BillCalculator.round2(h.model.usage * f.unitPrice);
                    if (h.amountEt != null) h.amountEt.setText(BillCalculator.money(h.model.amount));
                }
            }
        }
        updateWaterFee();
        updateGasFee();
        updateElecFee();
        updateTotal();
    }

    private void recalcMeterFeeWithLatest(MeterRowHolder h, String feeName) {
        if (h == null || h.model == null) return;
        FeeItem f = feeByName.get(feeName);
        if (f != null && f.enabled && f.type == FeeItem.TYPE_USAGE) {
            h.model.fee = BillCalculator.round2(h.model.usage * f.unitPrice);
            if (h.feeEt != null) h.feeEt.setText(BillCalculator.money(h.model.fee));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v;
        try {
            v = createContentView(inflater, container);
        } catch (Exception e) {
            e.printStackTrace();
            // 任何初始化异常都不让 App 崩溃：提示后返回空内容视图
            String msg = e.toString();
            if (msg.length() > 300) msg = msg.substring(0, 300);
            TextView tv = new TextView(requireContext());
            tv.setText("页面加载失败\n" + msg + "\n\n请把这段文字截图反馈给开发者");
            tv.setPadding(48, 48, 48, 48);
            try {
                Toast.makeText(requireContext(), "页面加载失败：" + e.getClass().getSimpleName(),
                        Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
            return tv;
        }
        return v;
    }

    private View createContentView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        View v = inflater.inflate(R.layout.fragment_bill_edit, container, false);
        db = new DbHelper(requireContext());
        feeItems = db.getFeeItems();
        for (FeeItem f : feeItems) {
            if (f != null && f.name != null && !f.name.trim().isEmpty()) {
                feeByName.put(f.name, f);
            }
        }

        bindViews(v);
        setupToolbar();
        setupButtons(v);

        if (billId > 0) {
            Bill loaded = db.getBill(billId);
            if (loaded != null) {
                bill = loaded;
                billId = loaded.id;
            }
        } else {
            bill.month = new SimpleDateFormat("yyyy-MM", Locale.CHINA).format(new Date());
            if (presetRoom != null && !presetRoom.isEmpty()) {
                bill.room = presetRoom;
            }
            bill.extras = BillCalculator.defaultExtras(feeItems);
            // 新建账单：水/燃气/电表默认都不显示，只显示“＋添加”按钮
            bill.waterMeters = new ArrayList<>();
            bill.gasMeters = new ArrayList<>();
            bill.meters = new ArrayList<>();
        }

        fillFields();
        updateSectionsVisibility();
        renderExtras();
        setupWatchers();
        updateTotal();
        return v;
    }

    private void bindViews(View v) {
        toolbar = v.findViewById(R.id.toolbar_edit);
        inMonth = v.findViewById(R.id.in_month);
        inRoom = v.findViewById(R.id.in_room);
        inTenant = v.findViewById(R.id.in_tenant);
        inNote = v.findViewById(R.id.in_note);
        txtTotal = v.findViewById(R.id.txt_total);
        extraContainer = v.findViewById(R.id.extra_container);
        meterContainer = v.findViewById(R.id.meter_container);
        waterSection = v.findViewById(R.id.water_section);
        gasSection = v.findViewById(R.id.gas_section);
        waterContainer = v.findViewById(R.id.water_container);
        gasContainer = v.findViewById(R.id.gas_container);
        btnDelete = v.findViewById(R.id.btn_delete);
        btnAddMeter = v.findViewById(R.id.btn_add_meter);
        btnAddWater = v.findViewById(R.id.btn_add_water);
        btnAddGas = v.findViewById(R.id.btn_add_gas);
    }

    private void setupToolbar() {
        toolbar.setTitle(billId > 0 ? "编辑账单" : "新建账单");
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(x -> requireActivity().onBackPressed());
    }

    private void setupButtons(View v) {
        setClick(v, R.id.btn_save, x -> save());
        setClick(v, R.id.btn_calc, x -> applyAllStandards());
        setClick(v, R.id.btn_share, x -> safeShare());
        setClick(v, R.id.btn_save_img, x -> saveImage());
        setClick(v, R.id.btn_copy, x -> copyText());
        setClick(v, R.id.btn_add_extra, x -> showAddExtraDialog());
        setClick(v, R.id.btn_next_month, x -> startNextMonth());
        if (btnAddMeter != null) {
            btnAddMeter.setOnClickListener(x -> addMeter(TYPE_ELEC));
        }
        if (btnAddWater != null) {
            btnAddWater.setOnClickListener(x -> addMeter(TYPE_WATER));
        }
        if (btnAddGas != null) {
            btnAddGas.setOnClickListener(x -> addMeter(TYPE_GAS));
        }

        if (btnDelete != null) {
            btnDelete.setVisibility(billId > 0 ? View.VISIBLE : View.GONE);
            btnDelete.setOnClickListener(x -> new MaterialAlertDialogBuilder(requireContext())
                    .setMessage(R.string.confirm_delete_bill)
                    .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                        db.deleteBill(bill.id);
                        Toast.makeText(requireContext(), R.string.toast_deleted, Toast.LENGTH_SHORT).show();
                        if (saveListener != null) saveListener.onBillDeleted();
                    })
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show());
        }
        if (v.findViewById(R.id.btn_next_month) != null) {
            v.findViewById(R.id.btn_next_month).setVisibility(billId > 0 ? View.VISIBLE : View.GONE);
        }
    }

    /** 在指定根视图内找控件并绑定点击；onCreateView 返回前禁止使用 requireView() */
    private void setClick(View root, int id, View.OnClickListener l) {
        if (root == null) return;
        View view = root.findViewById(id);
        if (view != null) view.setOnClickListener(l);
    }

    /** 起止码显示：0 显示空（方便“记下月”后直接手填新止码），非 0 正常显示 */
    private static String code(double v) {
        if (v == 0) return "";
        return BillCalculator.usage(v);
    }

    private void fillFields() {
        inMonth.setText(bill.month);
        inRoom.setText(bill.room);
        inTenant.setText(bill.tenant);
        inNote.setText(bill.note);
        renderWaterMeters();
        renderGasMeters();
        renderMeters();
    }

    // ---------- 表区展开/收起（水/燃气/电表都是“点按钮才出现，可删除”） ----------

    /** 按数据刷新水/燃气区可见性：有数据→显示输入区，无数据→只显示“＋添加”按钮 */
    private void updateSectionsVisibility() {
        showWater(bill.hasWaterData());
        showGas(bill.hasGasData());
    }

    private void showWater(boolean show) {
        if (waterSection == null || btnAddWater == null) return;
        if (show) {
            waterSection.setVisibility(View.VISIBLE);
            // “＋添加水表”按钮常驻：多块表可连续点＋叠加，不再隐藏
            if (bill.waterMeters == null || bill.waterMeters.isEmpty()) {
                bill.waterMeters = new ArrayList<>();
                bill.waterMeters.add(new Bill.Meter("水表1", 0, 0, 0, 0));
            }
            renderWaterMeters();
        } else {
            // 收起并清空水表数据（按钮保持可见，可重新点＋）
            bill.waterMeters = new ArrayList<>();
            waterRows.clear();
            waterSection.setVisibility(View.GONE);
        }
        updateTotal();
    }

    private void showGas(boolean show) {
        if (gasSection == null || btnAddGas == null) return;
        if (show) {
            gasSection.setVisibility(View.VISIBLE);
            // “＋添加燃气表”按钮常驻：多块表可连续点＋叠加，不再隐藏
            if (bill.gasMeters == null || bill.gasMeters.isEmpty()) {
                bill.gasMeters = new ArrayList<>();
                bill.gasMeters.add(new Bill.Meter("燃气表1", 0, 0, 0, 0));
            }
            renderGasMeters();
        } else {
            bill.gasMeters = new ArrayList<>();
            gasRows.clear();
            gasSection.setVisibility(View.GONE);
        }
        updateTotal();
    }

    // ---------- 动态表（水/燃气/电表通用） ----------

    private void renderWaterMeters() {
        if (waterContainer == null) return;
        renderMeterList(waterContainer, bill.waterMeters, TYPE_WATER, waterRows);
        updateWaterFee();
    }

    private void renderGasMeters() {
        if (gasContainer == null) return;
        renderMeterList(gasContainer, bill.gasMeters, TYPE_GAS, gasRows);
        updateGasFee();
    }

    private void renderMeters() {
        if (meterContainer == null) return;
        renderMeterList(meterContainer, bill.meters, TYPE_ELEC, meterRows);
        updateElecFee();
    }

    /** 通用渲染：把列表渲染成卡片行 */
    private void renderMeterList(LinearLayout container, List<Bill.Meter> list, int type,
                                 List<MeterRowHolder> holders) {
        if (container == null) return;
        container.removeAllViews();
        holders.clear();
        String prefix = type == TYPE_WATER ? "水表" : type == TYPE_GAS ? "燃气表" : "电表";
        if (list == null || list.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        for (int i = 0; i < list.size(); i++) {
            Bill.Meter m = list.get(i);
            if (m == null) {
                m = new Bill.Meter(prefix + (i + 1), 0, 0, 0, 0);
                list.set(i, m);
            }
            if (m.label == null || m.label.trim().isEmpty()) m.label = prefix + (i + 1);
            MeterRowHolder h = createMeterRow(m, type);
            holders.add(h);
            container.addView(h.root);
        }
        // 只有一块表时不显示表名标签（水表1/燃气表1/电表1 都不显示），多块才显示区分
        boolean single = list.size() == 1;
        if (single && !holders.isEmpty() && holders.get(0).labelTv != null) {
            holders.get(0).labelTv.setVisibility(View.GONE);
        } else {
            for (MeterRowHolder h : holders) {
                if (h.labelTv != null) h.labelTv.setVisibility(View.VISIBLE);
            }
        }
    }

    private void addMeter(int type) {
        List<Bill.Meter> list = type == TYPE_WATER ? bill.waterMeters
                : type == TYPE_GAS ? bill.gasMeters : bill.meters;
        if (list == null) list = new ArrayList<>();
        String prefix = type == TYPE_WATER ? "水表" : type == TYPE_GAS ? "燃气表" : "电表";
        list.add(new Bill.Meter(prefix + (list.size() + 1), 0, 0, 0, 0));
        if (type == TYPE_WATER) {
            bill.waterMeters = list;
            showWater(true);
        } else if (type == TYPE_GAS) {
            bill.gasMeters = list;
            showGas(true);
        } else {
            bill.meters = list;
            renderMeters();
        }
        updateTotal();
    }

    /** 电表单价项目名：优先“电表”（v2.8 合并后），兼容旧“电表1” */
    private String elecFeeName() {
        if (feeByName.containsKey("电表")) return "电表";
        if (feeByName.containsKey("电表1")) return "电表1";
        return "电表";
    }

    private MeterRowHolder createMeterRow(final Bill.Meter m, final int type) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_meter, null);
        MeterRowHolder h = new MeterRowHolder();
        h.root = row;
        h.model = m;
        h.labelTv = row.findViewById(R.id.item_meter_label);
        h.startEt = row.findViewById(R.id.item_meter_start);
        h.endEt = row.findViewById(R.id.item_meter_end);
        h.usageEt = row.findViewById(R.id.item_meter_usage);
        h.feeEt = row.findViewById(R.id.item_meter_fee);
        h.usageLayout = row.findViewById(R.id.item_meter_usage_layout);
        h.remove = row.findViewById(R.id.item_meter_remove);

        // 单位后缀：水=吨，燃气=方，电=度
        String unit = type == TYPE_WATER ? "吨" : type == TYPE_GAS ? "方" : "度";
        if (h.usageLayout != null) h.usageLayout.setSuffixText(unit);
        // 单价项目名
        final String feeName = type == TYPE_WATER ? "水费"
                : type == TYPE_GAS ? "燃气费" : elecFeeName();

        h.labelTv.setText(m.label);
        h.startEt.setText(code(m.start));
        h.endEt.setText(code(m.end));
        h.usageEt.setText(BillCalculator.usage(m.usage));
        h.feeEt.setText(BillCalculator.money(m.fee));

        TextWatcher codeWatcher = new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                m.start = parse(h.startEt);
                m.end = parse(h.endEt);
                m.usage = Bill.use(m.start, m.end);
                h.usageEt.setText(BillCalculator.usage(m.usage));
                FeeItem f = feeByName.get(feeName);
                if (f != null && f.enabled && f.type == FeeItem.TYPE_USAGE) {
                    m.fee = BillCalculator.round2(m.usage * f.unitPrice);
                    h.feeEt.setText(BillCalculator.money(m.fee));
                }
                refreshTypeFee(type);
                updateTotal();
            }
        };
        h.startEt.addTextChangedListener(codeWatcher);
        h.endEt.addTextChangedListener(codeWatcher);

        h.feeEt.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                m.fee = parse(h.feeEt);
                refreshTypeFee(type);
                updateTotal();
            }
        });

        h.remove.setOnClickListener(x -> {
            List<Bill.Meter> list = type == TYPE_WATER ? bill.waterMeters
                    : type == TYPE_GAS ? bill.gasMeters : bill.meters;
            if (list != null) list.remove(m);
            if (type == TYPE_WATER) {
                if (bill.waterMeters == null || bill.waterMeters.isEmpty()) {
                    showWater(false);
                } else {
                    renderWaterMeters();
                }
            } else if (type == TYPE_GAS) {
                if (bill.gasMeters == null || bill.gasMeters.isEmpty()) {
                    showGas(false);
                } else {
                    renderGasMeters();
                }
            } else {
                renderMeters();
            }
            updateTotal();
        });

        return h;
    }

    /** 刷新某一类的合计费用：水费=Σ水表、燃气费=Σ燃气表、电费=Σ电表 */
    private void refreshTypeFee(int type) {
        if (type == TYPE_WATER) {
            updateWaterFee();
        } else if (type == TYPE_GAS) {
            updateGasFee();
        } else {
            updateElecFee();
        }
    }

    /** 水费合计 = 各水表费用之和 */
    private void updateWaterFee() {
        double sum = 0;
        if (bill.waterMeters != null) {
            for (Bill.Meter m : bill.waterMeters) {
                if (m != null) sum += m.fee;
            }
        }
        bill.waterFee = BillCalculator.round2(sum);
    }

    /** 燃气费合计 = 各燃气表费用之和 */
    private void updateGasFee() {
        double sum = 0;
        if (bill.gasMeters != null) {
            for (Bill.Meter m : bill.gasMeters) {
                if (m != null) sum += m.fee;
            }
        }
        bill.gasFee = BillCalculator.round2(sum);
    }

    /** 汇总各电表费用到 elecFee（电费合计不再单独显示输入框，由各电表卡片自动汇总） */
    private void updateElecFee() {
        double sum = 0;
        for (Bill.Meter m : bill.meters) {
            if (m != null) sum += m.fee;
        }
        bill.elecFee = BillCalculator.round2(sum);
    }

    // ---------- 监听器 ----------

    private void setupWatchers() {
        // 水/燃气/电表都由各自卡片行内的 TextWatcher 自动算费，无需额外监听
    }

    /** 全部按标准重算：水费、燃气费、各电表费用、其他费用明细 */
    private void applyAllStandards() {
        for (MeterRowHolder h : waterRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
        }
        for (MeterRowHolder h : gasRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
        }
        for (MeterRowHolder h : meterRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
        }
        bill.recalcUsageFromCodes();
        bill.syncWaterGasFromMeters();
        for (MeterRowHolder h : waterRows) {
            h.usageEt.setText(BillCalculator.usage(h.model.usage));
        }
        for (MeterRowHolder h : gasRows) {
            h.usageEt.setText(BillCalculator.usage(h.model.usage));
        }
        for (MeterRowHolder h : meterRows) {
            h.usageEt.setText(BillCalculator.usage(h.model.usage));
        }

        BillCalculator.applyStandards(bill, feeItems);
        updateWaterFee();
        updateGasFee();
        updateElecFee();
        for (MeterRowHolder h : waterRows) {
            h.feeEt.setText(BillCalculator.money(h.model.fee));
        }
        for (MeterRowHolder h : gasRows) {
            h.feeEt.setText(BillCalculator.money(h.model.fee));
        }
        for (MeterRowHolder h : meterRows) {
            h.feeEt.setText(BillCalculator.money(h.model.fee));
        }
        for (RowHolder h : rows) {
            if (h.model.usageBased) {
                FeeItem f = feeByName.get(h.model.name);
                if (f != null) {
                    h.model.amount = BillCalculator.round2(h.model.usage * f.unitPrice);
                    h.amountEt.setText(BillCalculator.money(h.model.amount));
                }
            }
        }
        updateTotal();
        Toast.makeText(requireContext(), "已按收费标准重新计算", Toast.LENGTH_SHORT).show();
    }

    private void updateTotal() {
        double total = bill.waterFee + bill.gasFee + bill.elecFee + bill.otherFee();
        txtTotal.setText(BillCalculator.money(total));
    }

    // ---------- 其他费用明细行 ----------

    private void renderExtras() {
        extraContainer.removeAllViews();
        rows.clear();
        for (Bill.ExtraAmount model : bill.extras) {
            rows.add(createRow(model));
        }
    }

    private RowHolder createRow(Bill.ExtraAmount model) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_extra, extraContainer, false);
        RowHolder h = new RowHolder();
        h.root = row;
        h.model = model;
        h.usageEt = row.findViewById(R.id.extra_usage);
        h.amountEt = row.findViewById(R.id.extra_amount);
        h.unitTv = row.findViewById(R.id.extra_unit);

        TextView nameTv = row.findViewById(R.id.extra_name);
        if (nameTv != null) nameTv.setText(model.name == null ? "" : model.name);

        if (model.usageBased) {
            if (h.usageEt != null) {
                h.usageEt.setVisibility(View.VISIBLE);
                h.usageEt.setText(BillCalculator.usage(model.usage));
            }
            if (h.unitTv != null) {
                h.unitTv.setVisibility(View.VISIBLE);
                FeeItem f = feeByName.get(model.name);
                h.unitTv.setText((f != null && f.unitLabel != null && !f.unitLabel.isEmpty())
                        ? f.unitLabel : "单位");
            }
        } else {
            if (h.usageEt != null) h.usageEt.setVisibility(View.GONE);
            if (h.unitTv != null) h.unitTv.setVisibility(View.GONE);
        }
        if (h.amountEt != null) h.amountEt.setText(BillCalculator.money(model.amount));

        if (h.usageEt != null) {
            h.usageEt.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    h.model.usage = parse(s);
                    FeeItem f = feeByName.get(h.model.name);
                    if (f != null && f.type == FeeItem.TYPE_USAGE) {
                        h.model.amount = BillCalculator.round2(h.model.usage * f.unitPrice);
                        if (h.amountEt != null) h.amountEt.setText(BillCalculator.money(h.model.amount));
                    }
                    updateTotal();
                }
            });
        }
        if (h.amountEt != null) {
            h.amountEt.addTextChangedListener(new SimpleWatcher() {
                @Override
                public void afterTextChanged(Editable s) {
                    h.model.amount = parse(s);
                    updateTotal();
                }
            });
        }

        ImageButton remove = row.findViewById(R.id.extra_remove);
        if (remove != null) {
            remove.setOnClickListener(x -> {
                bill.extras.remove(h.model);
                renderExtras();
                updateTotal();
            });
        }

        extraContainer.addView(row);
        return h;
    }

    private void showAddExtraDialog() {
        List<String> names = new ArrayList<>();
        List<FeeItem> candidates = new ArrayList<>();
        for (FeeItem f : feeItems) {
            if (!f.enabled || f.isBuiltin()) continue;
            candidates.add(f);
            names.add((f.name == null ? "未命名项目" : f.name) + "（" + f.typeText() + "）");
        }
        names.add(getString(R.string.dialog_custom_extra));

        String[] arr = names.toArray(new String[0]);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_add_extra_title)
                .setItems(arr, (d, which) -> {
                    if (which < candidates.size()) {
                        FeeItem f = candidates.get(which);
                        if (f.type == FeeItem.TYPE_FIXED) {
                            bill.extras.add(new Bill.ExtraAmount(f.name, false, 0,
                                    BillCalculator.round2(f.unitPrice)));
                        } else {
                            bill.extras.add(new Bill.ExtraAmount(f.name, true, 0, 0));
                        }
                    } else {
                        showCustomExtraDialog();
                        return;
                    }
                    renderExtras();
                    updateTotal();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    private void showCustomExtraDialog() {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_fee_item, null);
        android.widget.Spinner typeSpinner = v.findViewById(R.id.dg_type);
        typeSpinner.setVisibility(View.GONE);
        v.findViewById(R.id.dg_type_label).setVisibility(View.GONE);
        v.findViewById(R.id.dg_unit_layout).setVisibility(View.GONE);
        EditText name = v.findViewById(R.id.dg_name);
        EditText price = v.findViewById(R.id.dg_price);

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_custom_title)
                .setView(v)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) return;
                    double amt = parse(price);
                    bill.extras.add(new Bill.ExtraAmount(n, false, 0, BillCalculator.round2(amt)));
                    renderExtras();
                    updateTotal();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---------- 保存 / 删除 / 分享 ----------

    private Bill collectBill() {
        bill.month = inMonth.getText().toString().trim();
        // 月份规范化：2025-6 → 2025-06，保证筛选/排序一致
        if (bill.month.matches("\\d{4}-\\d{1,2}")) {
            String[] p = bill.month.split("-");
            bill.month = p[0] + "-" + (p[1].length() == 1 ? "0" + p[1] : p[1]);
        }
        bill.room = inRoom.getText().toString().trim();
        bill.tenant = inTenant.getText().toString().trim();
        for (MeterRowHolder h : waterRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
            h.model.fee = parse(h.feeEt);
        }
        for (MeterRowHolder h : gasRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
            h.model.fee = parse(h.feeEt);
        }
        for (MeterRowHolder h : meterRows) {
            h.model.start = parse(h.startEt);
            h.model.end = parse(h.endEt);
            h.model.usage = Bill.use(h.model.start, h.model.end);
            h.model.fee = parse(h.feeEt);
        }
        bill.recalcUsageFromCodes();
        bill.syncWaterGasFromMeters();
        updateWaterFee();
        updateGasFee();
        updateElecFee();
        bill.note = inNote.getText().toString().trim();
        return bill;
    }

    private void save() {
        collectBill();
        if (bill.month.isEmpty() || !bill.month.matches("\\d{4}-\\d{1,2}")) {
            Toast.makeText(requireContext(), R.string.toast_need_month, Toast.LENGTH_SHORT).show();
            return;
        }
        bill.id = db.saveBill(bill);
        // 保存账单后自动登记房间（房间名已存在则保留原信息）
        try {
            db.upsertRoomFromBill(bill.room, bill.tenant);
        } catch (Exception ignored) {
        }
        Toast.makeText(requireContext(), R.string.toast_saved, Toast.LENGTH_SHORT).show();
        if (saveListener != null) saveListener.onBillSaved();
    }

    /** 一键记下月：本月止码变下月起码（止码留空），直接在本页切换为下月表单，保存即生成新账单 */
    private void startNextMonth() {
        try {
            Bill b2 = collectBill().copyForNextMonth();
            bill = b2;
            billId = -1;
            if (toolbar != null) toolbar.setTitle("新建账单");
            if (btnDelete != null) btnDelete.setVisibility(View.GONE);
            if (getView() != null) {
                View next = getView().findViewById(R.id.btn_next_month);
                if (next != null) next.setVisibility(View.GONE);
            }
            fillFields();
            updateSectionsVisibility();
            renderExtras();
            // 保险：下月账单止码必须为空（水/燃气/每块电表一致），方便直接手填新止码
            clearAllEndCodes();
            updateTotal();
            Toast.makeText(requireContext(), "已切换为下月账单：起码已自动填入，止码留空，填好止码后保存即可", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Toast.makeText(requireContext(), "无法生成下月账单", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }
    }

    /** 清空所有表（水/燃气/电表）的止码输入框 */
    private void clearAllEndCodes() {
        for (MeterRowHolder h : waterRows) {
            if (h != null && h.endEt != null) h.endEt.setText("");
        }
        for (MeterRowHolder h : gasRows) {
            if (h != null && h.endEt != null) h.endEt.setText("");
        }
        for (MeterRowHolder h : meterRows) {
            if (h != null && h.endEt != null) h.endEt.setText("");
        }
    }

    private void safeShare() {
        try {
            BillShare.shareToWeChat(requireContext(), collectBill());
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(),
                    "无法打开分享，请改用“存为表格图片”后发送图片", Toast.LENGTH_LONG).show();
        }
    }

    private void copyText() {
        ClipboardManager cm = (ClipboardManager) requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("bill", BillShare.buildText(requireContext(), collectBill())));
        Toast.makeText(requireContext(), R.string.toast_copied, Toast.LENGTH_SHORT).show();
    }

    private void saveImage() {
        if (Build.VERSION.SDK_INT < 29) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                return;
            }
        }
        doSaveImage();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            doSaveImage();
        } else {
            Toast.makeText(requireContext(), R.string.permission_storage, Toast.LENGTH_SHORT).show();
        }
    }

    private void doSaveImage() {
        try {
            boolean ok = BillShare.saveToGallery(requireContext(), collectBill());
            Toast.makeText(requireContext(),
                    ok ? R.string.toast_saved_image : R.string.permission_storage, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), R.string.export_fail, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- 工具 ----------

    private double parse(Editable s) {
        if (s == null) return 0;
        try {
            String t = s.toString().trim();
            if (t.isEmpty()) return 0;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return 0;
        }
    }

    private double parse(EditText et) {
        try {
            String t = et.getText().toString().trim();
            if (t.isEmpty()) return 0;
            return Double.parseDouble(t);
        } catch (Exception e) {
            return 0;
        }
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
