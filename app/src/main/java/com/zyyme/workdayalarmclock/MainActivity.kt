package com.zyyme.workdayalarmclock

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.support.v4.media.session.MediaSessionCompat
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import kotlin.system.exitProcess
import androidx.core.net.toUri
import com.zyyme.workdayalarmclock.applist.AppListActivity
import com.zyyme.workdayalarmclock.camera.AmbientBrightnessController
import com.zyyme.workdayalarmclock.nas.NasScanner
import com.zyyme.workdayalarmclock.nas.WebDavClient
import com.zyyme.workdayalarmclock.nas.WebDavException
import com.zyyme.workdayalarmclock.nas.LanDomainResolver
import com.zyyme.workdayalarmclock.notification.MeNotificationListenerService
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity() {
    companion object {
        var print2LogViewHandler: Handler? = null
        var me: MainActivity? = null
        var startService = true
        private const val MENU_EXIT = 1
        private const val OPEN_WEB = 2
        private const val OPEN_CLOCK = 3
        private const val OPEN_APPLIST = 4
        private const val OPEN_DEVICE_ADMIN = 5
        private const val OPEN_ACCESSIBILITY_SETTINGS = 6
        private const val OPEN_NOTIFICATION_FORWARD_URL = 7
        private const val TOGGLE_CAMERA_SERVER = 8
        private const val EDIT_CAMERA_PASSWORD = 9
        private const val OPEN_DEVELOPER_OPTIONS = 10
        private const val CONFIG_CAMERA_AUTO_BRIGHTNESS = 11
        private const val TOGGLE_HOME_LAUNCHER = 12
        private const val OPEN_TODO_URL = 13
        private const val OPEN_DESK = 14
        private const val TOGGLE_COMPUTER_SPEAKER = 15
        private const val OPEN_RECORD_SETTINGS = 16
        private const val MENU_SETTING_START = 100
        private const val REQUEST_MEDIA_SERVER_PERMISSIONS = 102
        private const val REQUEST_AMBIENT_CAMERA_PERMISSION = 103
        private const val REQUEST_RECORD_PERMISSIONS = 104
        private const val REQUEST_USB_TREE = 105
        private const val LOG_REFRESH_DELAY_MILLIS = 250L
        private const val DPAD_CENTER_LONG_PRESS_DELAY_MS = 900L
    }

    var mediaSessionCompat: MediaSessionCompat? = null
    var mediaComponentName: ComponentName? = null
    var isActivityVisible = false
    @Volatile var isActivityStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val logRefreshPending = AtomicBoolean(false)
    private lateinit var logView: TextView
    private lateinit var logScrollView: ScrollView
    private lateinit var playButton: ImageView
    private var settingsPopupMenu: PopupMenu? = null
    private var openAmbientDialogAfterPermission = false
    private var openRecordDialogAfterPermission = false
    // 录像设置对话框里的 USB 控件，供 SAF 目录选择回调写回；对话框关闭后置空。
    private var recordDialogUsbPathLabel: TextView? = null
    private var recordDialogUsbSwitch: Switch? = null
    private var dpadPassthrough = false
    private var dpadCenterLongPressTriggered = false
    private val dpadCenterLongPressRunnable = Runnable {
        if (dpadPassthrough || dpadCenterLongPressTriggered) return@Runnable
        dpadCenterLongPressTriggered = true
        dpadPassthrough = true
        showSettingsMenu(findViewById(R.id.iconMenu))
    }

    private val scrollLogToBottom = Runnable {
        if (isActivityVisible && ::logScrollView.isInitialized) {
            logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private val refreshLogView = Runnable {
        // 控制台更新节流
        logRefreshPending.set(false)
        if (!isActivityVisible || !::logView.isInitialized) return@Runnable

        val latestLog = synchronized(MeService.logBuilder) {
            MeService.logBuilder.toString()
        }
        if (logView.text.toString() != latestLog) {
            logView.text = latestLog
            logScrollView.removeCallbacks(scrollLogToBottom)
            logScrollView.post(scrollLogToBottom)
        }
    }

    // 时钟模式
    var useClockMode = false

    private data class SettingMenuItem(val key: String, val label: String)

    private val settingsMenuItems = listOf(
        SettingMenuItem(MeSettings.KEY_DISABLE, "开机不启动"),
        SettingMenuItem(MeSettings.KEY_NO_SCREEN, "无屏设备模式"),
        SettingMenuItem(MeSettings.KEY_CLOCK, "时钟模式"),
        SettingMenuItem(MeSettings.KEY_DESK_CLOCK, "使用大屏时钟"),
        SettingMenuItem(MeSettings.KEY_AUTO_BACK_CLOCK, "自动回到时钟"),
        SettingMenuItem(MeSettings.KEY_TSS, "时钟不显示秒"),
        SettingMenuItem(MeSettings.KEY_T24, "时钟24小时制"),
        SettingMenuItem(MeSettings.KEY_WHITE, "时钟亮色主题"),
        SettingMenuItem(MeSettings.KEY_LYRICS, "显示歌词"),
        SettingMenuItem(MeSettings.KEY_LANDSCAPE, "锁定横屏"),
        SettingMenuItem(MeSettings.KEY_VERTICAL, "强制竖屏布局"),
        SettingMenuItem(MeSettings.KEY_ROUND, "强制圆屏布局"),
        SettingMenuItem(MeSettings.KEY_AP, "开机开热点(辅助)"),
    )

    override fun onResume() {
        super.onResume()
        AmbientBrightnessController.applyLatestTo(window)
        isActivityVisible = true
        updatePlaybackButton(MeService.me?.shouldShowPauseIcon() == true)
        show2LogView()
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        mainHandler.removeCallbacks(refreshLogView)
        logRefreshPending.set(false)
        if (::logScrollView.isInitialized) {
            logScrollView.removeCallbacks(scrollLogToBottom)
        }
    }

    override fun onStop() {
        isActivityStarted = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        me = this
        useClockMode = MeSettings.isEnabled(this, MeSettings.KEY_CLOCK) &&
                !MeSettings.isEnabled(this, MeSettings.KEY_NO_SCREEN)

        // am start -n com.zyyme.workdayalarmclock/.MainActivity -d http://...
        val amUrl = getIntent().getDataString();
        if (amUrl != null) {
            startService = false
            if (MeService.me == null) {
                startService(Intent(this, MeService::class.java))
            } else {
                print2LogView("服务已经在运行了")
            }
            print2LogView("收到外部播放链接 将不启动服务 放完自动退出\n" + amUrl)
            Thread(Runnable {
                while (MeService.me == null) {
                    print2LogView("等待服务启动...")
                    Thread.sleep(1000)
                }
                MeService.me!!.playUrl(amUrl!!, true)
            }).start()
            return
        }

        // 启动服务 如果Activity不是重载的话
        if (MeService.me == null) {
            startService(Intent(this, MeService::class.java))
            if (useClockMode) {
                // 初次启动，切换到时钟模式 有clock文件的也切换      亮色主题（闹钟屏幕出线白色没那么明显）
                MeSettings.applyClockTheme(this)
                val intent = MeSettings.createClockIntent(this)
                intent.putExtra("clockMode", true)
                startActivity(intent)
            }
        }

        // 换一下循序，避免时钟模式设备多次渲染
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar));
        logView = findViewById(R.id.logView)
        logScrollView = findViewById(R.id.scrollView)

        // 存储空间权限 Android11
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            Toast.makeText(this,"请允许权限\n用于本地音乐播放", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
        } else if (Build.VERSION.SDK_INT < 30 && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,"请允许权限\n用于本地音乐播放", Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }

        // 检查二进制文件更新  lib不可写入 写入了重启也会恢复 于是没有热更新了
//        try {
//            val updateFile = File(Environment.getExternalStorageDirectory().absolutePath + "/libWorkdayAlarmClock.so")
//            if (updateFile.exists()) {
//                print2LogView("找到 /sdcard/libWorkdayAlarmClock.so 开始更新")
//                val libdir = File(applicationInfo.nativeLibraryDir)
//                if (!libdir.exists()) {
//                    libdir.mkdirs()
//                }
//                val inputStream: InputStream = FileInputStream(updateFile)
//                val outputFile = File(libdir, "libWorkdayAlarmClock.so")
//                val outputStream: OutputStream = FileOutputStream(outputFile)
//                val buffer = ByteArray(1024)
//                var length: Int
//                while (inputStream.read(buffer).also { length = it } > 0) {
//                    outputStream.write(buffer, 0, length)
//                }
//                outputStream.close()
//                inputStream.close()
//                print2LogView("更新完成")
//            } else {
//                print2LogView("没有找到 /sdcard/libWorkdayAlarmClock.so 不更新")
//            }
//        } catch (e: IOException) {
//            e.printStackTrace()
//            print2LogView(e.toString())
//        }

        // Shell输入框
        findViewById<EditText>(R.id.shellInput).setOnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_NULL ||
                (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN && (keyEvent.keyCode == KeyEvent.KEYCODE_ENTER || keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER))) {
                shellOnEnter()
                true
            } else {
                Log.d("shellInputEditText", "actionId: $actionId keyEvent: $keyEvent")
            }
            false
        }

        print2LogViewHandler = Handler(Looper.getMainLooper()) { msg ->
            print2LogView(msg.obj as String)
            true
        }

