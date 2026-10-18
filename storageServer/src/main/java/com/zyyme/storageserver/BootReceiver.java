package com.zyyme.storageserver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;

/** 开机自启 / 应用更新后自启：设置了自动启动且至少开启一个服务器时拉起服务。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        // 更新覆盖安装后系统会 force-stop 应用，服务不会自己回来，这里自动拉起。
        boolean replaced = "android.intent.action.MY_PACKAGE_REPLACED".equals(action);
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !replaced) return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean autostart = prefs.getBoolean("autostart", true);
        boolean anyOn = prefs.getBoolean("ftp_on", true) || prefs.getBoolean("dav_on", true);
        if (!autostart || !anyOn) return;
        Intent service = new Intent(context, ServerService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }
}
