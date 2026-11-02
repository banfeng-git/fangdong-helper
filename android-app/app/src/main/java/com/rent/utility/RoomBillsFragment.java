package com.rent.utility;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rent.utility.data.Bill;
import com.rent.utility.data.DbHelper;
import com.rent.utility.data.Room;
import com.rent.utility.ui.BillAdapter;
import com.rent.utility.util.BillCalculator;
import com.rent.utility.util.BillShare;
import com.rent.utility.util.CsvExporter;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 二级页面：某个房间的账单。
 * 顶部=房间信息（名称/租客/租房~到期时间，可编辑、删除房间=级联删除全部账单）；
 * 按钮=新的账单 / 导出当前账单 / 导出全部账单；「历史账单」按钮展开全部账单列表，
 * 每条历史账单可点击编辑、单独导出与分享。
 */
public class RoomBillsFragment extends Fragment {

    public interface Listener {
        void onNewBill(String roomName);

        void onRoomDeleted();

        void onBillClick(Bill bill);
    }

    private static final String ARG_ROOM = "room";

    private Listener listener;
    private DbHelper db;
    private String roomName;
    private Room room;

    private MaterialToolbar toolbar;
    private TextView roomTitle, roomTenant, roomRent;
    private TextView currentTitle, currentTotal, currentWater, currentElec, currentGas, currentOther;
    private MaterialButton btnHistory;
    private TextView historyEmpty;
    private RecyclerView historyList;
    private boolean historyOpen = false;
    private BillAdapter adapter;

    public static RoomBillsFragment newInstance(String roomName) {
        RoomBillsFragment f = new RoomBillsFragment();
        Bundle b = new Bundle();
        b.putString(ARG_ROOM, roomName);
        f.setArguments(b);
        return f;
    }

    /** 当前房间名（底部导航“＋”新建账单用） */
    public String getRoomName() {
        return roomName;
    }

