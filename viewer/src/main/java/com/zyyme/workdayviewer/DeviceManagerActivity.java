package com.zyyme.workdayviewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 设备管理：列出已保存的摄像头设备，点击进入编辑，底部按钮添加新设备。
 */
public class DeviceManagerActivity extends Activity {
    private ListView listView;
    private List<Config.Device> devices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("我的摄像头设备");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= devices.size()) return;
                Intent intent = new Intent(DeviceManagerActivity.this, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_INDEX, position);
                startActivity(intent);
            }
        });

        Button scan = new Button(this);
        scan.setText("🔍 扫描添加摄像头");
        scan.setTextSize(20);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        slp.topMargin = dp(12);
        scan.setLayoutParams(slp);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(DeviceManagerActivity.this, ScanActivity.class));
            }
        });
        root.addView(scan);

        Button add = new Button(this);
        add.setText("＋ 添加设备");
        add.setTextSize(20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(12);
        add.setLayoutParams(lp);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(DeviceManagerActivity.this, SettingsActivity.class);
                intent.putExtra(SettingsActivity.EXTRA_INDEX, -1);
                startActivity(intent);
            }
        });
        root.addView(add);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        devices = Config.devices(this);
        int active = Config.activeIndex(this);
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < devices.size(); i++) {
            Config.Device d = devices.get(i);
            String mark = (i == active) ? "● " : "○ ";
            labels.add(mark + d.name + "（" + d.host + ":" + d.port + "）");
        }
        if (devices.isEmpty()) {
            // 空列表时展示提示，不再自动跳添加页（旧逻辑会把用户卡死在添加页回不来，
            // 扫描按钮也永远没有机会点到）。
            labels.add("（还没有设备，点下方「扫描添加摄像头」或「添加设备」）");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
