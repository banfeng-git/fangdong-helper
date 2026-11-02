package com.rent.utility;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputLayout;
import com.rent.utility.data.DbHelper;
import com.rent.utility.data.FeeItem;
import com.rent.utility.ui.FeeItemAdapter;
import com.rent.utility.util.BillCalculator;

/**
 * 收费项目与收费标准设置页。
 * 内置：水费、电表1、电表2（按用量×单价自动算费）；
 * 可新增自定义项目（固定金额/按用量），可启用/停用、编辑、删除。
 */
public class FeeSettingsActivity extends AppCompatActivity {

    private DbHelper db;
    private FeeItemAdapter adapter;

    public static Intent createIntent(Context context) {
        return new Intent(context, FeeSettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fee_settings);

        db = new DbHelper(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_fee);
        toolbar.setTitle(R.string.fee_settings_title);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.settings_intro_tv)).setText(R.string.settings_intro);

        RecyclerView recycler = findViewById(R.id.fee_list);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FeeItemAdapter(new FeeItemAdapter.Callback() {
            @Override
            public void onEdit(FeeItem item) {
                showItemDialog(item);
            }

            @Override
            public void onDelete(FeeItem item) {
                new MaterialAlertDialogBuilder(FeeSettingsActivity.this)
                        .setMessage(getString(R.string.confirm_delete_fee, item.name))
                        .setPositiveButton(R.string.dialog_delete, (d, w) -> {
                            db.deleteFeeItem(item.id);
                            reload();
                        })
                        .setNegativeButton(R.string.dialog_cancel, null)
                        .show();
            }

            @Override
            public void onToggle(FeeItem item, boolean enabled) {
                item.enabled = enabled;
                db.saveFeeItem(item);
            }
        });
        recycler.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add_fee);
        fab.setOnClickListener(v -> showItemDialog(null));

        reload();
    }

    private void reload() {
        adapter.setData(db.getFeeItems());
    }

    private void showItemDialog(FeeItem existing) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_fee_item, null);
        EditText name = v.findViewById(R.id.dg_name);
        EditText price = v.findViewById(R.id.dg_price);
        EditText unit = v.findViewById(R.id.dg_unit);
        Spinner typeSpinner = v.findViewById(R.id.dg_type);
        TextView typeLabel = v.findViewById(R.id.dg_type_label);
        TextInputLayout unitLayout = v.findViewById(R.id.dg_unit_layout);

        String[] types = {getString(R.string.fee_type_usage), getString(R.string.fee_type_fixed)};
        ArrayAdapter<String> aa = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, types);
        aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(aa);

        boolean builtin = existing != null && existing.isBuiltin();
        if (builtin) {
            typeSpinner.setVisibility(View.GONE);
            typeLabel.setVisibility(View.GONE);
        } else {
            typeSpinner.setVisibility(View.VISIBLE);
            typeLabel.setVisibility(View.VISIBLE);
        }
        unitLayout.setVisibility(View.VISIBLE);

        if (existing != null) {
            name.setText(existing.name);
            price.setText(BillCalculator.money(existing.unitPrice));
            unit.setText(existing.unitLabel);
            typeSpinner.setSelection(existing.type == FeeItem.TYPE_FIXED ? 1 : 0);
            if (existing.type == FeeItem.TYPE_FIXED) {
                unitLayout.setVisibility(View.GONE);
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(existing == null ? R.string.dialog_fee_title_new : R.string.dialog_fee_title_edit)
                .setView(v)
                .setPositiveButton(R.string.dialog_ok, (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, R.string.hint_fee_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double p;
                    try {
                        p = Double.parseDouble(price.getText().toString().trim());
                    } catch (Exception e) {
                        p = 0;
                    }
                    FeeItem f = existing != null ? existing : new FeeItem();
                    f.name = n;
                    f.unitPrice = p;
                    f.enabled = true;
                    if (builtin) {
                        f.type = FeeItem.TYPE_USAGE;
                        f.unitLabel = unit.getText().toString().trim();
                    } else {
                        f.type = typeSpinner.getSelectedItemPosition() == 1
                                ? FeeItem.TYPE_FIXED : FeeItem.TYPE_USAGE;
                        if (f.type == FeeItem.TYPE_USAGE) {
                            f.unitLabel = unit.getText().toString().trim();
                        } else {
                            f.unitLabel = "";
                        }
                    }
                    db.saveFeeItem(f);
                    reload();
                })
                .setNegativeButton(R.string.dialog_cancel, null)
                .show();
    }
}
