package com.zyyme.workdayalarmclock

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate

/**
 * 设置
 * 代替之前的文件flag
 */
object MeSettings {
    private const val PREFS_NAME = "me_settings"

    const val KEY_DISABLE = "disable"
    const val KEY_NO_SCREEN = "no_screen"
    const val KEY_CLOCK = "clock"
    const val KEY_DESK_CLOCK = "desk_clock"
    const val KEY_TSS = "tss"
    const val KEY_T24 = "t24"
    const val KEY_WHITE = "white"
    const val KEY_LYRICS = "lyrics"
    const val KEY_LANDSCAPE = "landscape"
    const val KEY_VERTICAL = "vertical"
    const val KEY_ROUND = "round"
    const val KEY_AP = "ap"
    const val KEY_AUTO_BACK_CLOCK = "auto_back_clock"
    const val KEY_NOTIFICATION_FORWARD_URL = "notification_forward_url"
    const val KEY_NOTIFICATION_FORWARD_SCREEN_OFF_ONLY = "notification_forward_screen_off_only"
    const val KEY_NOTIFICATION_FORWARD_BLACKLIST = "notification_forward_blacklist"
    const val KEY_TODO_URL = "todo_url"
    const val KEY_CAMERA_SERVER = "camera_server"
    const val KEY_COMPUTER_SPEAKER = "computer_speaker"
    const val KEY_CAMERA_PASSWORD = "camera_password"
    const val KEY_CAMERA_AUTO_BRIGHTNESS = "camera_auto_brightness"
    const val KEY_CAMERA_BRIGHTNESS_0 = "camera_brightness_0"
    const val KEY_CAMERA_BRIGHTNESS_1 = "camera_brightness_1"
    const val KEY_CAMERA_BRIGHTNESS_2 = "camera_brightness_2"
    const val KEY_CAMERA_BRIGHTNESS_3 = "camera_brightness_3"
    const val KEY_CAMERA_BRIGHTNESS_4 = "camera_brightness_4"
    const val KEY_CAMERA_CLOSE_SCREEN = "camera_close_screen"
    const val KEY_CAMERA_CLOSE_SCREEN_LEVEL_1 = "camera_close_screen_level_1"
    const val KEY_CAMERA_CLOSE_SCREEN_KEEP_SCREEN_ON = "camera_close_screen_keep_screen_on"
    const val KEY_CAMERA_AUTO_WAKE_LEVEL = "camera_auto_wake_level"
    const val KEY_CAMERA_BRIGHTNESS_INTERVAL = "camera_brightness_interval"
    const val KEY_CAMERA_FACE_WAKE = "camera_face_wake"
    const val KEY_RECORD_ENABLED = "record_enabled"
    const val KEY_RECORD_CAMERA_INDEX = "record_camera_index"
    const val KEY_RECORD_HEIGHT = "record_height"
    const val KEY_RECORD_AUDIO = "record_audio"
    const val KEY_WEBDAV_URL = "webdav_url"
    const val KEY_WEBDAV_USERNAME = "webdav_username"
    const val KEY_WEBDAV_PASSWORD = "webdav_password"
    const val KEY_WEBDAV_DIR = "webdav_dir"
    const val KEY_QUOTA_MODE = "quota_mode"
    const val KEY_QUOTA_RESERVE_MB = "quota_reserve_mb"
    const val KEY_QUOTA_LIMIT_MB = "quota_limit_mb"
    const val KEY_LOCAL_CACHE_MB = "local_cache_mb"
    const val KEY_DESK_MASK = "desk_mask"
    const val KEY_DESK_LIGHT_TEXT = "desk_light_text"
    const val KEY_DESK_KEEP_SCREEN_ON = "desk_keep_screen_on"
    const val KEY_DESK_SLOT_TOP_LEFT = "desk_slot_top_left"
    const val KEY_DESK_SLOT_TOP_RIGHT = "desk_slot_top_right"
    const val KEY_DESK_SLOT_BOTTOM_LEFT = "desk_slot_bottom_left"
    const val KEY_DESK_SLOT_BOTTOM_RIGHT = "desk_slot_bottom_right"
    const val KEY_DESK_LYRICS_POSITION = "desk_lyrics_position"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, key: String, defaultValue: Boolean = false): Boolean {
        return preferences(context).getBoolean(key, defaultValue)
    }

    fun getEnabledKeys(context: Context, keys: Iterable<String>): Set<String> {
        val preferences = preferences(context)
        return keys.filterTo(mutableSetOf()) { key ->
            preferences.getBoolean(key, false)
        }
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(key, enabled)
            .apply()
    }

    fun getInt(context: Context, key: String, defaultValue: Int): Int {
        return preferences(context).getInt(key, defaultValue)
    }

    fun setInt(context: Context, key: String, value: Int) {
        preferences(context)
            .edit()
            .putInt(key, value)
            .apply()
    }

    fun getString(context: Context, key: String, defaultValue: String): String {
        return preferences(context).getString(key, defaultValue).orEmpty()
    }

    fun setString(context: Context, key: String, value: String) {
        preferences(context).edit().putString(key, value).apply()
    }

    fun createClockIntent(context: Context): Intent {
        val activityClass = if (isEnabled(context, KEY_DESK_CLOCK)) {
            DeskActivity::class.java
        } else {
            ClockActivity::class.java
        }
        return Intent(context, activityClass)
    }

    fun applyClockTheme(context: Context) {
        val nightMode = if (isEnabled(context, KEY_WHITE)) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun getNotificationForwardUrl(context: Context): String {
        return preferences(context)
            .getString(KEY_NOTIFICATION_FORWARD_URL, "")
            .orEmpty()
    }

    fun setNotificationForwardUrl(context: Context, url: String) {
        val cleanUrl = url.trim()
        val editor = preferences(context).edit()
        if (cleanUrl.isEmpty()) {
            editor.remove(KEY_NOTIFICATION_FORWARD_URL)
        } else {
            editor.putString(KEY_NOTIFICATION_FORWARD_URL, cleanUrl)
        }
        editor.apply()
    }

    fun getNotificationForwardBlacklist(context: Context): Set<String> {
        return preferences(context)
            .getStringSet(KEY_NOTIFICATION_FORWARD_BLACKLIST, emptySet())
            ?.toSet()
            .orEmpty()
    }

    fun setNotificationForwardBlacklist(context: Context, packageNames: Set<String>) {
        val cleanPackageNames = packageNames.filterTo(mutableSetOf()) { it.isNotBlank() }
        val editor = preferences(context).edit()
        if (cleanPackageNames.isEmpty()) {
            editor.remove(KEY_NOTIFICATION_FORWARD_BLACKLIST)
        } else {
            editor.putStringSet(KEY_NOTIFICATION_FORWARD_BLACKLIST, cleanPackageNames)
        }
        editor.apply()
    }

    fun getTodoUrl(context: Context): String {
        return preferences(context).getString(KEY_TODO_URL, "").orEmpty()
    }

    fun setTodoUrl(context: Context, url: String) {
        val cleanUrl = url.trim()
        val editor = preferences(context).edit()
        if (cleanUrl.isEmpty()) {
            editor.remove(KEY_TODO_URL)
        } else {
            editor.putString(KEY_TODO_URL, cleanUrl)
        }
        editor.apply()
    }

    fun getCameraPassword(context: Context): String {
        return preferences(context)
            .getString(KEY_CAMERA_PASSWORD, "")
            .orEmpty()
    }

    fun setCameraPassword(context: Context, password: String) {
        val cleanPassword = password.trim().trim('/')
        val editor = preferences(context).edit()
        if (cleanPassword.isEmpty()) {
            editor.remove(KEY_CAMERA_PASSWORD)
        } else {
            editor.putString(KEY_CAMERA_PASSWORD, cleanPassword)
        }
        editor.apply()
    }

    const val QUOTA_MODE_RESERVE = 0
    const val QUOTA_MODE_LIMIT = 1

    const val RECORD_HEIGHT_720 = 720
    const val RECORD_HEIGHT_1080 = 1080

    const val DEFAULT_QUOTA_RESERVE_MB = 1024
    const val DEFAULT_QUOTA_LIMIT_MB = 10240
    const val DEFAULT_LOCAL_CACHE_MB = 512

    fun isRecordEnabled(context: Context): Boolean {
        return isEnabled(context, KEY_RECORD_ENABLED)
    }

    fun setRecordEnabled(context: Context, enabled: Boolean) {
        setEnabled(context, KEY_RECORD_ENABLED, enabled)
    }

    fun getRecordCameraIndex(context: Context): Int {
        return getInt(context, KEY_RECORD_CAMERA_INDEX, 0)
    }

    fun setRecordCameraIndex(context: Context, index: Int) {
        setInt(context, KEY_RECORD_CAMERA_INDEX, index)
    }

    fun getRecordHeight(context: Context): Int {
        return getInt(context, KEY_RECORD_HEIGHT, RECORD_HEIGHT_1080)
    }

    fun setRecordHeight(context: Context, height: Int) {
        setInt(context, KEY_RECORD_HEIGHT, height)
    }

    fun isRecordAudioEnabled(context: Context): Boolean {
        return isEnabled(context, KEY_RECORD_AUDIO, true)
    }

    fun setRecordAudioEnabled(context: Context, enabled: Boolean) {
        setEnabled(context, KEY_RECORD_AUDIO, enabled)
    }

    fun getWebDavUrl(context: Context): String {
        return getString(context, KEY_WEBDAV_URL, "").trim().trimEnd('/')
    }

    fun setWebDavUrl(context: Context, url: String) {
        setString(context, KEY_WEBDAV_URL, url.trim().trimEnd('/'))
    }

    fun getWebDavUsername(context: Context): String {
        return getString(context, KEY_WEBDAV_USERNAME, "").trim()
    }

    fun setWebDavUsername(context: Context, username: String) {
        setString(context, KEY_WEBDAV_USERNAME, username.trim())
    }

    fun getWebDavPassword(context: Context): String {
        return getString(context, KEY_WEBDAV_PASSWORD, "")
    }

    fun setWebDavPassword(context: Context, password: String) {
        setString(context, KEY_WEBDAV_PASSWORD, password)
    }

    fun getWebDavDir(context: Context): String {
        val raw = getString(context, KEY_WEBDAV_DIR, "").trim().trim('/')
        return raw
    }

    fun setWebDavDir(context: Context, dir: String) {
        setString(context, KEY_WEBDAV_DIR, dir.trim().trim('/'))
    }

    fun getQuotaMode(context: Context): Int {
        return getInt(context, KEY_QUOTA_MODE, QUOTA_MODE_RESERVE)
    }

    fun setQuotaMode(context: Context, mode: Int) {
        setInt(context, KEY_QUOTA_MODE, mode)
    }

    fun getQuotaReserveMb(context: Context): Int {
        return getInt(context, KEY_QUOTA_RESERVE_MB, DEFAULT_QUOTA_RESERVE_MB)
    }

    fun setQuotaReserveMb(context: Context, mb: Int) {
        setInt(context, KEY_QUOTA_RESERVE_MB, mb)
    }

    fun getQuotaLimitMb(context: Context): Int {
        return getInt(context, KEY_QUOTA_LIMIT_MB, DEFAULT_QUOTA_LIMIT_MB)
    }

    fun setQuotaLimitMb(context: Context, mb: Int) {
        setInt(context, KEY_QUOTA_LIMIT_MB, mb)
    }

    fun getLocalCacheMb(context: Context): Int {
        return getInt(context, KEY_LOCAL_CACHE_MB, DEFAULT_LOCAL_CACHE_MB)
    }

    fun setLocalCacheMb(context: Context, mb: Int) {
        setInt(context, KEY_LOCAL_CACHE_MB, mb)
    }

    fun isRecordConfigured(context: Context): Boolean {
        return getWebDavUrl(context).isNotEmpty()
    }
}
