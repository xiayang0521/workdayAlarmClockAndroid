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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 摄像头设置页：从摄像头后台读取全部可设置项（旋转/编码/帧率/清晰度/录音/
 * 静止策略/灵敏度/存储与配额），修改后一键下发，摄像头服务会自动重启生效。
 * 需要摄像头后台 v40.20 及以上版本。
 */
public class CameraSettingsActivity extends Activity {
    private LinearLayout container;
    private TextView statusText;
    private Button saveBtn;

    private RadioGroup rotationGroup, codecGroup, fpsGroup, heightGroup, policyGroup, sensGroup;
    private Switch audioSwitch;

    // 录像存储分区
    private TextView storageStatusText;
    private Switch usbStorageSwitch;
    private RadioGroup quotaGroup;
    private EditText reserveInput, limitInput, cacheInput;
    private boolean storageSupported = false;
    // 存储配置读取失败（超时/旧固件）时为 true：保存绝不下发 nas*/usb* 字段，
    // 否则空表单会把相机端已配好的 NAS、USB 存储全部清空/关掉。
    private boolean storageReadFailed = false;

    // NAS（WebDAV）远程配置
    private EditText nasUrlInput, nasUserInput, nasPassInput, nasDirInput;
    private Button nasTestBtn;
    private boolean nasHasPassword = false;
    // 局域网 NAS 自动搜索
    private Button nasScanBtn;
    private TextView nasScanStatus;
    // 修改访问密码
    private EditText camPasswordInput;
    private Button camPasswordBtn;
    // 当前设备解析后的地址（修改密码后重建请求基址用）
    private String endpointHost;
    private int endpointPort = 8880;

    // USB 远程选目录（卷枚举 + 子目录）
    private Spinner usbVolumeSpinner;
    private TextView usbVolumeHint;
    private EditText usbSubDirInput;
    private EditText usbFullPathInput;
    // Spinner 选项对应的卷绝对路径，"手动输入路径"项为 null。
    private String[] usbVolumePaths = new String[0];
    private static final String MANUAL_VOLUME_CHOICE = "手动输入完整路径（高级）";
    private boolean volumesSupported = false;

    // 当前设备的存储接口基址（供测试连接等异步请求复用）
    private String storageBaseUrl;