    @Override
    public void onAttach(@NonNull android.content.Context context) {
        super.onAttach(context);
        if (context instanceof Listener) {
            listener = (Listener) context;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_room_bills, container, false);
        db = new DbHelper(requireContext());
        if (getArguments() != null) roomName = getArguments().getString(ARG_ROOM);
        if (roomName == null) roomName = "";

        toolbar = v.findViewById(R.id.toolbar_room);
        toolbar.setNavigationOnClickListener(x -> requireActivity().onBackPressed());

        roomTitle = v.findViewById(R.id.room_title);
        roomTenant = v.findViewById(R.id.room_tenant);
        roomRent = v.findViewById(R.id.room_rent_period);
        currentTitle = v.findViewById(R.id.current_title);
        currentTotal = v.findViewById(R.id.current_total);
        currentWater = v.findViewById(R.id.current_water);
        currentElec = v.findViewById(R.id.current_elec);
        currentGas = v.findViewById(R.id.current_gas);
        currentOther = v.findViewById(R.id.current_other);
        btnHistory = v.findViewById(R.id.btn_history);
        historyEmpty = v.findViewById(R.id.history_empty);
        historyList = v.findViewById(R.id.history_list);

        v.findViewById(R.id.btn_edit_room).setOnClickListener(x ->
                showRoomInfoDialog(this, db, room, this::reload));

        v.findViewById(R.id.btn_delete_room).setOnClickListener(x -> confirmDeleteRoom());

        v.findViewById(R.id.btn_new_bill).setOnClickListener(x -> {
            if (listener != null) listener.onNewBill(roomName);
        });
        v.findViewById(R.id.btn_export_current).setOnClickListener(x -> exportCurrent());
        v.findViewById(R.id.btn_export_all).setOnClickListener(x -> exportAll());
        btnHistory.setOnClickListener(x -> toggleHistory());

        historyList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new BillAdapter(new BillAdapter.Callback() {
            @Override
            public void onBillClick(Bill bill) {
                if (listener != null) listener.onBillClick(bill);
            }

            @Override
            public void onShare(Bill bill) {
                try {
                    BillShare.shareToWeChat(requireContext(), bill);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(requireContext(), R.string.export_fail, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onExport(Bill bill) {
                exportSingle(bill);
            }
        });
        historyList.setAdapter(adapter);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    public void reload() {
        if (db == null || roomName == null) return;
        try {
            room = db.getRoom(roomName);
            if (room == null) {
                // 房间已被删除，回退到一级
                if (getView() != null && listener != null) listener.onRoomDeleted();
                return;
            }
            toolbar.setTitle(room.name);
            roomTitle.setText(room.name);
            String tenant = room.tenant == null ? "" : room.tenant.trim();
            roomTenant.setText(tenant.isEmpty() ? "租客 未填写" : "租客 " + tenant);
            roomRent.setText(room.hasRentPeriod() ? ("租期 " + room.rentPeriodText()) : "租期 未填写");

            // 当前账单 = 最新一条
            Bill latest = db.getRoomLatestBill(roomName);
            if (latest == null) {
                currentTitle.setText(R.string.room_no_current);
                currentTotal.setText("");
                currentWater.setText("—");
                currentElec.setText("—");
                currentGas.setText("—");
                currentOther.setText("—");
            } else {
                currentTitle.setText("当前账单 · " + latest.month);
                currentTotal.setText(BillCalculator.money(latest.total()) + " 元");
                currentWater.setText(latest.waterFee > 0 ? BillCalculator.money(latest.waterFee) + " 元" : "—");
                currentElec.setText(latest.elecFee > 0 ? BillCalculator.money(latest.elecFee) + " 元" : "—");
                currentGas.setText(latest.gasFee > 0 ? BillCalculator.money(latest.gasFee) + " 元" : "—");
                currentOther.setText(latest.otherFee() > 0 ? BillCalculator.money(latest.otherFee()) + " 元" : "—");
            }

            List<Bill> bills = db.getBillsByRoom(roomName);
            btnHistory.setText(requireContext().getString(R.string.room_history) + "（" + bills.size() + "）");
            historyEmpty.setVisibility(bills.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.setData(bills);
            if (historyOpen && !bills.isEmpty()) {
                historyList.setVisibility(View.VISIBLE);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void toggleHistory() {
        historyOpen = !historyOpen;
        historyList.setVisibility(historyOpen ? View.VISIBLE : View.GONE);
        btnHistory.setText(historyOpen
                ? requireContext().getString(R.string.room_history_hide)
                : requireContext().getString(R.string.room_history));
    }

    private void confirmDeleteRoom() {
        String name = room != null ? room.name : roomName;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(requireContext().getString(R.string.confirm_delete_room, name))
                .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                    db.deleteRoom(roomName);
                    Toast.makeText(requireContext(), R.string.room_deleted, Toast.LENGTH_SHORT).show();
                    if (listener != null) listener.onRoomDeleted();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---------- 导出 ----------

    private void exportCurrent() {
        Bill latest = db.getRoomLatestBill(roomName);
        if (latest == null) {
            Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        exportBills("当前账单", new Bill[]{latest}, R.string.export_current_ok);
    }

    private void exportAll() {
        List<Bill> bills = db.getBillsByRoom(roomName);
        if (bills.isEmpty()) {
            Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        exportBills("全部账单", bills.toArray(new Bill[0]), R.string.export_room_ok);
    }

    private void exportSingle(Bill bill) {
        exportBills("账单", new Bill[]{bill}, R.string.export_single_ok);
    }

    private void exportBills(String label, Bill[] bills, int okRes) {
        if (Build.VERSION.SDK_INT < 29
                && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(requireContext(), R.string.permission_storage, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.util.ArrayList<Bill> list = new java.util.ArrayList<>();
            for (Bill b : bills) list.add(b);
            String csv = CsvExporter.buildCsv(list);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
            String safeRoom = roomName == null ? "房间" : roomName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = safeRoom + "_" + label + "_" + stamp + ".csv";
            boolean saved = CsvExporter.saveToDownloads(requireContext(), fileName, csv);
            Toast.makeText(requireContext(), saved ? okRes : R.string.export_fail, Toast.LENGTH_LONG).show();
            if (saved) {
                try {
                    File share = CsvExporter.writeCacheFile(requireContext(), fileName, csv);
                    CsvExporter.shareFile(requireContext(), share);
                } catch (Exception inner) {
                    inner.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), R.string.export_fail, Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 房间信息编辑（添加/编辑共用） ----------

    /** 给时间输入框挂日期选择器：点击弹出年月日选择，选中后回填 yyyy-MM-dd */
    private static void attachDatePicker(final EditText et) {
        et.setOnClickListener(v -> {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            String cur = et.getText().toString().trim();
            try {
                if (!cur.isEmpty()) {
                    String[] p = cur.split("-");
                    if (p.length == 3) {
                        cal.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
                    }
                }
            } catch (Exception ignored) {
            }
            new android.app.DatePickerDialog(et.getContext(),
                    (view, year, month, day) ->
                            et.setText(String.format(Locale.CHINA, "%04d-%02d-%02d", year, month + 1, day)),
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH)).show();
        });
    }

    /** 添加/编辑房间信息对话框；保存成功后回调 onSaved */
    public static void showRoomInfoDialog(Fragment host, DbHelper db, Room room, Runnable onSaved) {
        View v = LayoutInflater.from(host.requireContext()).inflate(R.layout.dialog_room_info, null);
        EditText name = v.findViewById(R.id.room_dg_name);
        EditText tenant = v.findViewById(R.id.room_dg_tenant);
        EditText start = v.findViewById(R.id.room_dg_start);
        EditText end = v.findViewById(R.id.room_dg_end);
        if (room != null) {
            name.setText(room.name);
            tenant.setText(room.tenant);
            start.setText(room.rentStart);
            end.setText(room.rentEnd);
        }

        // 租房时间/到期时间：点击弹日期选择器，不手填
        attachDatePicker(start);
        attachDatePicker(end);

        new MaterialAlertDialogBuilder(host.requireContext())
                .setTitle(room == null ? R.string.add_room : R.string.edit_room)
                .setView(v)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    String nm = name.getText().toString().trim();
                    if (nm.isEmpty()) {
                        Toast.makeText(host.requireContext(), R.string.room_name_required,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Room target = room == null ? new Room(nm) : room;
                    target.name = nm;
                    target.tenant = tenant.getText().toString().trim();
                    target.rentStart = start.getText().toString().trim();
                    target.rentEnd = end.getText().toString().trim();
                    if (target.id > 0) {
                        db.updateRoom(target);
                    } else {
                        db.upsertRoomFromBill(nm, target.tenant);
                        Room created = db.getRoom(nm);
                        if (created != null) {
                            created.tenant = target.tenant;
                            created.rentStart = target.rentStart;
                            created.rentEnd = target.rentEnd;
                            db.updateRoom(created);
                        }
                    }
                    Toast.makeText(host.requireContext(), R.string.room_saved, Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
