package com.rent.utility;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.rent.utility.data.Bill;
import com.rent.utility.data.DbHelper;
import com.rent.utility.util.CloudSync;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 云端同步（可选）：不登录完全不影响本地记账。
 * 服务端地址由用户自己填，Cloudflare / 腾讯 EdgeOne / 阿里云 ESA 三家用同一个地址格式。
 */
public class SyncActivity extends AppCompatActivity {

    private EditText etServer, etUser, etPass;
    private TextView tvStatus;
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static Intent createIntent(Context ctx) {
        return new Intent(ctx, SyncActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_sync);
        toolbar.setNavigationOnClickListener(x -> finish());

        etServer = findViewById(R.id.et_server);
        etUser = findViewById(R.id.et_username);
        etPass = findViewById(R.id.et_password);
        tvStatus = findViewById(R.id.tv_sync_status);

        etServer.setText(CloudSync.getServer(this));
        etUser.setText(CloudSync.getUsername(this));

        findViewById(R.id.btn_login).setOnClickListener(x -> doLogin());
        findViewById(R.id.btn_register).setOnClickListener(x -> doRegister());
        findViewById(R.id.btn_logout).setOnClickListener(x -> doLogout());
        findViewById(R.id.btn_upload).setOnClickListener(x -> doUpload());
        findViewById(R.id.btn_download).setOnClickListener(x -> doDownload());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String server = CloudSync.getServer(this);
        if (CloudSync.isLoggedIn(this)) {
            status("已登录：" + CloudSync.getUsername(this) + "\n服务端：" + server
                    + "\n\n可上传本地账单到云端，或把云端账单下载到本机。");
        } else if (server.isEmpty()) {
            status(getString(R.string.sync_status_idle));
        } else {
            status("服务端：" + server + "\n当前未登录，本地记账不受影响。");
        }
    }

    private void status(String s) {
        tvStatus.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private boolean saveServerField() {
        String server = etServer.getText().toString().trim();
        if (TextUtils.isEmpty(server)) {
            toast("请先填写服务端地址");
            return false;
        }
        CloudSync.setServer(this, server);
        etServer.setText(CloudSync.getServer(this));
        return true;
    }

    // ---------------- 账号 ----------------

    private void doLogin() {
        if (!saveServerField()) return;
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            toast("请输入账号和密码");
            return;
        }
        runAsync(() -> {
            CloudSync.Res r = CloudSync.login(this, user, pass);
            ui.post(() -> {
                if (r.ok()) {
                    CloudSync.saveLogin(this, user, r.data.optString("token", ""));
                    refreshStatus();
                    toast("登录成功");
                } else {
                    status("登录失败：" + r.msg);
                }
            });
        });
    }

    private void doRegister() {
        if (!saveServerField()) return;
        String user = etUser.getText().toString().trim();
        String pass = etPass.getText().toString().trim();
        if (user.length() < 2 || pass.length() < 6) {
            toast("账号至少 2 位、密码至少 6 位");
            return;
        }
        runAsync(() -> {
            CloudSync.Res r = CloudSync.register(this, user, pass);
            ui.post(() -> {
                if (r.ok()) {
                    CloudSync.saveLogin(this, user, r.data.optString("token", ""));
                    refreshStatus();
                    toast("注册成功，已自动登录");
                } else {
                    status("注册失败：" + r.msg);
                }
            });
        });
    }

    private void doLogout() {
        runAsync(() -> {
            try {
                if (CloudSync.isLoggedIn(this)) CloudSync.logout(this);
            } catch (Exception ignored) {
            }
            ui.post(() -> {
                CloudSync.clearLogin(this);
                refreshStatus();
                toast("已退出登录（本地账单不受影响）");
            });
        });
    }

    // ---------------- 同步 ----------------

    private void doUpload() {
        if (!saveServerField()) return;
        if (!CloudSync.isLoggedIn(this)) {
            toast("请先登录再上传");
            return;
        }
        runAsync(() -> {
            DbHelper db = new DbHelper(this);
            List<Bill> bills = db.getBills();
            if (bills.isEmpty()) {
                ui.post(() -> toast("本地还没有账单"));
                return;
            }
            // 补齐 uid / 更新时间，保证多端不重复
            for (Bill b : bills) {
                db.saveBill(b);
            }
            CloudSync.Res r = CloudSync.sync(this, bills);
            int merged = 0;
            if (r.ok()) {
                merged = mergeFromServer(r.data);
            }
            final int m = merged;
            ui.post(() -> {
                if (r.ok()) {
                    status("上传完成，云端现已 " + r.data.optInt("count", 0)
                            + " 条账单，本次合并 " + m + " 条到本机。");
                    toast("上传成功");
                } else {
                    status("上传失败：" + r.msg);
                }
            });
        });
    }