    private boolean loaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView outer = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 17, 23));
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        outer.addView(root);

        TextView title = new TextView(this);
        title.setText("摄像头设置");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextColor(Color.rgb(155, 164, 176));
        statusText.setPadding(0, dp(10), 0, 0);
        statusText.setText("正在读取设置…");
        root.addView(statusText);

        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container);

        saveBtn = new Button(this);
        saveBtn.setText("保存并生效");
        saveBtn.setTextSize(20);
        saveBtn.setEnabled(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(20);
        saveBtn.setLayoutParams(lp);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        root.addView(saveBtn);

        setContentView(outer);
        load();
    }

    private void load() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Config.Device device = Config.activeDevice(CameraSettingsActivity.this);
                    Resolver.Endpoint endpoint = Resolver.resolve(CameraSettingsActivity.this,
                            device.host, device.port);
                    final String prefix = device.password.isEmpty() ? "" : device.password + "/";
                    final String base = "http://" + endpoint.host + ":" + endpoint.port
                            + "/" + prefix;
                    URL url = new URL(base + "cam/settings");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        onError("读取失败（HTTP " + code + "），请检查地址密码，" +
                                "且摄像头需 v40.20 及以上版本");
                        return;
                    }
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    in.close();
                    conn.disconnect();
                    final JSONObject json = new JSONObject(buf.toString("UTF-8"));
                    endpointHost = endpoint.host;
                    endpointPort = endpoint.port;

                    // 存储配置单独读取：旧版摄像头后台没有该接口时不影响主设置页
                    final JSONObject storage = fetchJson(base + "cam/storage");
                    // 卷枚举是新增接口：旧固件返回 null，USB 选择退化为手动输入路径
                    final JSONObject volumes = fetchJson(base + "cam/storage/volumes");

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            storageBaseUrl = base;
                            buildUi(json);
                            buildStorageUi(storage, volumes);
                        }
                    });
                } catch (Exception e) {
                    onError("读取失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
        }, "cam-settings-load").start();
    }

    /** GET 一个 JSON 接口；任何失败都返回 null（调用方按"不支持"处理）。 */
    private JSONObject fetchJson(String urlText) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000); // NAS 统计 + U 盘统计可能很慢，相机端有缓存但首刷仍可能超时
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            in.close();
            return new JSONObject(buf.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void onError(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.setText(msg);
            }
        });
    }

    private void buildUi(JSONObject json) {
        container.removeAllViews();
        int rotation = json.optInt("rotation", 0);
        String codec = json.optString("codec", "h264");
        int fps = json.optInt("fps", 20);
        int height = json.optInt("height", 1080);
        boolean audio = json.optBoolean("audio", true);
        int policy = json.optInt("policy", 0);
        int sens = json.optInt("sens", 1);

        rotationGroup = addRadio("画面旋转角度（摄像头倒挂选 180°）",
                new String[]{"0°", "90°", "180°", "270°"},
                new int[]{0, 90, 180, 270}, rotation);
        codecGroup = addRadio("视频编码",
                new String[]{"H.264（兼容最好，推荐）", "H.265 / HEVC（省空间，旧设备可能放不了）"},
                new int[]{0, 1}, "h265".equals(codec) ? 1 : 0);
        fpsGroup = addRadio("录像帧率（每秒帧数）",
                new String[]{"10 fps", "15 fps", "20 fps（默认）", "25 fps", "30 fps"},
                new int[]{10, 15, 20, 25, 30}, fps);
        heightGroup = addRadio("录像清晰度",
                new String[]{"720p（省空间）", "1080p（清晰，默认）"},
                new int[]{720, 1080}, height);
        policyGroup = addRadio("画面静止时策略",
                new String[]{"全录（不变，默认）",
                        "A 抽帧录像（静止时只存关键帧，约 1fps，回看是快放效果）",
                        "B 动态码率（静止时降到 100kbps，画面连续）",
                        "A+B（抽帧 + 低码率）"},
                new int[]{0, 1, 2, 3}, policy);
        sensGroup = addRadio("静止检测灵敏度",
                new String[]{"高（0.3% 像素变动即算动态，最敏感）", "中（0.6%，默认）", "低（1.5%，不易误触发）"},
                new int[]{0, 1, 2}, sens);

        addLabel("录像同时录音");
        audioSwitch = new Switch(this);
        audioSwitch.setText("录音（关闭后录像没有声音）");
        audioSwitch.setTextColor(Color.WHITE);
        audioSwitch.setChecked(audio);
        container.addView(audioSwitch);

        statusText.setText("设置已读取，修改后点下方「保存并生效」。");
        saveBtn.setEnabled(true);
        loaded = true;
    }

    // ---------- 录像存储分区 ----------

    /**
     * 构建「录像存储」分区：空间占用总览、USB 开关、配额方式与阈值。
     * storage 为 null 表示摄像头后台没有该接口（旧版 App），只展示提示。
     */
    private void buildStorageUi(JSONObject storage, JSONObject volumes) {
        addSectionLabel("录像存储");

        storageStatusText = new TextView(this);
        storageStatusText.setTextSize(14);
        storageStatusText.setTextColor(Color.rgb(255, 196, 120));
        storageStatusText.setPadding(0, dp(4), 0, dp(8));
        container.addView(storageStatusText);

        if (storage == null) {
            // NAS 无响应等原因导致读取失败：表单照常渲染（方便看提示），
            // 但标记 storageReadFailed，保存时跳过整个存储分区，绝不用空值覆盖相机配置。
            storageSupported = true;
            storageReadFailed = true;
            storageStatusText.setText("存储状态读取失败（摄像头响应慢或旧固件），" +
                    "仍可修改摄像头参数并保存；存储设置本次不会变动，" +
                    "重进本页面待读取成功后再修改。");
            storage = new JSONObject();
        } else {
            storageSupported = true;
            storageReadFailed = false;
        }

        // 空间占用总览
        StringBuilder summary = new StringBuilder();
        JSONObject local = storage.optJSONObject("local");
        if (local != null) {
            summary.append("手机暂存已用 ").append(formatBytes(local.optLong("usedBytes")));
            summary.append("（上限 ").append(local.optInt("cacheMb", 512)).append(" MB）\n");
        }
        JSONObject nas = storage.optJSONObject("nas");
        if (nas != null) {
            if (nas.optBoolean("configured")) {
                summary.append("NAS 已用 ").append(
                        nas.has("usedBytes") ? formatBytes(nas.optLong("usedBytes")) : "统计失败");
                summary.append("\n");
            } else {
                summary.append("NAS 未配置（在下方填写 WebDAV 信息）\n");
            }
        }
        JSONObject usb = storage.optJSONObject("usb");
        if (usb != null) {
            if (!usb.optBoolean("enabled")) {
                summary.append("USB 未开启\n");
            } else if (!usb.optBoolean("configured")) {
                summary.append("USB 未选择目录（在下方选择 U 盘卷与子目录）\n");
            } else if (!usb.optBoolean("ready")) {
                summary.append("USB 未就绪（U 盘已拔出或不可写）\n");
            } else {
                summary.append("USB 已用 ").append(formatBytes(usb.optLong("usedBytes")));
                if (usb.has("availableBytes")) {
                    summary.append("，剩余 ").append(formatBytes(usb.optLong("availableBytes")));
                }
                summary.append("\n");
            }
        }
        storageStatusText.setText(summary.toString().trim());
        storageStatusText.setTextColor(Color.rgb(155, 164, 176));

        // ---------- NAS（WebDAV）远程配置表单 ----------
        addLabel("NAS（WebDAV）地址，例如 http://192.168.1.10:5005 或群晖 http://主机:5000");
        nasScanBtn = new Button(this);
        nasScanBtn.setText("搜索局域网 NAS（自动查找咩存储）");
        nasScanBtn.setTextSize(15);
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        scanLp.topMargin = dp(2);
        nasScanBtn.setLayoutParams(scanLp);
        nasScanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scanNas();
            }
        });
        container.addView(nasScanBtn);
        nasScanStatus = new TextView(this);
        nasScanStatus.setTextSize(12);
        nasScanStatus.setTextColor(Color.rgb(120, 128, 140));
        nasScanStatus.setPadding(0, dp(2), 0, dp(4));
        container.addView(nasScanStatus);
        String nasUrl = nas == null ? "" : nas.optString("url", "");
        String nasUser = nas == null ? "" : nas.optString("username", "");
        String nasDir = nas == null ? "" : nas.optString("dir", "");
        nasHasPassword = nas != null && nas.optBoolean("hasPassword", false);
        nasUrlInput = addTextInput("NAS 服务器地址（http:// 或 https:// 开头）", nasUrl, false);
        nasUserInput = addTextInput("用户名（无需认证可留空）", nasUser, false);
        // 密码不回显明文：已配置时给占位提示，留空保存即不修改原密码。
        nasPassInput = addTextInput(
                nasHasPassword ? "密码（已保存，留空表示不修改）" : "密码", "", true);
        nasDirInput = addTextInput("NAS 上的录像目录（如 workday/record，可留空）", nasDir, false);

        nasTestBtn = new Button(this);
        nasTestBtn.setText("测试 NAS 连接");
        nasTestBtn.setTextSize(16);
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        testLp.topMargin = dp(6);
        nasTestBtn.setLayoutParams(testLp);
        nasTestBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testNasConnection();
            }
        });
        container.addView(nasTestBtn);

        // ---------- USB 开关与远程选目录 ----------
        addLabel("存储目标（NAS 与 USB 可同时开启，录像双写）");
        usbStorageSwitch = new Switch(this);
        usbStorageSwitch.setText("同时复制到 USB 设备（OTG U 盘）");
        usbStorageSwitch.setTextColor(Color.WHITE);
        usbStorageSwitch.setChecked(usb != null && usb.optBoolean("enabled"));
        container.addView(usbStorageSwitch);

        addLabel("U 盘存储卷（选择检测到的卷，或手动输入路径）");
        usbVolumeSpinner = new Spinner(this);
        container.addView(usbVolumeSpinner);
        usbVolumeHint = new TextView(this);
        usbVolumeHint.setTextSize(12);
        usbVolumeHint.setTextColor(Color.rgb(120, 128, 140));
        usbVolumeHint.setPadding(0, dp(2), 0, dp(2));
        container.addView(usbVolumeHint);
        populateVolumeSpinner(usb, volumes);

        usbSubDirInput = addTextInput(
                "卷内子目录（如 workday，留空则存到卷根目录）",
                deriveSubDir(usb == null ? "" : usb.optString("fsPath", "")), false);

        TextView usbHint = new TextView(this);
        usbHint.setText("保存时会在相机端实测目录可写；无屏设备无需再插屏幕授权。");
        usbHint.setTextSize(12);
        usbHint.setTextColor(Color.rgb(120, 128, 140));
        usbHint.setPadding(0, dp(2), 0, dp(4));
        container.addView(usbHint);

        // 配额方式与阈值
        JSONObject quota = storage.optJSONObject("quota");
        int quotaMode = quota == null ? 0 : quota.optInt("mode", 0);
        quotaGroup = addRadioBase("空间限制方式（NAS 与 USB 共用，超限时删最旧录像）",
                new String[]{"保留可用空间（可用量低于下限就删最旧）",
                        "占用上限（总占用超过上限就删最旧）"},
                new int[]{0, 1}, quotaMode, 2000);

        reserveInput = addNumberInput("保留可用空间下限（MB）",
                quota == null ? 1024 : quota.optInt("reserveMb", 1024));
        limitInput = addNumberInput("占用上限（MB）",
                quota == null ? 10240 : quota.optInt("limitMb", 10240));
        cacheInput = addNumberInput("手机暂存上限（MB，投递完成后自动清理）",
                local == null ? 512 : local.optInt("cacheMb", 512));

        // 访问密码区块（本机保存的摄像头密码，随时可改）
        buildPasswordSection();
    }

    /** 单行文本输入框；isPassword 时遮挡显示（用于 NAS 密码）。 */
    private EditText addTextInput(String hint, String value, boolean isPassword) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.rgb(120, 128, 140));
        input.setTextSize(16);
        if (isPassword) {
            input.setInputType(InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        }
        input.setSingleLine(true);
        input.setBackgroundColor(Color.rgb(30, 38, 48));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.bottomMargin = dp(6);
        input.setLayoutParams(lp);
        container.addView(input);
        return input;
    }

    /**
     * 用相机端返回的卷列表填充下拉框：可写卷在前并标注容量，只读/系统卷也列出但标注不可写。
     * 始终附加一个"手动输入完整路径"项，兼容固件未枚举到但用户知道路径的情况。
     * 旧固件（volumes 为 null）时只提供手动输入。
     */
    private void populateVolumeSpinner(JSONObject usb, JSONObject volumes) {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> paths = new ArrayList<>();
        labels.add("不指定 USB 目录（关闭远程位置）");
        paths.add("");

        String currentFsPath = usb == null ? "" : usb.optString("fsPath", "");
        int selectIndex = 0;
        int writableVolumeCount = 0;

        if (volumes != null) {
            JSONArray arr = volumes.optJSONArray("volumes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject vol = arr.optJSONObject(i);
                    if (vol == null) continue;
                    String path = vol.optString("path", "");
                    if (path.isEmpty()) continue;
                    boolean writable = vol.optBoolean("writable", false);
                    StringBuilder label = new StringBuilder();
                    label.append(writable ? "可写" : "只读/不可写").append(" · ").append(path);
                    if (vol.has("totalBytes")) {
                        label.append("（共 ").append(formatBytes(vol.optLong("totalBytes")));
                        if (vol.has("availableBytes")) {
                            label.append("，剩 ").append(formatBytes(vol.optLong("availableBytes")));
                        }
                        label.append("）");
                    }
                    labels.add(label.toString());
                    paths.add(path);
                    if (writable) writableVolumeCount++;
                    // 已配置的卷若是当前卷的前缀，默认选中它
                    if (selectIndex == 0 && writable &&
                            !currentFsPath.isEmpty() && currentFsPath.startsWith(path)) {
                        selectIndex = paths.size() - 1;
                    }
                }
            }
        }
        volumesSupported = volumes != null;

        labels.add(MANUAL_VOLUME_CHOICE);
        paths.add(null); // 标记手动输入
        final int manualIndex = labels.size() - 1;

        usbVolumePaths = paths.toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        usbVolumeSpinner.setAdapter(adapter);

        // 手动输入完整路径框：仅当选中"手动输入"项时显示。
        usbFullPathInput = addTextInput(
                "完整路径（如 /storage/1A2B-3C4D/workday）", "", false);

        // 若当前已配置的路径并不在枚举到的卷里（例如枚举失败但配置仍在），默认选手动并回填。
        if (!currentFsPath.isEmpty() && selectIndex == 0 &&
                !isPathUnderAnyVolume(currentFsPath, paths)) {
            selectIndex = manualIndex;
            usbFullPathInput.setText(currentFsPath);
        }
        final int initialSelect = selectIndex;
        final int manualIdx = manualIndex;
        usbVolumeSpinner.setSelection(initialSelect);
        usbFullPathInput.setVisibility(initialSelect == manualIdx ? View.VISIBLE : View.GONE);
        usbVolumeSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        usbFullPathInput.setVisibility(position == manualIdx ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });

        if (!volumesSupported) {
            usbVolumeHint.setText("相机端固件较旧，无法自动枚举卷，请选「手动输入完整路径」。");
        } else if (writableVolumeCount == 0) {
            // Android 11+ 对 OTG U 盘的平台限制：非 root 无法免授权直写，需有屏 SAF 授权一次。
            usbVolumeHint.setText("未检测到可免授权直写的卷。该机型的 OTG U 盘受系统限制，"
                    + "仍需在相机本机用系统目录选择器授权一次；NAS 不受此限制，可直接在上方配置。");
            usbVolumeHint.setTextColor(Color.rgb(255, 170, 120));
        } else {
            usbVolumeHint.setText("U 盘插入相机后显示为 /storage/卷号；选卷后再填子目录。");
        }
    }

    private boolean isPathUnderAnyVolume(String fsPath, ArrayList<String> paths) {
        for (String p : paths) {
            if (p != null && !p.isEmpty() && fsPath.startsWith(p)) return true;
        }
        return false;
    }

    /** 已配置 fsPath 去掉卷前缀后得到子目录，用于回显。 */
    private String deriveSubDir(String fsPath) {
        if (fsPath == null || fsPath.isEmpty()) return "";
        // 简单处理：卷号段之后的相对部分。匹配 /storage/<卷>/<sub>。
        String[] parts = fsPath.split("/");
        // / storage <卷> sub...
        if (parts.length >= 4 && parts[1].equals("storage")) {
            StringBuilder sub = new StringBuilder();
            for (int i = 3; i < parts.length; i++) {
                if (sub.length() > 0) sub.append('/');
                sub.append(parts[i]);
            }
            return sub.toString();
        }
        return "";
    }

    /**
     * 根据卷选择与子目录组装要下发的绝对路径。
     * 返回 "" 表示清空 USB 远程位置（选了"不指定"）。
     */
    private String composeUsbFsPath() {
        int pos = usbVolumeSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= usbVolumePaths.length) return "";
        String volume = usbVolumePaths[pos];
        if (volume == null) {
            // 手动输入完整路径：原样返回（相机端会做安全前缀校验）。
            return usbFullPathInput.getText().toString().trim();
        }
        if (volume.isEmpty()) return "";
        // 去掉子目录首尾斜杠，避免与卷路径拼出双斜杠。
        String sub = usbSubDirInput.getText().toString().trim().replaceAll("^/+|/+$", "");
        return sub.isEmpty() ? volume : volume + "/" + sub;
    }

    /** 用当前表单参数实测 NAS 连接（不保存）。在后台线程跑 PROPFIND，避免阻塞 UI。 */
    private void testNasConnection() {
        if (storageBaseUrl == null) {
            toast("设置尚未加载完成");
            return;
        }
        final String url = nasUrlInput.getText().toString().trim();
        if (url.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("请先填写以 http:// 或 https:// 开头的 NAS 地址");
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("nasUrl", url);
            body.put("nasUsername", nasUserInput.getText().toString().trim());
            String pass = nasPassInput.getText().toString();
            if (!pass.isEmpty()) body.put("nasPassword", pass);
            body.put("nasDir", nasDirInput.getText().toString().trim());
        } catch (Exception ignored) {
        }
        nasTestBtn.setEnabled(false);
        nasTestBtn.setText("正在测试…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    JSONObject resp = postJsonForResult(
                            storageBaseUrl + "cam/storage/nas/test", body);
                    result = (resp != null && resp.optBoolean("ok", false))
                            ? "NAS 连接成功"
                            : "NAS 连接失败：" + (resp == null ? "无响应" : resp.optString("error", "未知错误"));
                } catch (Exception e) {
                    result = "NAS 连接失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
                final String finalResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        nasTestBtn.setEnabled(true);
                        nasTestBtn.setText("测试 NAS 连接");
                        storageStatusText.setText(finalResult);
                        storageStatusText.setTextColor(
                                finalResult.contains("成功") ? Color.rgb(120, 220, 140)
                                                             : Color.rgb(255, 150, 150));
                        toast(finalResult);
                    }
                });
            }
        }, "nas-test").start();
    }

    // ---------- 局域网 NAS 自动搜索 ----------

    /** NSD 扫描局域网里的 WebDAV 服务（咩存储），用当前表单账号密码逐个验证，通过后供用户选择填入。 */
    private void scanNas() {
        nasScanStatus.setText("正在搜索局域网 NAS（约 5 秒）…");
        nasScanBtn.setEnabled(false);
        NasScanner.scan(this, 5, new NasScanner.Callback() {
            @Override
            public void onResult(final java.util.List<NasScanner.Candidate> candidates) {
                nasScanBtn.setEnabled(true);
                if (candidates.isEmpty()) {
                    nasScanStatus.setText("未发现 NAS。请确认存储手机已开启 FTP/WebDAV 服务，" +
                            "且与本机连接同一局域网。");
                    return;
                }
                nasScanStatus.setText("发现 " + candidates.size() + " 个候选，正在验证 WebDAV 连接…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final java.util.List<NasScanner.Candidate> verified =
                                new ArrayList<>();
                        String username = nasUserInput.getText().toString().trim();
                        String password = nasPassInput.getText().toString();
                        for (NasScanner.Candidate c : candidates) {
                            if (verifyWebDav("http://" + c.host + ":" + c.port, username, password)) {
                                verified.add(c);
                            }
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (verified.isEmpty()) {
                                    nasScanStatus.setText("发现 " + candidates.size() +
                                            " 个设备，但 WebDAV 验证均未通过，" +
                                            "请检查上方账号密码后重试。");
                                } else {
                                    String[] items = new String[verified.size()];
                                    for (int i = 0; i < verified.size(); i++) {
                                        items[i] = verified.get(i).toString();
                                    }
                                    new android.app.AlertDialog.Builder(CameraSettingsActivity.this)
                                            .setTitle("选择 NAS（已通过 WebDAV 验证）")
                                            .setItems(items, new android.content.DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(android.content.DialogInterface dialog,
                                                                    int which) {
                                                    NasScanner.Candidate picked = verified.get(which);
                                                    nasUrlInput.setText(picked.suggestedUrl());
                                                    nasScanStatus.setText("已填入 " + picked.suggestedUrl() +
                                                            "，测试通过后点击「保存并生效」");
                                                }
                                            })
                                            .setNegativeButton("取消", null)
                                            .show();
                                }
                            }
                        });
                    }
                }, "nas-verify").start();
            }
        });
    }

    /**
     * 从本机直接对候选地址做 WebDAV PROPFIND（Depth: 0）验证。
     * 与相机端验证相互独立：这里先筛掉不可用的，相机端保存前还会再测一次。
     */
    private boolean verifyWebDav(String baseUrl, String username, String password) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(false);
            try {
                conn.setRequestMethod("PROPFIND");
            } catch (java.net.ProtocolException e) {
                conn.setRequestMethod("POST");
                conn.setRequestProperty("X-HTTP-Method-Override", "PROPFIND");
            }
            conn.setRequestProperty("Depth", "0");
            if (username != null && !username.isEmpty()) {
                String raw = username + ":" + (password == null ? "" : password);
                conn.setRequestProperty("Authorization", "Basic " +
                        android.util.Base64.encodeToString(
                                raw.getBytes("UTF-8"), android.util.Base64.NO_WRAP));
            }
            int code = conn.getResponseCode();
            return code == 207 || code == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---------- 修改访问摄像头密码 ----------

    /** 在存储分区末尾追加「访问密码」区块：修改本机保存的摄像头访问密码并即时生效。 */
    private void buildPasswordSection() {
        addSectionLabel("访问密码");
        Config.Device device = Config.activeDevice(this);
        camPasswordInput = addTextInput(
                device.password.isEmpty() ? "访问密码（当前未设置）"
                        : "访问密码（已保存，留空表示清除密码）",
                "", true);
        camPasswordBtn = new Button(this);
        camPasswordBtn.setText("保存访问密码");
        camPasswordBtn.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.topMargin = dp(6);
        camPasswordBtn.setLayoutParams(lp);
        camPasswordBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveCamPassword();
            }
        });
        container.addView(camPasswordBtn);
    }

    /** 更新当前设备在本机保存的访问密码；保存后重建存储接口基址，后续请求立即用新密码。 */
    private void saveCamPassword() {
        String newPassword = camPasswordInput.getText().toString().trim();
        if (newPassword.contains("/") || newPassword.contains("?") || newPassword.contains("#")) {
            toast("密码不能包含 /、?、#");
            return;
        }
        List<Config.Device> list = Config.devices(this);
        int idx = Config.activeIndex(this);
        if (idx < 0 || idx >= list.size()) {
            toast("当前没有已配置的设备");
            return;
        }
        Config.Device d = list.get(idx);
        d.password = newPassword;
        list.set(idx, d);
        Config.saveDevices(this, list);

        // 重建请求基址：新密码立即用于后续 NAS 测试与保存
        if (endpointHost != null) {
            String prefix = newPassword.isEmpty() ? "" : newPassword + "/";
            storageBaseUrl = "http://" + endpointHost + ":" + endpointPort + "/" + prefix;
        }
        camPasswordInput.setText("");
        camPasswordInput.setHint(newPassword.isEmpty() ? "访问密码（当前未设置）"
                : "访问密码（已保存，留空表示不修改）");
        toast("访问密码已保存");
    }

    private void addSectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText("— " + text + " —");
        label.setTextSize(17);
        label.setTextColor(Color.rgb(120, 200, 255));
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, dp(22), 0, dp(2));
        container.addView(label);
    }

    private EditText addNumberInput(String title, int valueMb) {
        addLabel(title);
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Math.max(0, valueMb)));
        input.setTextColor(Color.WHITE);
        input.setTextSize(16);
        input.setBackgroundColor(Color.rgb(30, 38, 48));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.bottomMargin = dp(6);
        input.setLayoutParams(lp);
        container.addView(input);
        return input;
    }

    private int parseMb(EditText input, int fallback) {
        try {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return fallback;
            return Integer.parseInt(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private void addLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(15);
        label.setTextColor(Color.rgb(200, 208, 216));
        label.setPadding(0, dp(16), 0, dp(4));
        container.addView(label);
    }

    private RadioGroup addRadio(String title, String[] labels, int[] values, int current) {
        return addRadioBase(title, labels, values, current, 1000);
    }

    /**
     * 与 addRadio 相同，但 id 基址可指定。同一页面多个 RadioGroup 的取值可能相同
     * （例如旋转 0° 与配额模式 0），共用同一个基址会让 check 命中错误的选项，
     * 回读时也会张冠李戴，所以存储分区单独用 2000 基址。
     */
    private RadioGroup addRadioBase(String title, String[] labels, int[] values,
                                    int current, int baseId) {
        addLabel(title);
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setTextColor(Color.WHITE);
            rb.setTextSize(16);
            rb.setId(baseId + values[i]);
            group.addView(rb);
        }
        group.check(baseId + current);
        container.addView(group);
        return group;
    }

    private void save() {
        if (!loaded) return;
        saveBtn.setEnabled(false);
        statusText.setText("正在保存…");
        final JSONObject json = new JSONObject();
        final JSONObject storageJson = new JSONObject();
        try {
            json.put("rotation", rotationGroup.getCheckedRadioButtonId() - 1000);
            json.put("codec", codecGroup.getCheckedRadioButtonId() - 1000 == 1 ? "h265" : "h264");
            json.put("fps", fpsGroup.getCheckedRadioButtonId() - 1000);
            json.put("height", heightGroup.getCheckedRadioButtonId() - 1000);
            json.put("audio", audioSwitch.isChecked());
            json.put("policy", policyGroup.getCheckedRadioButtonId() - 1000);
            json.put("sens", sensGroup.getCheckedRadioButtonId() - 1000);
            if (storageSupported && !storageReadFailed) {
                // NAS（WebDAV）远程配置；密码留空表示不修改原密码。
                storageJson.put("nasUrl", nasUrlInput.getText().toString().trim());
                storageJson.put("nasUsername", nasUserInput.getText().toString().trim());
                String nasPass = nasPassInput.getText().toString();
                if (!nasPass.isEmpty()) storageJson.put("nasPassword", nasPass);
                storageJson.put("nasDir", nasDirInput.getText().toString().trim());

                storageJson.put("usbEnabled", usbStorageSwitch.isChecked());
                // 仅在开启 USB 时下发目标路径，避免关闭开关时顺带清空已选位置。
                if (usbStorageSwitch.isChecked()) {
                    storageJson.put("usbFsPath", composeUsbFsPath());
                }
                storageJson.put("quotaMode", quotaGroup.getCheckedRadioButtonId() - 2000);
                storageJson.put("reserveMb", parseMb(reserveInput, 1024));
                storageJson.put("limitMb", parseMb(limitInput, 10240));
                storageJson.put("localCacheMb", parseMb(cacheInput, 512));
            }
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
            saveBtn.setEnabled(true);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    Config.Device device = Config.activeDevice(CameraSettingsActivity.this);
                    Resolver.Endpoint endpoint = Resolver.resolve(CameraSettingsActivity.this,
                            device.host, device.port);
                    String prefix = device.password.isEmpty() ? "" : device.password + "/";
                    String base = "http://" + endpoint.host + ":" + endpoint.port + "/" + prefix;
                    String error = postJson(base + "cam/settings", json);
                    if (error != null) {
                        msg = error;
                    } else if (storageSupported && !storageReadFailed) {
                        // 存储配置改动不需要重启摄像头服务，失败时单独提示
                        error = postJson(base + "cam/storage", storageJson);
                        msg = error != null
                                ? "编码参数已保存；存储设置保存失败（" + error + "）"
                                : "已保存，摄像头服务重启中，约 5 秒后生效";
                    } else if (storageReadFailed) {
                        // 存储配置没读取成功：绝不下发，避免空表单清空相机端 NAS/USB 配置
                        msg = "摄像头参数已保存；存储设置本次未读取成功、未做任何改动，请重进本页面后再修改存储";
                    } else {
                        msg = "已保存，摄像头服务重启中，约 5 秒后生效";
                    }
                } catch (Exception e) {
                    msg = "保存失败：" + (e.getMessage() == null
                            ? e.getClass().getSimpleName() : e.getMessage());
                }
                final String finalMsg = msg;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setText(finalMsg);
                        toast(finalMsg);
                        saveBtn.setEnabled(true);
                    }
                });
            }
        }, "cam-settings-save").start();
    }

    /** POST JSON 并把响应体解析为 JSONObject；网络/解析失败返回 null（调用方据此提示）。 */
    private JSONObject postJsonForResult(String urlText, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000); // NAS PROPFIND 可能较慢
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] data = body.toString().getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            OutputStream out = conn.getOutputStream();
            out.write(data);
            out.flush();
            out.close();
            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = stream.read(chunk)) > 0) buf.write(chunk, 0, n);
            stream.close();
            String text = buf.toString("UTF-8").trim();
            return text.isEmpty() ? null : new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** POST 一个 JSON body；返回 null 表示成功，否则返回可展示的错误信息（含相机端 ok:false 文案）。 */
    private String postJson(String urlText, JSONObject body) {
        try {
            JSONObject resp = postJsonForResult(urlText, body);
            if (resp != null && !resp.optBoolean("ok", true)) {
                String error = resp.optString("error", "");
                return error.isEmpty() ? "相机端拒绝了该配置" : error;
            }
            return null;
        } catch (Exception e) {
            return "保存失败：" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
