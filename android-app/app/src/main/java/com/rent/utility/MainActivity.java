package com.rent.utility;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.rent.utility.data.Bill;
import com.rent.utility.data.Room;

/**
 * 主界面：单容器导航（手机/平板统一）。
 * 一级=房间列表 → 二级=房间账单页 → 编辑页（新建/编辑账单）。
 * v2.0：底部导航栏（主页 / ＋添加 / 设置），编辑页隐藏底栏；右上角设置菜单移除。
 */
public class MainActivity extends AppCompatActivity
        implements RoomListFragment.Listener, RoomBillsFragment.Listener,
        BillEditFragment.SaveListener {

    private View bottomBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bottomBar = findViewById(R.id.bottom_bar);
        findViewById(R.id.bottom_home).setOnClickListener(v -> goHome());
        findViewById(R.id.bottom_add).setOnClickListener(v -> onBottomAdd());
        findViewById(R.id.bottom_settings).setOnClickListener(v ->
                startActivity(SettingsActivity.createIntent(this)));

        // 安全区：状态栏顶部留白、系统导航栏底部留白，保证底栏不被系统导航栏遮挡
        // （安卓15+ 强制 edge-to-edge 时 fitsSystemWindows 不可靠，统一改为手动处理）
        final View rootView = findViewById(R.id.root_main);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
            androidx.core.graphics.Insets bars =
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        // 关键修复：进入/退出任意页面都按“当前页面”自动同步底栏，
        // 避免用系统返回键退出编辑页时底栏停在 GONE 而“消失”（v2.8 及更早版本的问题）
        getSupportFragmentManager().addOnBackStackChangedListener(this::syncBottomBar);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, new RoomListFragment(), "rooms")
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从设置/关于等页面或系统弹窗回来后兜底同步一次
        syncBottomBar();
    }

    /**
     * 底栏可见性统一由“当前页面”决定：
     * 编辑账单页（全屏录入）隐藏底栏，其余页面（房间列表 / 房间账单）显示。
     */
    private void syncBottomBar() {
        if (bottomBar == null) return;
        try {
            Fragment top = getSupportFragmentManager().findFragmentById(R.id.container);
            boolean editing = top instanceof BillEditFragment;
            bottomBar.setVisibility(editing ? View.GONE : View.VISIBLE);
        } catch (Exception e) {
            e.printStackTrace();
            bottomBar.setVisibility(View.VISIBLE);
        }
    }

    // ---------- 底部导航 ----------

    /** 回到一级主页（房间列表） */
    private void goHome() {
        try {
            androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
            while (fm.getBackStackEntryCount() > 0) {
                fm.popBackStackImmediate();
            }
            fm.executePendingTransactions();
            RoomListFragment rf = (RoomListFragment) fm.findFragmentByTag("rooms");
            if (rf != null) rf.reload();
            if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 底部 ＋ ：一级页=弹菜单（添加房间/快捷导入/导出全部）；二级页=新建该房间账单 */
    private void onBottomAdd() {
        try {
            Fragment top = getSupportFragmentManager().findFragmentById(R.id.container);
            if (top instanceof RoomBillsFragment) {
                onNewBill(((RoomBillsFragment) top).getRoomName());
                return;
            }
            if (top instanceof RoomListFragment) {
                RoomListFragment rf = (RoomListFragment) top;
                String[] items = {
                        getString(R.string.bottom_add_room),
                        getString(R.string.bottom_add_import),
                        getString(R.string.bottom_add_export)
                };
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.bottom_add_menu_title)
                        .setItems(items, (d, which) -> {
                            if (which == 0) {
                                rf.showRoomDialog(null);
                            } else if (which == 1) {
                                rf.openImportFromBottomBar();
                            } else if (which == 2) {
                                rf.exportAllFromBottomBar();
                            }
                        })
                        .show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Toast.makeText(this, "无法添加", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- RoomListFragment.Listener ----------

    @Override
    public void onRoomClick(Room room) {
        try {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, RoomBillsFragment.newInstance(room.name), "room_bills")
                    .addToBackStack(null)
                    .commit();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- RoomBillsFragment.Listener ----------

    @Override
    public void onNewBill(String roomName) {
        openEdit(-1, roomName);
    }

    @Override
    public void onRoomDeleted() {
        getSupportFragmentManager().popBackStack();
        RoomListFragment rf = (RoomListFragment) getSupportFragmentManager().findFragmentByTag("rooms");
        if (rf != null) rf.reload();
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBillClick(Bill bill) {
        openEdit(bill.id, null);
    }

    // ---------- BillEditFragment.SaveListener ----------

    @Override
    public void onBillSaved() {
        // popBackStack 是异步的：先执行挂起的 Fragment 事务，确保拿到的是真实栈顶
        getSupportFragmentManager().executePendingTransactions();
        Fragment top = getSupportFragmentManager().findFragmentById(R.id.container);
        if (top instanceof BillEditFragment) {
            getSupportFragmentManager().popBackStack();
            getSupportFragmentManager().executePendingTransactions();
            top = getSupportFragmentManager().findFragmentById(R.id.container);
        }
        if (top instanceof RoomBillsFragment) {
            ((RoomBillsFragment) top).reload();
        } else if (top instanceof RoomListFragment) {
            ((RoomListFragment) top).reload();
        }
        if (bottomBar != null) bottomBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBillDeleted() {
        onBillSaved();
    }

    // ---------- 打开编辑页 ----------

    private void openEdit(long billId, String presetRoom) {
        try {
            BillEditFragment f = new BillEditFragment();
            Bundle args = new Bundle();
            args.putLong("billId", billId);
            if (presetRoom != null) args.putString("presetRoom", presetRoom);
            f.setArguments(args);
            FragmentTransaction t = getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, f)
                    .addToBackStack(null);
            t.commit();
            if (bottomBar != null) bottomBar.setVisibility(View.GONE);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Toast.makeText(this, "无法打开账单编辑", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {
            }
        }
    }
}