//        mediaButtonReceiverInit()

        // toolbar的控制按钮
        findViewById<ImageView>(R.id.iconPrev).setOnClickListener {
            MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        playButton = findViewById(R.id.iconPlay)
        playButton.setOnClickListener {
            MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
        findViewById<ImageView>(R.id.iconNext).setOnClickListener {
            MeService.me?.keyHandleAction(2147483645)
        }
        findViewById<ImageView>(R.id.iconStop).setOnClickListener {
            MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_STOP)
        }
        findViewById<ImageView>(R.id.iconForward).setOnClickListener {
            MeService.me?.keyHandleAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        }
        val menuIcon = findViewById<ImageView>(R.id.iconMenu)
        menuIcon.setOnClickListener {
            showSettingsMenu(menuIcon)
        }
        // Build the menu after the initial layout instead of doing all setup on the first click.
        menuIcon.post {
            if (!isFinishing) getOrCreateSettingsMenu(menuIcon)
        }
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setOnClickListener {
            val intent = MeSettings.createClockIntent(this)
            startActivity(intent)
        }
        toolbar.setOnLongClickListener {
            val intent = Intent(this, AppListActivity::class.java)
            startActivity(intent)
            true
        }
        toolbar.post {
            // 稍微晚点，等等MeService
            toolbar.subtitle = MeService.me?.getIp() + "咩咩"
        }
    }

    private fun showSettingsMenu(anchor: ImageView) {
        val popupMenu = getOrCreateSettingsMenu(anchor)
        val enabledSettings = MeSettings.getEnabledKeys(
            this,
            settingsMenuItems.map { it.key }
        )
        settingsMenuItems.forEachIndexed { index, item ->
            popupMenu.menu.findItem(MENU_SETTING_START + index)?.isChecked =
                item.key in enabledSettings
        }
        popupMenu.menu.findItem(TOGGLE_CAMERA_SERVER)?.isChecked =
            MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_SERVER)
        popupMenu.menu.findItem(TOGGLE_COMPUTER_SPEAKER)?.isChecked =
            MeSettings.isEnabled(this, MeSettings.KEY_COMPUTER_SPEAKER)
        popupMenu.menu.findItem(CONFIG_CAMERA_AUTO_BRIGHTNESS)?.isChecked =
            MeSettings.isEnabled(this, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS)
        popupMenu.menu.findItem(OPEN_RECORD_SETTINGS)?.title =
            if (MeSettings.isRecordEnabled(this)) "NAS录像设置（已开启）" else "NAS录像设置"
        popupMenu.menu.findItem(TOGGLE_HOME_LAUNCHER)?.isChecked =
            isHomeLauncherEnabled()
        popupMenu.show()    }

    private fun getOrCreateSettingsMenu(anchor: ImageView): PopupMenu {
        settingsPopupMenu?.let { return it }

        val popupMenu = PopupMenu(this, anchor)
        popupMenu.menu.add(Menu.NONE, MENU_EXIT, 0, "退出${getString(R.string.app_name)}")
        popupMenu.menu.add(Menu.NONE, OPEN_WEB, 1, "打开Web控制台")
        popupMenu.menu.add(Menu.NONE, OPEN_CLOCK, 2, "打开小屏时钟模式")
        popupMenu.menu.add(Menu.NONE, OPEN_DESK, 3, "打开大屏时钟模式")
        popupMenu.menu.add(Menu.NONE, OPEN_APPLIST, 4, "打开应用列表")
        popupMenu.menu.add(Menu.NONE, TOGGLE_HOME_LAUNCHER, 5, "作为桌面").apply {
            isCheckable = true
        }
        settingsMenuItems.forEachIndexed { index, item ->
            popupMenu.menu.add(Menu.NONE, MENU_SETTING_START + index, index + 6, item.label).apply {
                isCheckable = true
            }
        }
        popupMenu.menu.add(Menu.NONE, OPEN_DEVICE_ADMIN, MENU_SETTING_START + settingsMenuItems.size, "授权熄屏权限")
        popupMenu.menu.add(Menu.NONE, OPEN_ACCESSIBILITY_SETTINGS, MENU_SETTING_START + settingsMenuItems.size + 1, "辅助功能设置")
        popupMenu.menu.add(Menu.NONE, OPEN_NOTIFICATION_FORWARD_URL, MENU_SETTING_START + settingsMenuItems.size + 2, "通知转发设置")
        popupMenu.menu.add(Menu.NONE, OPEN_TODO_URL, MENU_SETTING_START + settingsMenuItems.size + 3, "待办接口URL")
        popupMenu.menu.add(Menu.NONE, TOGGLE_COMPUTER_SPEAKER, MENU_SETTING_START + settingsMenuItems.size + 4, "作为电脑音箱").apply {
            isCheckable = true
        }
        popupMenu.menu.add(Menu.NONE, TOGGLE_CAMERA_SERVER, MENU_SETTING_START + settingsMenuItems.size + 5, "IP摄像头").apply {
            isCheckable = true
        }
        popupMenu.menu.add(Menu.NONE, EDIT_CAMERA_PASSWORD, MENU_SETTING_START + settingsMenuItems.size + 6, "摄像头密码")
        popupMenu.menu.add(Menu.NONE, CONFIG_CAMERA_AUTO_BRIGHTNESS, MENU_SETTING_START + settingsMenuItems.size + 7, "摄像头自动亮度").apply {
            isCheckable = true
        }
        popupMenu.menu.add(Menu.NONE, OPEN_RECORD_SETTINGS, MENU_SETTING_START + settingsMenuItems.size + 8, "NAS录像设置")
        popupMenu.menu.add(Menu.NONE, OPEN_DEVELOPER_OPTIONS, MENU_SETTING_START + settingsMenuItems.size + 9, "开发者选项")

        popupMenu.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == MENU_EXIT) {
                exitApp()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_WEB) {
                startActivity(Intent(this, WebActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_CLOCK) {
                startActivity(Intent(this, ClockActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_DESK) {
                startActivity(Intent(this, DeskActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_APPLIST) {
                startActivity(Intent(this, AppListActivity::class.java))
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == TOGGLE_HOME_LAUNCHER) {
                toggleHomeLauncher(menuItem.isChecked)
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_DEVICE_ADMIN) {
                openDeviceAdminSettings()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_ACCESSIBILITY_SETTINGS) {
                openAccessibilitySettings()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_NOTIFICATION_FORWARD_URL) {
                showNotificationForwardSettingsDialog()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_TODO_URL) {
                showTodoUrlDialog()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == TOGGLE_CAMERA_SERVER) {
                toggleCameraServer(menuItem.isChecked)
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == TOGGLE_COMPUTER_SPEAKER) {
                val enabled = !menuItem.isChecked
                MeSettings.setEnabled(this, MeSettings.KEY_COMPUTER_SPEAKER, enabled)
                menuItem.isChecked = enabled
                MeService.me?.syncCameraServerSetting()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == EDIT_CAMERA_PASSWORD) {
                showCameraPasswordDialog()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == CONFIG_CAMERA_AUTO_BRIGHTNESS) {
                showCameraAutoBrightnessDialog()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_RECORD_SETTINGS) {
                showRecordSettingsDialog()
                return@setOnMenuItemClickListener true
            } else if (menuItem.itemId == OPEN_DEVELOPER_OPTIONS) {
                openDeveloperOptions()
                return@setOnMenuItemClickListener true
            }


            val settingItem = settingsMenuItems.getOrNull(menuItem.itemId - MENU_SETTING_START)
            if (settingItem != null) {
                val enabled = !menuItem.isChecked
                MeSettings.setEnabled(this, settingItem.key, enabled)
                if (settingItem.key == MeSettings.KEY_NO_SCREEN) {
                    useClockMode = !enabled &&
                            MeSettings.isEnabled(this, MeSettings.KEY_CLOCK)
                }
                if (settingItem.key == MeSettings.KEY_LYRICS) {
                    MeService.me?.syncLyricsSetting()
                }
                menuItem.isChecked = enabled
                true
            } else {
                false
            }
        }
        popupMenu.setOnDismissListener {
            // 长按 Center 进入菜单后，返回时恢复应用自身的按键劫持。
            dpadPassthrough = false
            dpadCenterLongPressTriggered = false
        }
        settingsPopupMenu = popupMenu
        return popupMenu
    }

    private fun homeLauncherComponent() =
        ComponentName(packageName, "$packageName.HomeLauncherAlias")

    private fun isHomeLauncherEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(homeLauncherComponent()) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }

    private fun toggleHomeLauncher(currentlyChecked: Boolean) {
        val enabled = !currentlyChecked
        try {
            packageManager.setComponentEnabledSetting(
                homeLauncherComponent(),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
            settingsPopupMenu?.menu?.findItem(TOGGLE_HOME_LAUNCHER)?.isChecked = enabled
        } catch (_: Exception) {
            settingsPopupMenu?.menu?.findItem(TOGGLE_HOME_LAUNCHER)?.isChecked =
                isHomeLauncherEnabled()
            Toast.makeText(this, "桌面设置修改失败", Toast.LENGTH_LONG).show()
            return
        }

        if (enabled) {
            requestDefaultHomeSelection()
        } else {
            Toast.makeText(this, "已取消作为桌面", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDefaultHomeSelection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
                    Toast.makeText(this, "当前已是默认桌面", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                }
                return
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                return
            } catch (_: Exception) {
            }
        }

        Toast.makeText(this, "请按Home键并选择${getString(R.string.app_name)}作为桌面", Toast.LENGTH_LONG).show()
    }

    private fun openDeviceAdminSettings() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponentName = ComponentName(this, MeDeviceAdminReceiver::class.java)
        if (devicePolicyManager.isAdminActive(adminComponentName)) {
            try {
                startActivity(Intent("android.settings.DEVICE_ADMIN_SETTINGS"))
                Toast.makeText(this, "熄屏权限已授权", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    Toast.makeText(this, "请在安全设置中打开设备管理应用", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "打开设备管理员设置失败", Toast.LENGTH_LONG).show()
                }
            }
            return
        }

        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "远程锁屏需要这个权限"
            )
        }
        startActivity(intent)
        Toast.makeText(this,"请激活设备管理员权限\n远程锁屏需要这个权限\n卸载app需要在这里卸载", Toast.LENGTH_LONG).show()
    }

    private fun hasDeviceAdminPermission(): Boolean {
        val manager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return manager.isAdminActive(ComponentName(this, MeDeviceAdminReceiver::class.java))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "请开启工作咩闹钟辅助功能", Toast.LENGTH_LONG).show()
    }

    private fun toggleCameraServer(currentlyChecked: Boolean) {
        if (currentlyChecked) {
            MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_SERVER, false)
            settingsPopupMenu?.menu?.findItem(TOGGLE_CAMERA_SERVER)?.isChecked = false
            MeService.me?.syncCameraServerSetting()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val missingPermissions = listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isEmpty()) {
                enableCameraServer()
                return
            }
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_MEDIA_SERVER_PERMISSIONS
            )
            return
        }
        enableCameraServer()
    }

    private fun enableCameraServer() {
        MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_SERVER, true)
        settingsPopupMenu?.menu?.findItem(TOGGLE_CAMERA_SERVER)?.isChecked = true
        MeService.me?.syncCameraServerSetting()
    }

    private fun showCameraPasswordDialog() {
        val input = EditText(this).apply {
            hint = "视频：ip:8880/密码/1，声音：ip:8880/密码/aac"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(MeSettings.getCameraPassword(this@MainActivity))
            setSelection(text.length)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("摄像头密码")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = input.text.toString().trim().trim('/')
                if (password.any { it == '/' || it == '?' || it == '#' }) {
                    input.error = "密码不能包含 /、?、#"
                    return@setOnClickListener
                }
                MeSettings.setCameraPassword(this, password)
                MeService.me?.updateCameraPassword()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showRecordSettingsDialog() {
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padTop = (10 * density).toInt()
        val padSmall = (6 * density).toInt()

        fun sectionLabel(textValue: String) = TextView(this).apply {
            text = textValue
            textSize = 14f
            setPadding(0, padTop, 0, padSmall)
        }

        fun textInput(hintValue: String, current: String, passwordStyle: Boolean = false) =
            EditText(this).apply {
                hint = hintValue
                setSingleLine(true)
                inputType = if (passwordStyle) {
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_URI or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                setText(current)
                setSelection(text.length)
            }

        fun numberInput(current: Int) = EditText(this).apply {
            hint = current.toString()
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, 0, padH, 0)
        }

        val enabledSwitch = Switch(this).apply {
            text = "开启 NAS 录像（每分钟一个文件）"
            isChecked = MeSettings.isRecordEnabled(this@MainActivity)
        }
        container.addView(enabledSwitch)

        container.addView(sectionLabel("WebDAV 服务器地址（例如 http://192.168.1.10:5005）"))
        val urlInput = textInput("http://nas地址:端口", MeSettings.getWebDavUrl(this))
        container.addView(urlInput)

        container.addView(sectionLabel("账号（无鉴权可留空）"))
        val usernameInput = textInput("WebDAV 账号", MeSettings.getWebDavUsername(this))
        container.addView(usernameInput)

        container.addView(sectionLabel("密码（无鉴权可留空）"))
        val passwordInput = textInput("WebDAV 密码", MeSettings.getWebDavPassword(this), true)
        container.addView(passwordInput)

        // ===== 局域网 NAS 自动搜索：发现咩存储的 WebDAV 服务，验证通过后填入地址 =====
        val scanNasButton = android.widget.Button(this).apply {
            text = "搜索局域网 NAS（自动查找咩存储）"
        }
        container.addView(scanNasButton)
        val scanNasStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padSmall, 0, 0)
        }
        container.addView(scanNasStatus)

        scanNasButton.setOnClickListener {
            scanNasStatus.text = "正在搜索局域网 NAS（约 5 秒）…"
            scanNasButton.isEnabled = false
            NasScanner.scan(this) { candidates ->
                scanNasButton.isEnabled = true
                if (candidates.isEmpty()) {
                    scanNasStatus.text = "未发现 NAS。请确认存储手机已开启 FTP/WebDAV 服务，" +
                        "且与本机连接同一局域网。"
                    return@scan
                }
                scanNasStatus.text = "发现 ${candidates.size} 个候选，正在验证 WebDAV 连接…"
                val username = usernameInput.text.toString().trim()
                val password = passwordInput.text.toString()
                Thread {
                    val verified = mutableListOf<NasScanner.Candidate>()
                    for (c in candidates) {
                        try {
                            WebDavClient("http://${c.host}:${c.port}", username, password)
                                .testConnection()
                            verified.add(c)
                        } catch (_: Exception) {
                            // 验证失败（非 WebDAV 服务或账号密码不对）的候选不列出
                        }
                    }
                    runOnUiThread {
                        if (verified.isEmpty()) {
                            scanNasStatus.text =
                                "发现 ${candidates.size} 个设备，但 WebDAV 验证均未通过，" +
                                    "请检查上方账号密码后重试。"
                        } else {
                            val items = verified.map { it.toString() }.toTypedArray()
                            AlertDialog.Builder(this)
                                .setTitle("选择 NAS（已通过 WebDAV 验证）")
                                .setItems(items) { _, which ->
                                    val picked = verified[which]
                                    urlInput.setText(picked.suggestedUrl())
                                    urlInput.setSelection(urlInput.text.length)
                                    scanNasStatus.text =
                                        "已填入 ${picked.suggestedUrl()}，点击「保存」生效"
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }.start()
            }
        }

        container.addView(sectionLabel("录像存放目录（相对地址，可留空存到根目录）"))
        val dirInput = textInput("例如 monitor/recordings", MeSettings.getWebDavDir(this))
        container.addView(dirInput)

        val testButton = android.widget.Button(this).apply {
            text = "测试连接"
        }
        val testStatus = TextView(this).apply {
            textSize = 13f
            setPadding(0, padSmall, 0, 0)
        }
        container.addView(testButton)
        container.addView(testStatus)

        container.addView(sectionLabel("录像清晰度"))
        val heightGroup = RadioGroup(this)
        val height720 = RadioButton(this).apply { text = "720p"; id = 720 }
        val height1080 = RadioButton(this).apply { text = "1080p"; id = 1080 }
        heightGroup.addView(height720)
        heightGroup.addView(height1080)
        heightGroup.check(
            if (MeSettings.getRecordHeight(this) == MeSettings.RECORD_HEIGHT_720) 720 else 1080
        )
        container.addView(heightGroup)

        container.addView(sectionLabel("画面旋转角度（摄像头倒挂时选 180°，新录制的视频生效）"))
        val rotationGroup = RadioGroup(this)
        listOf(0, 90, 180, 270).forEach { angle ->
            rotationGroup.addView(RadioButton(this).apply {
                text = angle.toString() + "°"
                id = 1000 + angle
            })
        }
        rotationGroup.check(1000 + MeSettings.getRecordRotation(this))
        container.addView(rotationGroup)

        container.addView(sectionLabel("视频编码（影响直播和录像，切换后自动重启摄像头服务）"))
        val codecGroup = RadioGroup(this)
        val codecH264 = RadioButton(this).apply { text = "H.264（兼容最好，推荐）"; id = 2000 }
        val codecH265 = RadioButton(this).apply {
            text = "H.265 / HEVC（省一半空间，部分旧设备/浏览器放不了）"; id = 2001
        }
        codecGroup.addView(codecH264)
        codecGroup.addView(codecH265)
        codecGroup.check(if (MeSettings.getRecordCodec(this) == MeSettings.CODEC_H265) 2001 else 2000)
        container.addView(codecGroup)

        container.addView(sectionLabel("录像帧率（每秒帧数 10~30，默认 20，影响直播和录像，切换后自动重启摄像头服务）"))
        val fpsGroup = RadioGroup(this)
        listOf(10, 15, 20, 25, 30).forEach { fps ->
            fpsGroup.addView(RadioButton(this).apply {
                text = fps.toString() + " fps"
                id = 5000 + fps
            })
        }
        fpsGroup.check(5000 + MeSettings.getRecordFps(this))
        container.addView(fpsGroup)

        container.addView(sectionLabel("画面静止时策略（帧差法检测：每 0.5 秒对比一帧，变动约 1.5 秒触发、静止约 6 秒确认，防灯光频闪误触发）"))
        val policyGroup = RadioGroup(this)
        val policyOptions = listOf(
            MeSettings.STATIC_POLICY_ALL to "全录（不变，默认）",
            MeSettings.STATIC_POLICY_TIMELAPSE to "A 抽帧录像（静止时只存关键帧，约 1fps，回看是快放效果）",
            MeSettings.STATIC_POLICY_LOW_BITRATE to "B 动态码率（静止时降到 100kbps，画面连续）",
            MeSettings.STATIC_POLICY_BOTH to "A+B（抽帧 + 低码率）"
        )
        policyOptions.forEach { (value, label) ->
            policyGroup.addView(RadioButton(this).apply { text = label; id = 3000 + value })
        }
        policyGroup.check(3000 + MeSettings.getRecordStaticPolicy(this))
        container.addView(policyGroup)

        container.addView(sectionLabel("静止检测灵敏度（差异像素占比阈值，另含强差异小目标检测）"))
        val sensGroup = RadioGroup(this)
        listOf("高（0.3%，轻微动静就触发）", "中（0.6%）", "低（1.5%，较大动静才触发）").forEachIndexed { i, label ->
            sensGroup.addView(RadioButton(this).apply { text = label; id = 4000 + i })
        }
        sensGroup.check(4000 + MeSettings.getRecordMotionSensitivity(this))
        container.addView(sensGroup)

        container.addView(sectionLabel("摄像头序号（多摄像头设备可切换，默认 0）"))
        val cameraIndexInput = numberInput(MeSettings.getRecordCameraIndex(this))
        container.addView(cameraIndexInput)

        val audioSwitch = Switch(this).apply {
            text = "录制声音"
            isChecked = MeSettings.isRecordAudioEnabled(this@MainActivity)
            setPadding(0, padTop, 0, 0)
        }
        container.addView(audioSwitch)

        // ===== 本机 USB 存储（OTG U 盘），与 NAS 可同时启用，复用同一组空间限制策略 =====
        val usbSwitch = Switch(this).apply {
            text = "同时存到本机 USB 设备（OTG U 盘）"
            isChecked = MeSettings.isUsbRecordEnabled(this@MainActivity)
            setPadding(0, padTop, 0, 0)
        }
        container.addView(usbSwitch)

        val selectUsbButton = android.widget.Button(this).apply {
            text = "选择 USB 录像目录"
        }
        container.addView(selectUsbButton)

        // 目录路径用局部持有；SAF 回调先把选择结果持久化到 MeSettings，保存时若本地为空会回退读取。
        var selectedUsbUri: String = MeSettings.getUsbRecordTreeUri(this)
        val usbPathLabel = TextView(this).apply {
            textSize = 12f
            setPadding(0, padSmall, 0, 0)
        }
        fun renderUsbPath() {
            usbPathLabel.text = if (selectedUsbUri.isNotEmpty()) {
                "已选择 USB 目录（uri 末段）：…/" + Uri.parse(selectedUsbUri).lastPathSegment.orEmpty()
                    .substringAfterLast('/')
            } else {
                "尚未选择目录：点击上方按钮，在 U 盘上新建或选择一个录像文件夹"
            }
        }
        renderUsbPath()
        container.addView(usbPathLabel)
        recordDialogUsbSwitch = usbSwitch
        recordDialogUsbPathLabel = usbPathLabel

        selectUsbButton.setOnClickListener {
            try {
                // 让系统同时展示可移动存储，并允许在 U 盘上新建目录。
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                }
                startActivityForResult(intent, REQUEST_USB_TREE)
            } catch (_: Exception) {
                Toast.makeText(this, "当前系统不支持选择目录", Toast.LENGTH_LONG).show()
            }
        }

        container.addView(sectionLabel("NAS 空间限制方式（NAS 与 USB 共用）"))
        val quotaGroup = RadioGroup(this)
        val reserveRadio = RadioButton(this).apply {
            text = "保留可用空间（可用空间不足时删除最早录像）"
            id = MeSettings.QUOTA_MODE_RESERVE
        }
        val limitRadio = RadioButton(this).apply {
            text = "限制录像占用空间（超出后删除最早录像）"
            id = MeSettings.QUOTA_MODE_LIMIT
        }
        quotaGroup.addView(reserveRadio)
        quotaGroup.addView(limitRadio)
        quotaGroup.check(MeSettings.getQuotaMode(this))
        container.addView(quotaGroup)

        container.addView(sectionLabel("保留可用空间（MB，默认 1024 = 1GB）"))
        val reserveInput = numberInput(MeSettings.getQuotaReserveMb(this))
        container.addView(reserveInput)

        container.addView(sectionLabel("录像占用上限（MB，默认 10240 = 10GB）"))
        val limitInput = numberInput(MeSettings.getQuotaLimitMb(this))
        container.addView(limitInput)

        container.addView(sectionLabel("本机待上传缓存上限（MB，默认 512）"))
        val cacheInput = numberInput(MeSettings.getLocalCacheMb(this))
        container.addView(cacheInput)

        fun updateQuotaFieldState() {
            val reserveMode = quotaGroup.checkedRadioButtonId == MeSettings.QUOTA_MODE_RESERVE
            reserveInput.isEnabled = reserveMode
            limitInput.isEnabled = !reserveMode
        }
        quotaGroup.setOnCheckedChangeListener { _, _ -> updateQuotaFieldState() }
        updateQuotaFieldState()

        container.addView(TextView(this).apply {
            text = "手机浏览器访问 http://设备IP:8880/recordings 可查看和下载录像。"
            textSize = 12f
            setPadding(0, padTop, 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("NAS录像设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()

        testButton.setOnClickListener {
            val url = urlInput.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                testStatus.text = "地址需以 http:// 或 https:// 开头"
                return@setOnClickListener
            }
            testStatus.text = "正在连接…"
            testButton.isEnabled = false
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()
            val dir = dirInput.text.toString().trim().trim('/')
            Thread {
                var success = false
                var message = ""
                try {
                    // 域名（workdayXXXXXX.local）先经 mDNS 解析成 IP 再连接。
                    val resolvedUrl = LanDomainResolver.resolveUrl(this, url).first
                    val client = WebDavClient(resolvedUrl, username, password)
                    client.testConnection()
                    if (dir.isNotEmpty()) {
                        client.mkdirs(dir)
                    }
                    success = true
                } catch (e: WebDavException) {
                    message = e.message ?: "连接失败"
                } catch (e: Exception) {
                    message = "连接失败：${e.message ?: e.javaClass.simpleName}"
                }
                if (!success) LanDomainResolver.evictUrl(url)
                val finalMessage = message
                runOnUiThread {
                    testButton.isEnabled = true
                    testStatus.text = if (success) {
                        "连接成功" + if (dir.isNotEmpty()) "，目录可访问" else ""
                    } else {
                        finalMessage
                    }
                }
            }.start()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = urlInput.text.toString().trim().trimEnd('/')
                if (url.isNotEmpty() &&
                    !url.startsWith("http://", ignoreCase = true) &&
                    !url.startsWith("https://", ignoreCase = true)
                ) {
                    urlInput.error = "地址需以 http:// 或 https:// 开头"
                    return@setOnClickListener
                }
                if (url.isEmpty() && enabledSwitch.isChecked && !usbSwitch.isChecked) {
                    urlInput.error = "NAS 与 USB 至少需要配置一个存储目标"
                    return@setOnClickListener
                }
                // SAF 回调可能已更新持久化值；以当前选择为准，未选择时回退已保存值。
                val effectiveUsbUri = selectedUsbUri.ifEmpty { MeSettings.getUsbRecordTreeUri(this) }
                // 勾选了 USB 就必须先选好并持久授权一个目录。
                if (usbSwitch.isChecked && effectiveUsbUri.isEmpty()) {
                    usbPathLabel.text = "请先点击“选择 USB 录像目录”授权一个文件夹"
                    return@setOnClickListener
                }
                val cameraIndex = cameraIndexInput.text.toString().ifEmpty { "0" }.toIntOrNull()
                if (cameraIndex == null || cameraIndex < 0) {
                    cameraIndexInput.error = "请输入大于等于0的序号"
                    return@setOnClickListener
                }
                val reserveMb = reserveInput.text.toString()
                    .ifEmpty { MeSettings.DEFAULT_QUOTA_RESERVE_MB.toString() }
                    .toIntOrNull()
                if (reserveMb == null || reserveMb <= 0) {
                    reserveInput.error = "请输入大于0的MB数"
                    return@setOnClickListener
                }
                val limitMb = limitInput.text.toString()
                    .ifEmpty { MeSettings.DEFAULT_QUOTA_LIMIT_MB.toString() }
                    .toIntOrNull()
                if (limitMb == null || limitMb <= 0) {
                    limitInput.error = "请输入大于0的MB数"
                    return@setOnClickListener
                }
                val cacheMb = cacheInput.text.toString()
                    .ifEmpty { MeSettings.DEFAULT_LOCAL_CACHE_MB.toString() }
                    .toIntOrNull()
                if (cacheMb == null || cacheMb <= 0) {
                    cacheInput.error = "请输入大于0的MB数"
                    return@setOnClickListener
                }

                MeSettings.setWebDavUrl(this, url)
                MeSettings.setWebDavUsername(this, usernameInput.text.toString())
                MeSettings.setWebDavPassword(this, passwordInput.text.toString())
                MeSettings.setWebDavDir(this, dirInput.text.toString())
                MeSettings.setRecordHeight(this, if (heightGroup.checkedRadioButtonId == 720) {
                    MeSettings.RECORD_HEIGHT_720
                } else {
                    MeSettings.RECORD_HEIGHT_1080
                })
                MeSettings.setRecordCameraIndex(this, cameraIndex)
                MeSettings.setRecordAudioEnabled(this, audioSwitch.isChecked)
                val rotationAngle = rotationGroup.checkedRadioButtonId - 1000
                MeSettings.setRecordRotation(this, rotationAngle)
                val newCodec = if (codecGroup.checkedRadioButtonId == 2001) {
                    MeSettings.CODEC_H265
                } else {
                    MeSettings.CODEC_H264
                }
                val newPolicy = policyGroup.checkedRadioButtonId - 3000
                val newSens = sensGroup.checkedRadioButtonId - 4000
                val newFps = fpsGroup.checkedRadioButtonId - 5000
                val needServerRestart = newCodec != MeSettings.getRecordCodec(this) ||
                    newPolicy != MeSettings.getRecordStaticPolicy(this) ||
                    newSens != MeSettings.getRecordMotionSensitivity(this) ||
                    newFps != MeSettings.getRecordFps(this)
                MeSettings.setRecordCodec(this, newCodec)
                MeSettings.setRecordStaticPolicy(this, newPolicy)
                MeSettings.setRecordMotionSensitivity(this, newSens)
                MeSettings.setRecordFps(this, newFps)
                MeSettings.setQuotaMode(this, quotaGroup.checkedRadioButtonId)
                MeSettings.setQuotaReserveMb(this, reserveMb)
                MeSettings.setQuotaLimitMb(this, limitMb)
                MeSettings.setLocalCacheMb(this, cacheMb)
                // USB 目录与开关随录像设置一起持久化；tree uri 在选择目录时已取得持久授权。
                MeSettings.setUsbRecordTreeUri(this, effectiveUsbUri)
                MeSettings.setUsbRecordEnabled(this, usbSwitch.isChecked)
                MeSettings.setRecordEnabled(this, enabledSwitch.isChecked)

                val storageActive = url.isNotEmpty() || (usbSwitch.isChecked && effectiveUsbUri.isNotEmpty())
                settingsPopupMenu?.menu?.findItem(OPEN_RECORD_SETTINGS)?.title =
                    if (enabledSwitch.isChecked && storageActive) "录像设置（已开启）" else "NAS录像设置"

                val wantRecord = enabledSwitch.isChecked && storageActive
                if (wantRecord && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val missing = buildList {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.CAMERA
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.CAMERA)
                        }
                        if (audioSwitch.isChecked &&
                            ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            add(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    if (missing.isNotEmpty()) {
                        openRecordDialogAfterPermission = true
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            missing.toTypedArray(),
                            REQUEST_RECORD_PERMISSIONS
                        )
                    } else {
                        if (needServerRestart) {
                            MeService.me?.restartCameraServer()
                        } else {
                            MeService.me?.syncCameraServerSetting()
                        }
                    }
                } else {
                    if (needServerRestart) {
                        MeService.me?.restartCameraServer()
                    } else {
                        MeService.me?.syncCameraServerSetting()
                    }
                }
                dialog.dismiss()
            }
        }
        dialog.show()
        dialog.setOnDismissListener {
            // 释放对已关闭对话框控件的引用，避免泄漏 Activity。
            recordDialogUsbPathLabel = null
            recordDialogUsbSwitch = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_USB_TREE) return
        val treeUri = data?.data
        if (resultCode != RESULT_OK || treeUri == null) {
            Toast.makeText(this, "未选择目录", Toast.LENGTH_SHORT).show()
            return
        }
        // 持久化读写授权，重启后仍可访问该 U 盘目录（U 盘拔出时授权暂不生效，重新插入即恢复）。
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, flags)
            val uriString = treeUri.toString()
            MeSettings.setUsbRecordTreeUri(this, uriString)
            // 回写到当前打开的对话框；若对话框已关，则下次打开会读取已保存值。
            recordDialogUsbPathLabel?.text =
                "已选择 USB 目录（uri 末段）：…/" + treeUri.lastPathSegment.orEmpty()
                    .substringAfterLast('/')
            recordDialogUsbSwitch?.isChecked = true
            Toast.makeText(this, "已授权 USB 目录", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "USB 目录授权失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun showCameraAutoBrightnessDialog() {
        // 摄像头自动亮度打开后会立即启动采样，因此在进入设置页时就请求摄像头权限。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            openAmbientDialogAfterPermission = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_AMBIENT_CAMERA_PERMISSION
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            Toast.makeText(this, "请允许修改系统设置，用于调节系统亮度", Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        "package:$packageName".toUri()
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        fun numberInput(defaultValue: String, currentValue: String, decimal: Boolean = false): EditText {
            return EditText(this).apply {
                hint = defaultValue
                inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) {
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
                } else {
                    0
                }
                if (currentValue != defaultValue) setText(currentValue)
                setSingleLine(true)
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
        }
        fun addNumberField(label: String, input: EditText) {
            container.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            })
            container.addView(input)
        }
        fun applyManualSystemBrightness(value: Int) {
            val brightness = value.coerceIn(0, 255)
            try {                Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
                Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            } catch (_: Exception) {
            }
            fun applyWindow(window: android.view.Window) {
                val attributes = window.attributes
                attributes.screenBrightness = brightness / 255f
                window.attributes = attributes
            }
            applyWindow(window)
            ClockActivity.me?.let { applyWindow(it.window) }
            DeskActivity.me?.let { applyWindow(it.window) }
        }
        fun addBrightnessField(label: String, seekBar: SeekBar) {
            val labelView = TextView(this).apply {
                textSize = 14f
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            fun updateLabel() {
                labelView.text = "$label：${seekBar.progress}"
            }
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    updateLabel()
                    if (fromUser) applyManualSystemBrightness(progress)
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
            updateLabel()
            container.addView(labelView)
            container.addView(seekBar)
        }

        val enabledSwitch = Switch(this).apply {
            text = "启用摄像头自动亮度"
            isChecked = MeSettings.isEnabled(this@MainActivity, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS)
        }
        container.addView(enabledSwitch)
        val faceWakeSwitch = Switch(this).apply {
            text = "人脸检测触发亮屏/无人脸熄屏"
            isChecked = MeSettings.isEnabled(this@MainActivity, MeSettings.KEY_CAMERA_FACE_WAKE)
            setOnCheckedChangeListener { _, checked ->
                MeService.me?.setAmbientPreviewFaceWakeEnabled(checked)
            }
        }
        container.addView(faceWakeSwitch)
        fun brightnessSeekBar(value: Int): SeekBar {
            return SeekBar(this).apply {
                max = 255
                progress = value.coerceIn(0, 255)
            }
        }
        val level1 = brightnessSeekBar(MeSettings.getInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_1, 8))
        val level2 = brightnessSeekBar(MeSettings.getInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_2, 32))
        val level3 = brightnessSeekBar(MeSettings.getInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_3, 96))
        val level4 = brightnessSeekBar(MeSettings.getInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_4, 255))
        addBrightnessField("档位1系统亮度 (0-255)", level1)
        addBrightnessField("档位2系统亮度 (0-255)", level2)
        addBrightnessField("档位3系统亮度 (0-255)", level3)
        addBrightnessField("档位4系统亮度 (0-255)", level4)
        val closeScreenSwitch = Switch(this).apply {
            text = "档位0关闭屏幕（关闭时设置亮度0）"
            isChecked = MeSettings.isEnabled(this@MainActivity, MeSettings.KEY_CAMERA_CLOSE_SCREEN)
            setOnCheckedChangeListener { button, checked ->
                if (checked && !hasDeviceAdminPermission()) {
                    button.isChecked = false
                    openDeviceAdminSettings()
                }
            }
        }
        container.addView(closeScreenSwitch)
        val closeScreenLevel1Switch = Switch(this).apply {
            text = "档位1关闭屏幕（关闭时设置上面的亮度）"
            isChecked = MeSettings.isEnabled(this@MainActivity, MeSettings.KEY_CAMERA_CLOSE_SCREEN_LEVEL_1)
            setOnCheckedChangeListener { button, checked ->
                if (checked && !hasDeviceAdminPermission()) {
                    button.isChecked = false
                    openDeviceAdminSettings()
                }
            }
        }
        container.addView(closeScreenLevel1Switch)
        val closeScreenKeepScreenOnSwitch = Switch(this).apply {
            text = "保持亮屏时仍关闭屏幕"
            isChecked = MeSettings.isEnabled(this@MainActivity, MeSettings.KEY_CAMERA_CLOSE_SCREEN_KEEP_SCREEN_ON)
            setOnCheckedChangeListener { button, checked ->
                if (checked && !hasDeviceAdminPermission()) {
                    button.isChecked = false
                    openDeviceAdminSettings()
                }
            }
        }
        container.addView(closeScreenKeepScreenOnSwitch)
        val wakeLevel = numberInput(
            "0",
            MeSettings.getInt(this, MeSettings.KEY_CAMERA_AUTO_WAKE_LEVEL, 0).toString()
        )
        addNumberField("自动亮屏档位 (0-4，0不开)", wakeLevel)
        val interval = numberInput(
            "0",
            MeSettings.getString(this, MeSettings.KEY_CAMERA_BRIGHTNESS_INTERVAL, "0"),
            true
        )
        addNumberField("检测间隔（分钟，可小数，0连续）", interval)
        val scroll = ScrollView(this).apply { addView(container) }
        val currentBrightnessLevel = MeService.me?.ambientBrightnessLevel() ?: 4
        val dialog = AlertDialog.Builder(this)
            .setTitle("摄像头自动亮度（当前等级 $currentBrightnessLevel，系统亮度 ${MeService.me?.ambientBrightnessValue() ?: 255}）")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            ) {
                MeService.me?.startAmbientBrightnessPreview()
            }
            val titleUpdater = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    val level = MeService.me?.ambientBrightnessLevel() ?: 4
                    val value = MeService.me?.ambientBrightnessValue() ?: 255
                    val faceStatus = MeService.me?.ambientFaceStatus() ?: "未开启"
                    dialog.setTitle("摄像头自动亮度（当前等级 $level，系统亮度 $value，人脸：$faceStatus）")
                    mainHandler.postDelayed(this, 500L)
                }
            }
            mainHandler.post(titleUpdater)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val levels = listOf(level1, level2, level3, level4).map { it.progress }
                val wake = wakeLevel.text.toString().toIntOrNull()
                    ?: 0
                if (wake == null || wake !in 0..4) {
                    wakeLevel.error = "请输入0到4"
                    return@setOnClickListener
                }
                val intervalValue = interval.text.toString().ifEmpty { "0" }
                val intervalMinutes = intervalValue.toDoubleOrNull()
                if (intervalMinutes == null || intervalMinutes < 0.0) {
                    interval.error = "请输入大于等于0的分钟数"
                    return@setOnClickListener
                }

                MeSettings.setInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_1, levels[0])
                MeSettings.setInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_2, levels[1])
                MeSettings.setInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_3, levels[2])
                MeSettings.setInt(this, MeSettings.KEY_CAMERA_BRIGHTNESS_4, levels[3])
                MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_CLOSE_SCREEN, closeScreenSwitch.isChecked)
                MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_CLOSE_SCREEN_LEVEL_1, closeScreenLevel1Switch.isChecked)
                MeSettings.setEnabled(
                    this,
                    MeSettings.KEY_CAMERA_CLOSE_SCREEN_KEEP_SCREEN_ON,
                    closeScreenKeepScreenOnSwitch.isChecked
                )
                MeSettings.setInt(this, MeSettings.KEY_CAMERA_AUTO_WAKE_LEVEL, wake)
                MeSettings.setString(this, MeSettings.KEY_CAMERA_BRIGHTNESS_INTERVAL, intervalValue.trim())
                MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_FACE_WAKE, faceWakeSwitch.isChecked)

                val shouldEnable = enabledSwitch.isChecked
                MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_AUTO_BRIGHTNESS, shouldEnable)
                MeService.me?.syncAmbientBrightnessSetting()
                dialog.dismiss()
            }
            dialog.setOnDismissListener {
                mainHandler.removeCallbacks(titleUpdater)
                MeService.me?.stopAmbientBrightnessPreview()
            }
        }
        dialog.show()
    }

    private fun openDeveloperOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "打开开发者选项失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AMBIENT_CAMERA_PERMISSION) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            val openDialog = openAmbientDialogAfterPermission
            openAmbientDialogAfterPermission = false
            if (granted && openDialog) {
                showCameraAutoBrightnessDialog()
            } else if (!granted) {
                Toast.makeText(this, "摄像头权限未授权，无法使用自动亮度", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (requestCode == REQUEST_RECORD_PERMISSIONS) {
            openRecordDialogAfterPermission = false
            val cameraGranted = ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (!cameraGranted) {
                MeSettings.setRecordEnabled(this, false)
                settingsPopupMenu?.menu?.findItem(OPEN_RECORD_SETTINGS)?.title = "NAS录像设置"
                Toast.makeText(this, "摄像头权限未授权，NAS录像无法开启", Toast.LENGTH_LONG).show()
            }
            MeService.me?.syncCameraServerSetting()
            return
        }
        if (requestCode != REQUEST_MEDIA_SERVER_PERMISSIONS) return

        val hasCamera = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val hasMicrophone = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCamera || hasMicrophone) {
            enableCameraServer()
        } else {
            MeSettings.setEnabled(this, MeSettings.KEY_CAMERA_SERVER, false)
            settingsPopupMenu?.menu?.findItem(TOGGLE_CAMERA_SERVER)?.isChecked = false
            Toast.makeText(this, "摄像头和麦克风权限都未授权", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNotificationForwardSettingsDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val horizontalPadding = (20 * density).toInt()
            setPadding(horizontalPadding, (8 * density).toInt(), horizontalPadding, 0)
        }

        container.addView(TextView(this).apply {
            text = "转发 URL"
            textSize = 14f
        })
        val input = EditText(this).apply {
            hint = "通知内容将拼在URL末端推送，也支持标签替换：应用{app}包名{pkg}标题{title}内容{msg}"
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(MeSettings.getNotificationForwardUrl(this@MainActivity))
            setSelection(text.length)
        }
        container.addView(input)

        val screenOffOnlySwitch = Switch(this).apply {
            text = "亮屏时不推送"
            isChecked = MeSettings.isEnabled(
                this@MainActivity,
                MeSettings.KEY_NOTIFICATION_FORWARD_SCREEN_OFF_ONLY
            )
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        container.addView(screenOffOnlySwitch)

        container.addView(TextView(this).apply {
            text = "应用黑名单（勾选后不推送）"
            textSize = 14f
            setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
        })

        val savedBlacklist = MeSettings.getNotificationForwardBlacklist(this)
        val recentPackageNames = MeNotificationListenerService.getRecentPackageNames()
        val recentPackageSet = recentPackageNames.toSet()

        fun appDisplayName(packageName: String): String {
            return try {
                val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                val appName = packageManager.getApplicationLabel(applicationInfo).toString()
                if (appName == packageName) packageName else "$appName\n$packageName"
            } catch (_: Exception) {
                packageName
            }
        }

        val recentChoices = recentPackageNames.map { it to appDisplayName(it) }
        val blacklistOnlyChoices = savedBlacklist
            .filterNot { it in recentPackageSet }
            .map { it to appDisplayName(it) }
            .sortedBy { it.second.lowercase() }
        val appChoices = recentChoices + blacklistOnlyChoices
        val appCheckBoxes = LinkedHashMap<String, CheckBox>()

        if (appChoices.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无最近转发过通知的应用"
                setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
            })
        } else {
            appChoices.forEach { (packageName, displayName) ->
                val checkBox = CheckBox(this).apply {
                    text = displayName
                    isChecked = packageName in savedBlacklist
                    setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
                }
                appCheckBoxes[packageName] = checkBox
                container.addView(checkBox)
            }
        }

        val scroll = ScrollView(this).apply { addView(container) }

        AlertDialog.Builder(this)
            .setTitle("通知转发设置")
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val url = input.text.toString().trim()
                MeSettings.setNotificationForwardUrl(this, url)
                MeSettings.setEnabled(
                    this,
                    MeSettings.KEY_NOTIFICATION_FORWARD_SCREEN_OFF_ONLY,
                    screenOffOnlySwitch.isChecked
                )
                MeSettings.setNotificationForwardBlacklist(
                    this,
                    appCheckBoxes.filterValues { it.isChecked }.keys
                )
                if (url.isEmpty()) {
                    Toast.makeText(this, "通知转发功能已关闭", Toast.LENGTH_SHORT).show()
                } else {
                    requestNotificationListenerPermissionIfNeeded()
                }
            }
            .show()
    }

    private fun showTodoUrlDialog() {
        val input = EditText(this).apply {
            hint = "需要返回[\"待办1\",\"待办2\"]的jsonArray或纯文本"
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_VARIATION_URI or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(MeSettings.getTodoUrl(this@MainActivity))
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("待办接口URL")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                MeSettings.setTodoUrl(this, input.text.toString())
                MeService.me?.refreshTodoNow()
            }
            .show()
    }

    private fun requestNotificationListenerPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            Toast.makeText(this, "当前系统不支持通知读取权限", Toast.LENGTH_LONG).show()
            return
        }

        if (isNotificationListenerEnabled()) {
            Toast.makeText(this, "通知读取权限已授权", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "请开启${getString(R.string.app_name)}通知读取权限", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
                Toast.makeText(this, "请在系统设置中开启通知读取权限", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "打开通知读取权限设置失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false
        }
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        val expectedComponent = ComponentName(this, MeNotificationListenerService::class.java)
        return enabledListeners.split(":").any { listener ->
            val component = ComponentName.unflattenFromString(listener)
            component?.packageName == expectedComponent.packageName &&
                    component.className == expectedComponent.className
        }
    }

    private fun exitApp() {
        Toast.makeText(this, "${this.getString(R.string.app_name)} 服务已停止", Toast.LENGTH_SHORT).show()
        onDestroy()
        MeService.me?.stopSelf()
        exitProcess(0)
    }

    fun shellOnEnter() {
        val shellInput = findViewById<EditText>(R.id.shellInput)
        val cmd = shellInput.text.toString()
        shellInput.setText("")
        print2LogView("> $cmd")
        MeService.me?.send2Shell(cmd)
        shellInput.requestFocus()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshLogView)
        mainHandler.removeCallbacks(dpadCenterLongPressRunnable)
        if (::logScrollView.isInitialized) {
            logScrollView.removeCallbacks(scrollLogToBottom)
        }
        settingsPopupMenu?.dismiss()
        settingsPopupMenu = null
        print2LogView("控制台退出...")
//        mediaButtonReceiverDestroy()
//        stopService(Intent(this, MeService::class.java))
//        Toast.makeText(this, "${this.getString(R.string.app_name)} 在后台运行", Toast.LENGTH_SHORT).show()
        me = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 默认时钟模式的设备 返回退到全屏时钟
        if (useClockMode) {
            MeSettings.applyClockTheme(this)
            val intent = MeSettings.createClockIntent(this)
            intent.putExtra("clockMode", true)
            startActivity(intent)
        } else {
            super.onBackPressed()
        }
    }

    override fun dispatchKeyEvent(keyEvent: KeyEvent?): Boolean {
        if (dpadPassthrough) {
            if (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                dpadCenterLongPressTriggered = false
            }
            return super.dispatchKeyEvent(keyEvent)
        }
        // 方便按键机操作
        if (findViewById<EditText>(R.id.shellInput).text.toString() == "") {
            when (keyEvent?.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                        if (!dpadCenterLongPressTriggered) {
                            mainHandler.postDelayed(
                                dpadCenterLongPressRunnable,
                                DPAD_CENTER_LONG_PRESS_DELAY_MS
                            )
                        }
                        return true
                    }
                    if (MeService.me?.keyHandle(keyEvent.keyCode, true) == true) {
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    if (keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                        mainHandler.removeCallbacks(dpadCenterLongPressRunnable)
                        val wasLongPress = dpadCenterLongPressTriggered
                        dpadCenterLongPressTriggered = false
                        if (wasLongPress) return true
                        if (MeService.me?.keyHandleAction(KeyEvent.KEYCODE_DPAD_CENTER) == true) {
                            return true
                        }
                    } else if (keyEvent.keyCode == KeyEvent.KEYCODE_CALL) {
                        exitApp()
                    } else if (MeService.me?.keyHandle(keyEvent.keyCode, false) == true) {
                        return true
                    }
                }
            }
        } else if (keyEvent?.action == KeyEvent.ACTION_UP && keyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // 多亲这个系统不知道怎么回事不会调用setOnEditorActionListener
            shellOnEnter()
        }
        return super.dispatchKeyEvent(keyEvent)
    }

    /**
     * 将日志打印到logView
     */
    fun print2LogView(s:String) {
        MeService.me?.print2LogView(s)
    }

    fun show2LogView() {
        if (isActivityVisible && logRefreshPending.compareAndSet(false, true)) {
            mainHandler.postDelayed(refreshLogView, LOG_REFRESH_DELAY_MILLIS)
        }
    }

    fun updatePlaybackButton(showPause: Boolean) {
        runOnUiThread {
            if (::playButton.isInitialized) {
                playButton.isSelected = showPause
                playButton.contentDescription = if (showPause) "暂停" else "播放"
            }
        }
    }

    /**
     * 初始化媒体按键监听
     * 是的，除了写个监听器还要超麻烦的初始化
     */
    private fun mediaButtonReceiverInit() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        mediaComponentName = ComponentName(packageName, MeMediaButtonReceiver::class.java.name).apply {
            packageManager.setComponentEnabledSetting(
                this,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
            // Android 5.0
            if (Build.VERSION.SDK_INT >= 21) {
                mediaSessionCompat =
                    MediaSessionCompat(this@MainActivity, "WorkdayAlarmClock", this, null).apply {
                        //指明支持的按键信息类型
                        setFlags(
                            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                        )

                        setCallback(object : MediaSessionCompat.Callback() {
                            override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                                MeMediaButtonReceiver().onReceive(this@MainActivity, mediaButtonEvent)
                                return true
                            }
                        }, Handler(Looper.getMainLooper()))
                        isActive = true
                    }

            } else {
                audioManager.registerMediaButtonEventReceiver(this)
            }
        }

    }

    /**
     * 销毁媒体按键监听
     */
    private fun mediaButtonReceiverDestroy() {
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        // Android 5.0
        if (Build.VERSION.SDK_INT >= 21) {
            mediaSessionCompat?.let {
                it.setCallback(null)
                it.release()
            }
        } else {
            mediaComponentName?.let {
                audioManager.unregisterMediaButtonEventReceiver(it)
            }

        }
    }

}
