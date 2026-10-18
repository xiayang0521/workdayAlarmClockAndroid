package com.zyyme.workdayviewer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 局域网扫描：发现 _http._tcp. 服务（咩摄像头 workdayXXXXXX 及其他网络设备），
 * 扫描约 6 秒，点击结果自动带入添加设备页（密码仍需手动输入）。
 */
public class ScanActivity extends Activity {
    private static final String SERVICE_TYPE = "_http._tcp.";
    private static final int SCAN_SECONDS = 6;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, NsdServiceInfo> found = new LinkedHashMap<>();
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener discovery;
    private ListView listView;
    private TextView statusView;
    private ArrayAdapter<String> adapter;
    private final List<String> labels = new ArrayList<>();
    private boolean picking = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("扫描局域网摄像头");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.rgb(155, 164, 176));
        statusView.setText("\n正在扫描局域网摄像头（约 " + SCAN_SECONDS + " 秒）…\n请确保摄像头设备已开机并运行咩摄像头服务。\n");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        statusView.setLayoutParams(slp);
        root.addView(statusView);

        listView = new ListView(this);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= labels.size()) return;
                String name = labels.get(position);
                onPick(name);
            }
        });

        setContentView(root);
        startScan();
    }

    private void startScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            statusView.setText("\n当前系统版本过低，不支持自动扫描。\n请手动输入摄像头地址。\n");
            return;
        }
        nsdManager = (NsdManager) getSystemService(NSD_SERVICE);
        if (nsdManager == null) {
            statusView.setText("\n当前设备不支持网络发现。\n请手动输入摄像头地址。\n");
            return;
        }
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                finishScan("扫描启动失败（错误码 " + errorCode + "），请手动添加设备。");
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                final String name = serviceInfo.getServiceName();
                if (name == null) return;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!found.containsKey(name)) {
                            found.put(name, serviceInfo);
                            labels.add(name);
                            adapter.notifyDataSetChanged();
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
            }
        };
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
                } catch (Exception e) {
                    finishScan("扫描启动失败：" + e.getMessage() + "，请手动添加设备。");
                }
            }
        });
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopDiscovery();
                int count = found.size();
                if (count == 0) {
                    finishScan("没有发现局域网设备。\n请确认摄像头手机与这台手机连接同一个 Wi-Fi，再试一次。");
                } else {
                    statusView.setText("\n发现 " + count + " 个设备，点击要添加的摄像头：\n");
                }
            }
        }, SCAN_SECONDS * 1000L);
    }

    private void stopDiscovery() {
        if (nsdManager != null && discovery != null) {
            try {
                nsdManager.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
        }
    }

    private void finishScan(String message) {
        stopDiscovery();
        statusView.setText("\n" + message + "\n");
    }

    /** 点击某个服务：解析出端口后跳到添加设备页。 */
    private void onPick(final String name) {
        if (picking) return;
        picking = true;
        statusView.setText("\n正在解析 " + name + " …\n");
        new Thread(new Runnable() {
            @Override
            public void run() {
                Resolver.Endpoint ep = Resolver.resolve(ScanActivity.this, name, 8880);
                final int port = ep != null ? ep.port : 8880;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(ScanActivity.this, SettingsActivity.class);
                        intent.putExtra(SettingsActivity.EXTRA_INDEX, -1);
                        intent.putExtra(SettingsActivity.EXTRA_HOST, name);
                        intent.putExtra(SettingsActivity.EXTRA_PORT, port);
                        intent.putExtra(SettingsActivity.EXTRA_NAME, name);
                        startActivity(intent);
                        finish();
                    }
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
