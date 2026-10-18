package com.zyyme.workdayviewer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 多设备配置：每台设备包含名称、地址（IP 或 mDNS 域名）、端口、密码、摄像头序号。
 * 持久化为 JSON 数组；老版本单设备配置自动迁移为第一个设备。
 */
public class Config {
    private static final String PREFS = "viewer_config";
    private static final String KEY_DEVICES = "devices_json";
    private static final String KEY_ACTIVE = "active_device";
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    public static class Device {
        public String name = "";
        public String host = "";
        public int port = 8880;
        public String password = "";
        public int camera = 1;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 把设备地址中的全角标点与空白归一化为 ASCII。
     *
     * 为什么需要：部分输入法在数字间输入的"."实际是全角句号"。"(U+3002) 或全角点
     * "．"(U+FF0E)/半角片假名句点"｡"(U+FF61)。Java 网络栈对非 ASCII host 较宽容，
     * 实时流与下载看似正常，但播放端瑞芯微 Rockit 内置 ffmpeg 严格按 URL 规范解析，
     * 会在第一个全角点处把 host 截断成 "192" 并报解析失败（MediaPlayer error(1,0)）。
     * 在配置入口统一归一化，可让 native 直连，无需任何本地代理兜底。
     */
    static String normalizeHost(String raw) {
        if (raw == null) return "";
        return raw
                .replace('。', '.')   // 全角中文句号 U+3002
                .replace('．', '.')   // 全角点 U+FF0E
                .replace('｡', '.')   // 半角片假名句点 U+FF61
                .replace('：', ':')   // 全角冒号 U+FF1A（兼容个别用户把端口写进地址）
                .replaceAll("[\\s\\u3000]", "")  // 去掉 ASCII 空白与全角空格 U+3000
                .trim();
    }

    /** 读取全部设备；首次运行把老版本单设备配置迁移成「设备1」。 */
    public static List<Device> devices(Context ctx) {
        List<Device> list = new ArrayList<>();
        String json = prefs(ctx).getString(KEY_DEVICES, "");
        if (!json.isEmpty()) {
            try {
                JSONArray array = new JSONArray(json);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    Device d = new Device();
                    d.name = o.optString("name", "设备" + (i + 1));
                    d.host = normalizeHost(o.optString("host", ""));
                    d.port = o.optInt("port", 8880);
                    d.password = o.optString("password", "");
                    d.camera = o.optInt("camera", 1);
                    list.add(d);
                }
            } catch (Exception ignored) {
            }
        }
        if (list.isEmpty()) {
            // 迁移老版本字段
            Device d = new Device();
            d.name = "设备1";
            d.host = normalizeHost(prefs(ctx).getString("host", ""));
            d.port = prefs(ctx).getInt("port", 8880);
            d.password = prefs(ctx).getString("password", "").trim();
            d.camera = prefs(ctx).getInt("camera", 1);
            if (!d.host.isEmpty()) list.add(d);
        }
        return list;
    }

    public static void saveDevices(Context ctx, List<Device> list) {
        JSONArray array = new JSONArray();
        try {
            for (Device d : list) {
                JSONObject o = new JSONObject();
                o.put("name", d.name);
                // 保存时同样归一化，确保写入磁盘的就是规范 ASCII 地址（兼顾历史脏数据修复）。
                o.put("host", normalizeHost(d.host));
                o.put("port", d.port);
                o.put("password", d.password);
                o.put("camera", d.camera);
                array.put(o);
            }
        } catch (Exception ignored) {
        }
        prefs(ctx).edit().putString(KEY_DEVICES, array.toString()).apply();
    }

    public static int activeIndex(Context ctx) {
        int idx = prefs(ctx).getInt(KEY_ACTIVE, 0);
        List<Device> list = devices(ctx);
        if (idx < 0 || idx >= list.size()) idx = 0;
        return idx;
    }

    public static void setActiveIndex(Context ctx, int index) {
        prefs(ctx).edit().putInt(KEY_ACTIVE, index).apply();
    }

    public static Device activeDevice(Context ctx) {
        List<Device> list = devices(ctx);
        int idx = activeIndex(ctx);
        if (list.isEmpty()) return new Device();
        return list.get(Math.min(idx, list.size() - 1));
    }

    public static boolean configured(Context ctx) {
        return !activeDevice(ctx).host.isEmpty();
    }

    public static boolean isIp(String host) {
        return IPV4.matcher(host).matches();
    }
}
