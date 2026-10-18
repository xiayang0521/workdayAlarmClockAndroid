package com.zyyme.workdayviewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 老人主界面：顶部选择设备，两个大按钮（看实时 / 看录像），设备管理入口。
 */
public class MainActivity extends Activity {
    private TextView statusText;
    private Spinner deviceSpinner;
    private boolean spinnerInit = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTextColor(Color.rgb(155, 164, 176));
        statusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(10);
        root.addView(statusText, sp);

        root.addView(bigButton("📷  看画面 / 回放", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ensureConfigured()) startActivity(new Intent(MainActivity.this, RecordingsActivity.class));
            }
        }));

        root.addView(bigButton("⚙  摄像头设置", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ensureConfigured()) startActivity(new Intent(MainActivity.this, CameraSettingsActivity.class));
            }
        }));

        Button manage = new Button(this);
        manage.setText("设备管理 / 添加摄像头");
        manage.setTextSize(16);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        mp.topMargin = dp(24);
        manage.setLayoutParams(mp);
        manage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DeviceManagerActivity.class));
            }
        });
        root.addView(manage);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadDevices();
        refreshStatus();
    }

    private void reloadDevices() {
        final List<Config.Device> devices = Config.devices(this);
        List<String> names = new ArrayList<>();
        for (Config.Device d : devices) names.add(d.name.isEmpty() ? d.host : d.name);
        if (names.isEmpty()) names.add("（还没添加设备）");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        deviceSpinner.setAdapter(adapter);
        spinnerInit = false;
        int active = Config.activeIndex(this);
        if (active < devices.size()) deviceSpinner.setSelection(active);
        spinnerInit = true;
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!spinnerInit) return;
                if (position < devices.size()) {
                    Config.setActiveIndex(MainActivity.this, position);
                    refreshStatus();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void refreshStatus() {
        if (!Config.configured(this)) {
            statusText.setText("还没有配置摄像头\n请点下方「设备管理」添加");
        } else {
            Config.Device d = Config.activeDevice(this);
            String pwd = d.password.isEmpty() ? "无密码" : "有密码";
            statusText.setText("当前：" + d.host + ":" + d.port
                    + "（" + pwd + "，摄像头" + d.camera + "）");
        }
    }

    private boolean ensureConfigured() {
        if (Config.configured(this)) return true;
        refreshStatus();
        startActivity(new Intent(this, DeviceManagerActivity.class));
        return false;
    }

    private Button bigButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(28);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(110));
        lp.topMargin = dp(24);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
