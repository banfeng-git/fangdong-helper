package com.rent.utility;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/** 设置菜单：收费项目与标准 / 关于本软件 */
public class SettingsActivity extends AppCompatActivity {

    public static Intent createIntent(Context ctx) {
        return new Intent(ctx, SettingsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_settings);
        toolbar.setNavigationOnClickListener(x -> finish());

        findViewById(R.id.row_fee_settings).setOnClickListener(x ->
                startActivity(FeeSettingsActivity.createIntent(this)));
        findViewById(R.id.row_sync).setOnClickListener(x ->
                startActivity(SyncActivity.createIntent(this)));
        findViewById(R.id.row_about).setOnClickListener(x ->
                startActivity(AboutActivity.createIntent(this)));
    }
}
