package com.zyyme.storageserver;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 咩存储 主界面：FTP / WebDAV 开关与账号端口设置、存储目录选择、
 * 服务启停、状态与日志查看。改动保存后立即生效（自动重启服务）。
 */
public class MainActivity extends Activity {
    private static final int REQ_PICK_DIR = 21;

    private SharedPreferences prefs;
    private Switch ftpSwitch, davSwitch, autostartSwitch;
    private EditText ftpPort, ftpUser, ftpPass, davPort, davUser, davPass;
    private TextView statusText, pathText, logText, domainText;
    private BroadcastReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        ScrollView outer = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        outer.addView(root);

        TextView title = new TextView(this);
        title.setText("咩存储（FTP / WebDAV 存储服务器）");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.rgb(88, 166, 255));
        statusText.setPadding(0, dp(10), 0, 0);
        root.addView(statusText);

        // ---------- FTP ----------
        ftpSwitch = sectionSwitch(root, "开启 FTP 服务器（摄像头常用 FTP 上传录像）",
                "ftp_on", true);
        LinearLayout ftpBox = box(root);
        ftpPort = input(ftpBox, "FTP 端口（默认 2121）",
                String.valueOf(prefs.getInt("ftp_port", 2121)), InputType.TYPE_CLASS_NUMBER);
        ftpUser = input(ftpBox, "FTP 用户名（留空 = 不校验密码）",
                prefs.getString("ftp_user", ""));
        ftpPass = input(ftpBox, "FTP 密码", prefs.getString("ftp_pass", ""));
        ftpPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ftpSwitch.setOnCheckedChangeListener((new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                ftpBox.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        }));
        ftpBox.setVisibility(ftpSwitch.isChecked() ? View.VISIBLE : View.GONE);

        // ---------- WebDAV ----------
        davSwitch = sectionSwitch(root, "开启 WebDAV 服务器（「工作咩闹钟」录像上传用）",
                "dav_on", true);
        LinearLayout davBox = box(root);
        davPort = input(davBox, "WebDAV 端口（默认 5005）",
                String.valueOf(prefs.getInt("dav_port", 5005)), InputType.TYPE_CLASS_NUMBER);
        davUser = input(davBox, "WebDAV 用户名（留空 = 不校验密码）",
                prefs.getString("dav_user", ""));
        davPass = input(davBox, "WebDAV 密码", prefs.getString("dav_pass", ""));
        davPass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        davSwitch.setOnCheckedChangeListener((new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                davBox.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
        }));
        davBox.setVisibility(davSwitch.isChecked() ? View.VISIBLE : View.GONE);

        // ---------- 存储目录 ----------
        TextView dirLabel = label("录像保存目录");
        root.addView(dirLabel);
        pathText = new TextView(this);
        pathText.setTextSize(13);
        pathText.setTextColor(Color.rgb(155, 164, 176));
        root.addView(pathText);
        Button pickDir = new Button(this);
        pickDir.setText("更换目录（可选 SD 卡）…");
        pickDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(intent, REQ_PICK_DIR);
            }
        });
        root.addView(pickDir);
        Button resetDir = new Button(this);
        resetDir.setText("恢复默认目录");
        resetDir.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().remove("root_uri").apply();
                refreshPath();
                applyAndRestart();
            }
        });
        root.addView(resetDir);

        // ---------- 其他 ----------
        autostartSwitch = sectionSwitch(root, "开机自动启动服务", "autostart", true);

        // ---------- 局域网域名 ----------
        TextView domainLabel = label("局域网域名（IP 变了也能用）");
        root.addView(domainLabel);
        domainText = new TextView(this);
        domainText.setTextSize(14);
        domainText.setTextColor(Color.rgb(88, 166, 255));
        domainText.setPadding(dp(12), 0, 0, 0);
        root.addView(domainText);
        Button regen = new Button(this);
        regen.setText("重新生成域名");
        regen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().remove("lan_name").apply();
                applyAndRestart();
                refreshStatus();
                toast("域名已重新生成");
            }
        });
        root.addView(regen);

        // ---------- 保存 ----------
        Button save = new Button(this);
        save.setText("保存并启动服务");
        save.setTextSize(18);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        slp.topMargin = dp(16);
        save.setLayoutParams(slp);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveAndApply();
            }
        });
        root.addView(save);

        TextView help = new TextView(this);
        help.setTextSize(13);
        help.setTextColor(Color.rgb(120, 128, 140));
        help.setPadding(0, dp(12), 0, 0);
        help.setText("使用方法：\n"
                + "• 工作咩闹钟录像上传：NAS录像设置 → WebDAV 地址填 "
                + "http://本机IP:端口/目录（如 http://192.168.1.20:5005/recordings），"
                + "用户名密码与上面一致。\n"
                + "• 摄像头 FTP 上传：服务器地址填本机 IP，端口默认 2121。\n"
                + "• 本机 IP 见下方状态栏。");
        root.addView(help);

        TextView logLabel = label("运行日志");
        logLabel.setPadding(0, dp(14), 0, dp(4));
        root.addView(logLabel);
        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTextColor(Color.rgb(139, 148, 158));
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(logText);

        setContentView(outer);
        refreshPath();
        refreshStatus();

        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                refreshLog();
                refreshStatus();
            }
        };
        IntentFilter filter = new IntentFilter("com.zyyme.storageserver.LOG");
        filter.setPriority(999);
        if (Build.VERSION.SDK_INT >= 33) {
            // Context.RECEIVER_NOT_EXPORTED（0x4）：仅接收本应用广播
            registerReceiver(logReceiver, filter, 0x4);
        } else {
            registerReceiver(logReceiver, filter);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshLog();
        refreshPath();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(logReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void saveAndApply() {
        int fport = parsePort(ftpPort, 2121);
        int dport = parsePort(davPort, 5005);
        if (fport <= 0 || dport <= 0) return;
        if (ftpSwitch.isChecked() && davSwitch.isChecked() && fport == dport) {
            toast("FTP 和 WebDAV 端口不能相同");
            return;
        }
        prefs.edit()
                .putBoolean("ftp_on", ftpSwitch.isChecked())
                .putBoolean("dav_on", davSwitch.isChecked())
                .putBoolean("autostart", autostartSwitch.isChecked())
                .putInt("ftp_port", fport)
                .putInt("dav_port", dport)
                .putString("ftp_user", ftpUser.getText().toString().trim())
                .putString("ftp_pass", ftpPass.getText().toString())
                .putString("dav_user", davUser.getText().toString().trim())
                .putString("dav_pass", davPass.getText().toString())
                .apply();
        applyAndRestart();
        toast("已保存并生效");
    }

    private void applyAndRestart() {
        Intent service = new Intent(this, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        ServerService instance = ServerService.getInstance();
        if (instance != null) instance.applySettings();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DIR && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                toast("无法获得目录长期授权");
                return;
            }
            prefs.edit().putString("root_uri", uri.toString()).apply();
            refreshPath();
            applyAndRestart();
            toast("存储目录已更换");
        }
    }

    private void refreshPath() {
        ServerService instance = ServerService.getInstance();
        if (instance != null) {
            pathText.setText(instance.storageDisplayPath());
        } else {
            String tree = prefs.getString("root_uri", "");
            pathText.setText(tree.isEmpty() ? "（默认 App 目录）" : tree);
        }
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("本机 IP：").append(localIp()).append('\n');
        if (prefs.getBoolean("ftp_on", true)) {
            sb.append("FTP：").append(prefs.getInt("ftp_port", 2121))
                    .append(authNote(prefs.getString("ftp_user", ""))).append('\n');
        }
        if (prefs.getBoolean("dav_on", true)) {
            sb.append("WebDAV：").append(prefs.getInt("dav_port", 5005))
                    .append(authNote(prefs.getString("dav_user", ""))).append('\n');
        }
        ServerService instance = ServerService.getInstance();
        sb.append("服务：").append(instance != null ? "运行中" : "未运行（点「保存并启动服务」）");
        statusText.setText(sb.toString());
        // 域名展示
        String name = instance != null ? instance.lanDomainText()
                : prefs.getString("lan_name", "");
        if (name.isEmpty()) name = "（启动服务后自动生成）";
        String davHost = prefs.getBoolean("dav_on", true)
                ? "\nWebDAV：http://" + name + ".local:" + prefs.getInt("dav_port", 5005) : "";
        String ftpHost = prefs.getBoolean("ftp_on", true)
                ? "\nFTP 主机名：" + name + ".local（端口 " + prefs.getInt("ftp_port", 2121) + "）" : "";
        domainText.setText("域名：" + name + ".local" + davHost + ftpHost);
    }

    private String authNote(String user) {
        return user.isEmpty() ? "（无密码）" : "（要密码）";
    }

    private void refreshLog() {
        ServerService instance = ServerService.getInstance();
        if (instance == null) {
            logText.setText("（服务未运行）");
            return;
        }
        List<String> lines = instance.getLogs();
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, lines.size() - 30);
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        logText.setText(sb.toString());
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            List<String> ips = new ArrayList<>();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
            return ips.isEmpty() ? "未连接网络" : android.text.TextUtils.join(" , ", ips);
        } catch (Exception e) {
            return "未知";
        }
    }

    // ---------- UI 组装小件 ----------

    private Switch sectionSwitch(LinearLayout root, String text, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(Color.WHITE);
        s.setTextSize(16);
        s.setChecked(prefs.getBoolean(key, def));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        s.setLayoutParams(lp);
        root.addView(s);
        return s;
    }

    private LinearLayout box(LinearLayout root) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(12), 0, 0, 0);
        root.addView(b);
        return b;
    }

    private TextView label(String text) {
        TextView l = new TextView(this);
        l.setText(text);
        l.setTextSize(15);
        l.setTextColor(Color.rgb(200, 208, 216));
        l.setPadding(0, dp(12), 0, dp(2));
        return l;
    }

    private EditText input(LinearLayout root, String hint, String value) {
        return input(root, hint, value,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    private EditText input(LinearLayout root, String hint, String value, int inputType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(16);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(120, 128, 140));
        e.setSingleLine(true);
        e.setInputType(inputType);
        root.addView(e, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return e;
    }

    private int parsePort(EditText field, int fallback) {
        String s = field.getText().toString().trim();
        if (s.isEmpty()) return fallback;
        try {
            int p = Integer.parseInt(s);
            if (p < 1 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            toast("端口必须是 1-65535 的数字");
            return -1;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
