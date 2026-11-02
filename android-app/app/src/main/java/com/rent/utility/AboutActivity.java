package com.rent.utility;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

/** 关于本软件：作者、版本、更新信息 */
public class AboutActivity extends AppCompatActivity {

    public static Intent createIntent(Context ctx) {
        return new Intent(ctx, AboutActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        MaterialToolbar toolbar = findViewById(R.id.toolbar_about);
        toolbar.setNavigationOnClickListener(x -> finish());

        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.about_version)).setText(
                    getString(R.string.about_version_line, ver));
        } catch (Exception e) {
            e.printStackTrace();
        }
        ((TextView) findViewById(R.id.about_changelog)).setText(R.string.about_changelog);
    }
}