    private void doDownload() {
        if (!saveServerField()) return;
        if (!CloudSync.isLoggedIn(this)) {
            toast("请先登录再下载");
            return;
        }
        status("正在拉取云端账单列表…");
        runAsync(() -> {
            CloudSync.Res r = CloudSync.list(this);
            if (!r.ok()) {
                ui.post(() -> status("获取云端列表失败：" + r.msg));
                return;
            }
            final JSONArray arr = r.data.optJSONArray("bills");
            ui.post(() -> showCloudPicker(arr));
        });
    }

    /** 手动挑选：列出云端账单（含来源设备），可勾选下载，也可勾选从云端删除 */
    private void showCloudPicker(JSONArray arr) {
        final JSONArray bills = arr == null ? new JSONArray() : arr;
        final int n = bills.length();
        if (n == 0) {
            status("云端还没有账单。可点“上传本地账单”先传上去。");
            return;
        }
        final String[] items = new String[n];
        final boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            try {
                Bill b = CloudSync.fromJson(bills.getJSONObject(i));
                String s = b.room + " · " + b.month
                        + (TextUtils.isEmpty(b.tenant) ? "" : " · 租客 " + b.tenant)
                        + " · 合计 " + String.format("%.2f", b.total()) + " 元";
                if (!TextUtils.isEmpty(b.device)) s += " · 来自 " + b.device;
                items[i] = s;
            } catch (Exception e) {
                items[i] = "云端账单 #" + i;
            }
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("云端共 " + n + " 条账单（勾选要下载的）")
                .setMultiChoiceItems(items, checked, (d, w, isChecked) -> checked[w] = isChecked)
                .setPositiveButton("下载选中", (d, w) -> runAsync(() -> {
                    int merged = 0;
                    try {
                        DbHelper db = new DbHelper(this);
                        for (int i = 0; i < n; i++) {
                            if (!checked[i]) continue;
                            Bill incoming = CloudSync.fromJson(bills.optJSONObject(i));
                            if (TextUtils.isEmpty(incoming.uid)) continue;
                            Bill local = db.findByUid(incoming.uid);
                            if (local == null) {
                                incoming.id = 0;
                                db.saveBillFromCloud(incoming);
                                merged++;
                            } else if (incoming.updatedAt > local.updatedAt) {
                                incoming.id = local.id;
                                db.saveBillFromCloud(incoming);
                                merged++;
                            } else {
                                db.upsertRoomFromBill(local.room, local.tenant);
                            }
                        }
                    } catch (Exception e) {
                        String m = e.getMessage() == null ? e.toString() : e.getMessage();
                        ui.post(() -> status("下载出错：" + m));
                        return;
                    }
                    final int c = merged;
                    ui.post(() -> {
                        status("已下载 " + c + " 条账单到本机（其余未勾选），返回主界面即可看到。");
                        toast("下载完成");
                    });
                }))
                .setNeutralButton("删除选中", (d, w) -> runAsync(() -> {
                    int deleted = 0;
                    String lastMsg = "";
                    try {
                        for (int i = 0; i < n; i++) {
                            if (!checked[i]) continue;
                            JSONObject o = bills.optJSONObject(i);
                            String uid = o == null ? "" : o.optString("uid", "");
                            if (TextUtils.isEmpty(uid)) continue;
                            CloudSync.Res r = CloudSync.delete(this, uid);
                            if (r.ok()) deleted++;
                            else lastMsg = r.msg;
                        }
                    } catch (Exception e) {
                        lastMsg = e.getMessage();
                    }
                    final int c = deleted;
                    final String msg = lastMsg;
                    ui.post(() -> {
                        status("已从云端删除 " + c + " 条账单。" + (TextUtils.isEmpty(msg) ? "" : "（出错：" + msg + "）"));
                        toast("删除完成");
                    });
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把服务端返回的账单并进本地数据库（按 uid，时间新的覆盖旧的） */
    private int mergeFromServer(JSONObject data) {
        int count = 0;
        try {
            JSONArray arr = data.optJSONArray("bills");
            if (arr == null) return 0;
            DbHelper db = new DbHelper(this);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Bill incoming = CloudSync.fromJson(o);
                if (TextUtils.isEmpty(incoming.uid)) continue;
                Bill local = db.findByUid(incoming.uid);
                if (local == null) {
                    incoming.id = 0;
                    db.saveBillFromCloud(incoming);
                    count++;
                } else if (incoming.updatedAt > local.updatedAt) {
                    incoming.id = local.id;
                    db.saveBillFromCloud(incoming);
                    count++;
                } else {
                    // 本地已有同一条（可能是之前下载过但没登记房间），补登记，保证主界面能看到
                    db.upsertRoomFromBill(local.room, local.tenant);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return count;
    }

    // ---------------- 线程 ----------------

    private interface Job {
        void run() throws Exception;
    }

    private void runAsync(Job job) {
        new Thread(() -> {
            try {
                job.run();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui.post(() -> status("出错了：" + msg));
            }
        }).start();
    }
}
