package com.rent.utility;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rent.utility.data.Bill;
import com.rent.utility.data.DbHelper;
import com.rent.utility.data.Room;
import com.rent.utility.ui.RoomAdapter;
import com.rent.utility.util.BillCalculator;
import com.rent.utility.util.CsvExporter;
import com.rent.utility.util.CsvImporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 一级页面：房间列表。点房间进入二级（该房间账单页）；顶部可导入/导出全部；长按房间有操作菜单。 */
public class RoomListFragment extends Fragment {

    public interface Listener {
        void onRoomClick(Room room);
    }

    private Listener listener;
    private DbHelper db;
    private RoomAdapter adapter;
    private TextView emptyHint;
    private TextView ovWater, ovElec, ovGas, ovTotal, ovInfo;
    private EditText searchBox;
    private List<Room> allRooms = new ArrayList<>();
    private String keyword = "";
    private ActivityResultLauncher<String[]> filePicker;

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
        View v = inflater.inflate(R.layout.fragment_room_list, container, false);
        db = new DbHelper(requireContext());

        // CSV 文件选择（导入账单）
        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onFilePicked);

        emptyHint = v.findViewById(R.id.empty_hint);
        ovWater = v.findViewById(R.id.ov_water);
        ovElec = v.findViewById(R.id.ov_elec);
        ovGas = v.findViewById(R.id.ov_gas);
        ovTotal = v.findViewById(R.id.ov_total);
        ovInfo = v.findViewById(R.id.ov_info);
        RecyclerView recycler = v.findViewById(R.id.room_list);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new RoomAdapter(new RoomAdapter.Callback() {
            @Override
            public void onRoomClick(Room room) {
                if (listener != null) listener.onRoomClick(room);
            }

            @Override
            public void onRoomLongClick(Room room) {
                showRoomMenu(room);
            }
        });
        recycler.setAdapter(adapter);

        // 搜索：按 房间名/租客 过滤房间列表（保留搜索功能）
        searchBox = v.findViewById(R.id.search_rooms);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                keyword = s == null ? "" : s.toString().trim();
                applyFilter();
            }
        });

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    /** 保存/删除/新建/导入后重新加载房间列表 */
    public void reload() {
        if (db == null) return;
        try {
            allRooms = db.getRooms();
            applyFilter();
            updateOverview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 按关键词过滤并显示房间列表 */
    private void applyFilter() {
        List<Room> shown = new ArrayList<>();
        if (allRooms != null) {
            for (Room r : allRooms) {
                if (keyword.isEmpty()) {
                    shown.add(r);
                } else {
                    String name = r.name == null ? "" : r.name;
                    String tenant = r.tenant == null ? "" : r.tenant;
                    if (name.contains(keyword) || tenant.contains(keyword)) shown.add(r);
                }
            }
        }
        adapter.setData(shown);
        if (emptyHint != null) {
            emptyHint.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /** 全部账单汇总：水/电/燃气/合计 + 账单数与房间数（保留汇总板块） */
    private void updateOverview() {
        try {
            List<Bill> bills = db.getBills(null, null, null);
            double w = 0, e = 0, g = 0, o = 0, t = 0;
            java.util.Set<String> roomNames = new java.util.HashSet<>();
            for (Bill b : bills) {
                w += b.waterFee;
                e += b.elecFee;
                g += b.gasFee;
                o += b.otherFee();
                t += b.total();
                if (b.room != null && !b.room.trim().isEmpty()) roomNames.add(b.room.trim());
            }
            ovWater.setText(BillCalculator.money(w));
            ovElec.setText(BillCalculator.money(e));
            ovGas.setText(BillCalculator.money(g));
            ovTotal.setText(BillCalculator.money(t));
            StringBuilder sb = new StringBuilder("共 ").append(bills.size()).append(" 条账单 · ")
                    .append(roomNames.size()).append(" 个房间");
            if (o != 0) sb.append(" · 其他费用合计 ").append(BillCalculator.money(o)).append(" 元");
            ovInfo.setText(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 添加/编辑房间（room 为 null 时是新增） */
    public void showRoomDialog(Room room) {
        RoomBillsFragment.showRoomInfoDialog(this, db, room, this::reload);
    }

    // ---------- 长按房间菜单 ----------

    private void showRoomMenu(Room room) {
        String[] items = {
                getString(R.string.room_menu_import),
                getString(R.string.room_menu_export),
                getString(R.string.room_menu_edit),
                getString(R.string.room_menu_delete)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(room.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        openFilePicker();
                    } else if (which == 1) {
                        exportRoom(room.name);
                    } else if (which == 2) {
                        showRoomDialog(room);
                    } else if (which == 3) {
                        confirmDeleteRoom(room);
                    }
                })
                .show();
    }

    private void confirmDeleteRoom(Room room) {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.confirm_delete_room, room.name))
                .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                    db.deleteRoom(room.name);
                    Toast.makeText(requireContext(), R.string.room_deleted, Toast.LENGTH_SHORT).show();
                    reload();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }

    // ---------- 导入 / 导出 ----------

    private void openFilePicker() {
        try {
            filePicker.launch(new String[]{"text/*", "application/octet-stream"});
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 底部导航“＋ → 快捷导入账单”入口 */
    public void openImportFromBottomBar() {
        openFilePicker();
    }

    /** 底部导航“＋ → 导出全部账单”入口 */
    public void exportAllFromBottomBar() {
        exportAll();
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        new Thread(() -> {
            String content = readUri(uri);
            requireActivity().runOnUiThread(() -> doImport(content));
        }).start();
    }

    private String readUri(Uri uri) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
        return sb.toString();
    }

    private void doImport(String content) {
        if (content == null || content.trim().isEmpty()) {
            Toast.makeText(requireContext(), R.string.import_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            List<Bill> bills = CsvImporter.parseCsv(content);
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), R.string.import_parse_fail, Toast.LENGTH_SHORT).show();
                return;
            }
            int n = 0;
            for (Bill b : bills) {
                try {
                    db.saveBill(b);
                    db.upsertRoomFromBill(b.room, b.tenant);
                    n++;
                } catch (Exception ignored) {
                }
            }
            Toast.makeText(requireContext(),
                    getString(R.string.import_ok, n), Toast.LENGTH_LONG).show();
            reload();
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(requireContext(), R.string.import_parse_fail, Toast.LENGTH_SHORT).show();
        }
    }

    /** 导出全部账单 CSV（保存到 Download 并分享） */
    private void exportAll() {
        try {
            List<Bill> bills = db.getBills(null, null, null);
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            String csv = CsvExporter.buildCsv(bills);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
            String fileName = "全部账单_" + stamp + ".csv";
            boolean saved = CsvExporter.saveToDownloads(requireContext(), fileName, csv);
            Toast.makeText(requireContext(),
                    saved ? R.string.export_all_rooms_ok : R.string.export_fail, Toast.LENGTH_LONG).show();
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

    /** 导出指定房间的全部账单 */
    private void exportRoom(String roomName) {
        try {
            List<Bill> bills = db.getBillsByRoom(roomName);
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            String csv = CsvExporter.buildCsv(bills);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date());
            String safe = roomName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = safe + "_全部账单_" + stamp + ".csv";
            boolean saved = CsvExporter.saveToDownloads(requireContext(), fileName, csv);
            Toast.makeText(requireContext(),
                    saved ? R.string.export_room_ok : R.string.export_fail, Toast.LENGTH_LONG).show();
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
}
