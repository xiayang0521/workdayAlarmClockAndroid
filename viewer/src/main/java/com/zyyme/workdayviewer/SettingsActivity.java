package com.zyyme.workdayviewer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 设备编辑页：新增或修改一台摄像头设备（名称/地址/端口/密码/摄像头序号）。
 * 通过 intent extra "index" 区分：-1 = 新增，>=0 = 修改该序号设备。
 */
public class SettingsActivity extends Activity {
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_NAME = "name";

    private int deviceIndex = -1;
    private EditText nameInput, hostInput, portInput, passwordInput, cameraInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deviceIndex = getIntent().getIntExtra(EXTRA_INDEX, -1);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(deviceIndex < 0 ? "添加设备" : "修改设备");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        Config.Device current = new Config.Device();
        if (deviceIndex >= 0) {
            List<Config.Device> list = Config.devices(this);
            if (deviceIndex < list.size()) current = list.get(deviceIndex);
        }
        nameInput = addInput(root, "设备名称（如：客厅摄像头）", current.name);
        hostInput = addInput(root, "摄像头地址（IP 或域名）", current.host);
        portInput = addInput(root, "端口（默认 8880）", current.port == 8880 ? "" : String.valueOf(current.port));
        passwordInput = addInput(root, "访问密码（没有可留空）", current.password);
        cameraInput = addInput(root, "摄像头序号（默认 1）", current.camera == 1 ? "" : String.valueOf(current.camera));
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        applyPrefill();

        TextView tip = new TextView(this);
        tip.setTextSize(14);
        tip.setTextColor(Color.rgb(155, 164, 176));
        tip.setText("\n地址示例：\n192.168.1.31\nworkday483920（局域网域名，" +
                "在摄像头 App 网页后台顶部可看到完整域名）\n\n提示：可直接粘贴 http:// 开头的完整地址，会自动提取。");
        root.addView(tip);

        Button test = new Button(this);
        test.setText("测试连接");
        test.setTextSize(20);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        tp.topMargin = dp(20);
        test.setLayoutParams(tp);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });
        root.addView(test);

        Button save = new Button(this);
        save.setText("保存");
        save.setTextSize(22);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        lp.topMargin = dp(24);
        save.setLayoutParams(lp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(save);

        if (deviceIndex >= 0) {
            Button delete = new Button(this);
            delete.setText("删除这台设备");
            delete.setTextSize(18);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
            dp2.topMargin = dp(12);
            delete.setLayoutParams(dp2);
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteDevice();
                }
            });
            root.addView(delete);
        }

        // 返回按钮：添加/编辑设备页没有系统返回键以外的退出路径时，
        // 点这里直接回到设备列表，避免被空列表自动跳转卡死。
        Button back = new Button(this);
        back.setText("← 返回设备列表");
        back.setTextSize(18);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        bp.topMargin = dp(12);
        back.setLayoutParams(bp);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(back);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private EditText addInput(LinearLayout root, String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(18);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(120, 128, 140));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(16);
        root.addView(e, lp);
        return e;
    }

    /** 扫描结果预填：仅新增设备时生效。 */
    private void applyPrefill() {
        if (deviceIndex >= 0) return;
        String host = getIntent().getStringExtra(EXTRA_HOST);
        if (host != null && !host.trim().isEmpty()) {
            hostInput.setText(host.trim());
        }
        int port = getIntent().getIntExtra(EXTRA_PORT, 0);
        if (port > 0) {
            portInput.setText(String.valueOf(port));
        }
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name != null && !name.trim().isEmpty()) {
            nameInput.setText(name.trim());
        }
    }

    /** 测试连接：解析地址后请求录像列表，200=成功，404=密码错误。 */
    private void testConnection() {
        final String host = cleanHost();
        if (host.isEmpty()) {
            toast("请填写摄像头地址");
            return;
        }
        final int port = parsePort();
        if (port <= 0) return;
        final String password = passwordInput.getText().toString().trim();
        toast("正在测试连接…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String message;
                try {
                    Resolver.Endpoint ep = Resolver.resolve(SettingsActivity.this, host, port);
                    String pwdPart = password.isEmpty() ? "" : java.net.URLEncoder.encode(password, "UTF-8") + "/";
                    java.net.URL url = new java.net.URL("http://" + ep.host + ":" + ep.port + "/" + pwdPart + "rec/list");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code == 200) {
                        ok = true;
                        message = "连接成功";
                    } else if (code == 404) {
                        ok = false;
                        message = "密码错误或录像服务不可用";
                    } else {
                        ok = false;
                        message = "摄像头返回错误：" + code;
                    }
                } catch (Exception e) {
                    String detail = e.getMessage();
                    message = "连接失败：" + (detail == null ? e.getClass().getSimpleName() : detail);
                }
                final boolean testOk = ok;
                final String testMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(testMessage);
                        if (testOk && deviceIndex < 0) {
                            toast("已加入设备列表");
                            save();
                        }
                    }
                });
            }
        }).start();
    }

    /** 提取输入框里的主机名（去掉协议头和路径，拆出端口写入端口框）。 */
    private String cleanHost() {
        String host = hostInput.getText().toString().trim();
        if (host.contains("://")) {
            host = host.substring(host.indexOf("://") + 3);
        }
        host = host.replaceAll("/.*$", "");
        if (host.contains(":")) {
            String[] parts = host.split(":", 2);
            host = parts[0];
            if (portInput.getText().toString().trim().isEmpty()) {
                portInput.setText(parts[1]);
            }
        }
        return host;
    }

    private int parsePort() {
        int port = 8880;
        String portText = portInput.getText().toString().trim();
        if (!portText.isEmpty()) {
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                toast("端口必须是数字");
                return -1;
            }
        }
        return port;
    }

    private void save() {
        String name = nameInput.getText().toString().trim();
        String host = cleanHost();
        if (host.isEmpty()) {
            toast("请填写摄像头地址");
            return;
        }
        int port = parsePort();
        if (port <= 0) return;
        int camera = 1;
        String cameraText = cameraInput.getText().toString().trim();
        if (!cameraText.isEmpty()) {
            try {
                camera = Integer.parseInt(cameraText);
            } catch (NumberFormatException e) {
                toast("摄像头序号必须是数字");
                return;
            }
        }
        if (name.isEmpty()) name = host;

        List<Config.Device> list = Config.devices(this);
        Config.Device d = new Config.Device();
        d.name = name;
        d.host = host;
        d.port = port;
        d.password = passwordInput.getText().toString().trim();
        d.camera = camera;
        if (deviceIndex >= 0 && deviceIndex < list.size()) {
            list.set(deviceIndex, d);
        } else {
            list.add(d);
            Config.setActiveIndex(this, list.size() - 1);
        }
        Config.saveDevices(this, list);
        toast("已保存");
        finish();
    }

    private void deleteDevice() {
        List<Config.Device> list = Config.devices(this);
        if (deviceIndex >= 0 && deviceIndex < list.size()) {
            list.remove(deviceIndex);
            Config.saveDevices(this, list);
            Config.setActiveIndex(this, 0);
            toast("已删除");
        }
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
