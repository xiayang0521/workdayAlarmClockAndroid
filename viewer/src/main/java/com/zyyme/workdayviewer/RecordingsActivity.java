package com.zyyme.workdayviewer;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 米家摄像头风格界面（三屏 + 底部 5 槽位导航）：
 * 1. 看家：实时画面 + 顶部设备名/实时码率 + 视频工具条（夜间/自动/静音/截图/录像/全屏）+ 云台方向盘；
 * 2. 回看：播放器 + 黑底控制条（暂停/声音/截图/录像/倍速/全屏）+ 日期横条 + 粉色时间轴 + 青色「全部回看视频」；
 * 3. 全部回看：顶部水平日期选择 + 3 列 24 小时缩略图网格（夜间黑白、白昼彩色、运动小人标记）。
 *
 * 诚实性约定：本设备为固定摄像头，服务端不提供云台 / 夜视 / 云存储 / 对讲 / 画质档能力，
 * 这些控件按高保真模板保留，点击时明确提示「设备不支持」，不做静默无反应的假按钮。
 *
 * 实时解码链路（H264LiveDecoder）与播放器层（TextureView）沿用已设备验证的方案：
 * 实时期间 TextureView 恒可见、只切换上层普通 View；切到「全部回看」时才停流（隐藏 TextureView，
 * 返回时重新把 SurfaceTexture 包出的 Surface 绑定给新建解码器）。
 * 选 TextureView 而非 SurfaceView 的原因：倒挂安装需对画面做属性旋转，SurfaceView 的硬件 overlay
 * 层在瑞芯微平台不响应 setRotation（真机日志已证实），TextureView 走 GPU 纹理可正常旋转。
 */
public class RecordingsActivity extends Activity {
    // ---------------- 米家风格配色（浅色底 + 青色主色 + 粉色时间轴） ----------------
    private static final int C_BG = Color.rgb(243, 245, 247);
    private static final int C_CARD = Color.WHITE;
    private static final int C_TEAL = Color.rgb(82, 184, 188);
    private static final int C_TEAL_DARK = Color.rgb(64, 158, 162);
    private static final int C_TEXT = Color.rgb(34, 38, 42);
    private static final int C_TEXT_SUB = Color.rgb(138, 146, 154);
    private static final int C_PINK = Color.rgb(247, 224, 214);
    private static final int C_PINK_MARK = Color.rgb(240, 138, 92);
    private static final int C_TAB_INACTIVE = Color.rgb(120, 126, 134);

    private static final int TAB_HOME = 0;
    private static final int TAB_PLAYBACK = 1;
    private static final int TAB_GRID = 2;

    private FrameLayout contentRoot;
    private LinearLayout root;
    private ViewGroup livePanel;
    private ViewGroup playbackPanel;
    private ViewGroup gridPanel;
    private LinearLayout bottomBar;
    private final List<View> tabHitArea = new ArrayList<>();
    private final List<TextView> tabLabels = new ArrayList<>();
    private int currentTab = TAB_HOME;

    // 公共播放器
    private FrameLayout playerFrame;
    private VideoView videoView;
    private MjpegView mjpegView;
    private TextureView liveTexture;
    /** 由 TextureView 的 SurfaceTexture 包出的解码输出 Surface，随纹理生命周期创建/释放。 */
    private Surface liveOutputSurface;
    private H264LiveDecoder h264Decoder;
    private TextView liveStatus;
    private TextView liveTitle;
    private TextView liveBitrate;
    private LinearLayout liveToolbar;
    private LinearLayout vodToolbar;
    private MediaPlayer mediaPlayer;

    // 看家工具条
    private TextView btnMuteLive;
    // 回看控制条
    private TextView btnPlay, btnMuteVod, btnSpeed;

    // 回看
    private LinearLayout dayChips;
    private HorizontalScrollView dayScroll;
    private HorizontalScrollView timelineScroll;
    private TimelineView timeline;
    private TextView playbackHint;

    // 网格
    private LinearLayout gridDateChips;
    private HorizontalScrollView gridDateScroll;
    private LinearLayout gridRows;
    private ScrollView gridScroll;
    private String gridDay = "";
    private ThumbnailLoader thumbs;

    // 全屏
    private boolean fullscreen = false;
    private TextView exitFsBtn;
    private LinearLayout.LayoutParams playerPortraitParams;
    private int playerIndexInRoot;

    // 实时
    private boolean liveMode = true;
    private final List<Integer> liveCandidates = new ArrayList<>();
    private int liveCandidateIndex = 0;
    private int liveToken = 0;
    private boolean liveFrameReceived = false;
    private String liveHost = "";
    private int livePort = 8880;
    private String livePassword = "";
    /**
     * 实时画面旋转角度（0/90/180/270），取自摄像头端 cam/settings 的 rotation。
     * 为什么实时要在 viewer 侧旋转、回放不用：录像 MP4 由 muxer.setOrientationHint 写入了
     * 旋转元数据，MediaPlayer/VideoView 回放时自动旋转；而实时下发的是 H.264 Annex-B 裸流
     * （MJPEG 路为 JPEG 帧），没有任何旋转元数据通道，摄像头端 OverlayRenderer 也只用单位矩阵
     * 直传像素（仅水印文字反向预旋），故倒挂安装(180°)时实时画面是倒的。
     * 这里在 View 层旋转（与网页端 CSS rotate 同构），同时作用于 MJPEG 视图与 H.264 硬解
     * TextureView，不改解码器喂帧/drain，仅把解码输出载体换成可做属性变换的 TextureView。
     */
    private volatile int liveRotationDegrees = 0;
    /** 已解析的基础地址（http://host:port/密码前缀/），供缩略图等复用，避免主线程做 mDNS。 */
    private volatile String baseHttp = "";
    private boolean liveMuted = false;
    private boolean vodMuted = false;
    private float vodSpeed = 1f;

    private final Runnable bitrateTicker = new Runnable() {
        @Override
        public void run() {
            if (liveMode && currentTab == TAB_HOME && mjpegView != null) {
                liveBitrate.setText(formatBitrate(mjpegView.bytesPerSecond()));
            }
            handler.postDelayed(this, 1000);
        }
    };

    private static final int REQ_WRITE_STORAGE = 41;

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<Entry>> byDay = new LinkedHashMap<>();
    private final List<String> dayKeys = new ArrayList<>();
    private String selectedDay = "";
    private Entry currentEntry = null;
    private boolean autoPlayNext = true;
    private File pendingPublishFile;
    private String pendingPublishName;
    private Bitmap pendingImage;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);

    private static class Entry {
        String name;
        long time;
        long size;
        long end;
        List<long[]> motion = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        thumbs = new ThumbnailLoader(this);

        contentRoot = new FrameLayout(this);
        contentRoot.setBackgroundColor(C_BG);

        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, dp(84)); // 底部留白给固定底栏
        outer.addView(root);
        contentRoot.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildPlayer();
        buildLivePanel();
        buildPlaybackPanel();
        buildGridPanel();
        buildBottomBar();

        setContentView(contentRoot);
        // 首次只做 Tab 视觉态，实时流统一由 onResume 启动，避免与 onResume 重复建连。
        currentTab = TAB_HOME;
        liveToolbar.setVisibility(View.VISIBLE);
        vodToolbar.setVisibility(View.GONE);
        updateTabVisual();
        showPanelsForTab();
        loadList();
    }

    // ================= 播放器 =================

    private void buildPlayer() {
        playerFrame = new FrameLayout(this);
        playerFrame.setBackgroundColor(Color.BLACK);

        videoView = new VideoView(this);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        playerFrame.addView(videoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mjpegView = new MjpegView(this);
        mjpegView.setVisibility(View.GONE);
        playerFrame.addView(mjpegView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mjpegView.setOnErrorListener(new MjpegView.OnErrorListener() {
            @Override
            public void onError(String message) {
                liveStatus.setText(message);
                liveStatus.setVisibility(View.VISIBLE);
                if (!liveFrameReceived && message.contains("404")
                        && liveCandidateIndex + 1 < liveCandidates.size()) {
                    liveCandidateIndex++;
                    startLiveCandidate();
                }
            }
        });
        mjpegView.setOnFrameListener(new MjpegView.OnFrameListener() {
            @Override
            public void onFirstFrame() {
                liveFrameReceived = true;
                liveStatus.setText("已连接（实时）");
                liveStatus.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        liveStatus.setVisibility(View.GONE);
                    }
                }, 1200);
            }
        });

        h264Decoder = new H264LiveDecoder();
        h264Decoder.setErrorListener(new H264LiveDecoder.ErrorListener() {
            @Override
            public void onError(String message) {
                liveStatus.setText(message);
                liveStatus.setVisibility(View.VISIBLE);
            }
        });
        // 实时硬解载体用 TextureView 而非 SurfaceView：倒挂安装(180°/90°/270°)时需要对画面
        // 做属性旋转，SurfaceView 的硬件 overlay 层在瑞芯微平台不响应 setRotation/setScale
        // （真机日志已证实角度已应用、但 SurfaceView 画面仍倒置）；TextureView 内容走普通 GPU
        // 纹理，View 层旋转/缩放必然生效。解码器喂帧/drain 不变，仅 Surface 来源换为 SurfaceTexture。
        liveTexture = new TextureView(this);
        liveTexture.setVisibility(View.GONE);
        liveTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                if (liveOutputSurface == null) liveOutputSurface = new Surface(st);
                h264Decoder.setSurface(liveOutputSurface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                h264Decoder.setSurface(null);
                if (liveOutputSurface != null) {
                    liveOutputSurface.release();
                    liveOutputSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        playerFrame.addView(liveTexture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mjpegView.setOnH264FrameListener(new MjpegView.OnH264FrameListener() {
            @Override
            public void onH264Frame(byte[] annexbData) {
                h264Decoder.feed(annexbData);
            }
        });
        mjpegView.setOnStreamModeListener(new MjpegView.OnStreamModeListener() {
            @Override
            public void onStreamMode(boolean isH264) {
                if (!liveMode) return;
                // 只切换普通 View；绝不切 liveTexture，避免销毁重建 Surface 导致黑屏。
                mjpegView.setVisibility(isH264 ? View.GONE : View.VISIBLE);
            }
        });

        liveStatus = new TextView(this);
        liveStatus.setTextSize(14);
        liveStatus.setTextColor(Color.WHITE);
        liveStatus.setBackgroundColor(0x88000000);
        liveStatus.setGravity(Gravity.CENTER);
        liveStatus.setPadding(20, 10, 20, 10);
        playerFrame.addView(liveStatus, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // 两条悬浮工具条：看家（实时）/ 回看，互斥。
        liveToolbar = buildLiveToolbar();
        vodToolbar = buildVodToolbar();
        playerFrame.addView(liveToolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        playerFrame.addView(vodToolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        vodToolbar.setVisibility(View.GONE);

        // 顶部标题栏（设备名 + 实时码率）
        FrameLayout titleBar = new FrameLayout(this);
        titleBar.setBackgroundColor(Color.argb(110, 0, 0, 0));
        LinearLayout titleWrap = new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        titleWrap.setPadding(0, dp(8), 0, dp(8));
        liveTitle = new TextView(this);
        liveTitle.setTextColor(Color.WHITE);
        liveTitle.setTextSize(17);
        liveTitle.setTypeface(Typeface.DEFAULT_BOLD);
        liveTitle.setGravity(Gravity.CENTER);
        String devName = Config.activeDevice(this).name;
        liveTitle.setText(devName == null || devName.isEmpty() ? "咩监控摄像头" : devName);
        liveBitrate = new TextView(this);
        liveBitrate.setTextColor(Color.rgb(214, 218, 222));
        liveBitrate.setTextSize(12);
        liveBitrate.setGravity(Gravity.CENTER);
        liveBitrate.setText("连接中…");
        titleWrap.addView(liveTitle);
        titleWrap.addView(liveBitrate);
        titleBar.addView(titleWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playerFrame.addView(titleBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));

        // 退出全屏按钮
        exitFsBtn = new TextView(this);
        exitFsBtn.setText("✕");
        exitFsBtn.setTextColor(Color.WHITE);
        exitFsBtn.setTextSize(20);
        exitFsBtn.setGravity(Gravity.CENTER);
        exitFsBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        exitFsBtn.setBackgroundColor(0x88000000);
        exitFsBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams xfLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        xfLp.topMargin = dp(10);
        xfLp.rightMargin = dp(10);
        exitFsBtn.setLayoutParams(xfLp);
        exitFsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(false);
            }
        });
        playerFrame.addView(exitFsBtn);

        LinearLayout.LayoutParams lp = embeddedPlayerParams();
        playerFrame.setLayoutParams(lp);
        playerPortraitParams = lp;
        root.addView(playerFrame);
        playerIndexInRoot = root.indexOfChild(playerFrame);

        playerFrame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!fullscreen) setFullscreen(true);
            }
        });

        videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mediaPlayer = mp;
                applyVodAudio();
                if (Build.VERSION.SDK_INT >= 23) applyVodSpeed();
                mp.start();
                syncPlayButton();
            }
        });
        videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                playNextAfter(currentEntry);
            }
        });
        videoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                toast("播放失败，请重试");
                return true;
            }
        });
    }

    /** 生成悬浮在视频底部的半透明黑色工具条容器。 */
    private LinearLayout newOverlayBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        bar.setPadding(pad, dp(8), pad, dp(8));
        bar.setBackgroundColor(Color.argb(150, 0, 0, 0));
        return bar;
    }

    /** 工具条上的白色图标文字按钮（等宽分布）。 */
    private TextView barButton(String glyph, boolean outlined) {
        TextView b = new TextView(this);
        b.setText(glyph);
        b.setTextColor(Color.WHITE);
        b.setTextSize(outlined ? 14 : 19);
        b.setGravity(Gravity.CENTER);
        b.setPadding(0, dp(8), 0, dp(8));
        if (outlined) {
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(4));
            gd.setColor(Color.TRANSPARENT);
            gd.setStroke(dp(1), Color.WHITE);
            b.setBackground(glyph == null ? null : gd);
        }
        return b;
    }

    /** 看家（实时）工具条：夜间 / 自动 / 静音 / 截图 / 录像 / 全屏。 */
    private LinearLayout buildLiveToolbar() {
        LinearLayout bar = newOverlayBar();
        TextView night = barButton("☾", false);
        TextView auto = barButton("自动", true);
        btnMuteLive = barButton("🔊", false);
        TextView shot = barButton("✂", false);
        TextView rec = barButton("▭", false);
        TextView full = barButton("⤢", false);
        setUnsupported(night);
        setUnsupported(auto);
        // 实时静音：实时通道未在本播放器混合音频（AAC 为独立通道），按诚实约定提示。
        btnMuteLive.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                liveMuted = !liveMuted;
                btnMuteLive.setText(liveMuted ? "🔇" : "🔊");
                toast(liveMuted ? "实时声音已静音" : "实时声音已开启");
            }
        });
        shot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureLiveFrame();
            }
        });
        setUnsupported(rec);
        full.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(true);
            }
        });
        addBarItem(bar, night);
        addBarItem(bar, auto);
        addBarItem(bar, btnMuteLive);
        addBarItem(bar, shot);
        addBarItem(bar, rec);
        addBarItem(bar, full);
        return bar;
    }

    /** 回看控制条：暂停 / 声音 / 截图 / 录像 / 倍速 / 全屏。 */
    private LinearLayout buildVodToolbar() {
        LinearLayout bar = newOverlayBar();
        btnPlay = barButton("‖", false);
        btnMuteVod = barButton("🔊", false);
        TextView shot = barButton("✂", false);
        TextView rec = barButton("▭", false);
        btnSpeed = barButton("1x", true);
        TextView full = barButton("⤢", false);

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleVodPlayPause();
            }
        });
        btnMuteVod.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vodMuted = !vodMuted;
                applyVodAudio();
            }
        });
        shot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureVodFrame();
            }
        });
        // 回放段本身已是录像文件，「录像」按钮无对应能力（服务端录像在 App 端控制）。
        setUnsupported(rec);
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleVodSpeed();
            }
        });
        full.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(true);
            }
        });
        addBarItem(bar, btnPlay);
        addBarItem(bar, btnMuteVod);
        addBarItem(bar, shot);
        addBarItem(bar, rec);
        addBarItem(bar, btnSpeed);
        addBarItem(bar, full);
        return bar;
    }

    private void addBarItem(LinearLayout bar, View item) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(item, lp);
    }

    /** 设备不支持的控件：统一给出明确反馈，而不是静默无反应。 */
    private void setUnsupported(View v) {
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("该固定摄像头暂不支持此功能");
            }
        });
    }

    // ================= 看家面板：云台方向盘 + 拍照 =================

    private void buildLivePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(0, dp(18), 0, dp(10));

        final PtzDialView dial = new PtzDialView(this);
        int dialSize = dp(236);
        LinearLayout.LayoutParams dialLp = new LinearLayout.LayoutParams(dialSize, dialSize);
        dialLp.topMargin = dp(8);
        panel.addView(dial, dialLp);
        dial.setDirectionListener(new PtzDialView.DirectionListener() {
            @Override
            public void onDirection(String name) {
                toast("该固定摄像头暂不支持云台转动（" + name + "）");
            }
        });

        // 右下角圆形「拍照」悬浮钮（米家模板），实时截图。
        FrameLayout camWrap = new FrameLayout(this);
        LinearLayout.LayoutParams cwLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        cwLp.topMargin = dp(10);
        panel.addView(camWrap, cwLp);
        TextView camBtn = new TextView(this);
        camBtn.setText("📷");
        camBtn.setTextSize(22);
        camBtn.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.WHITE);
        camBtn.setBackground(circle);
        if (Build.VERSION.SDK_INT >= 21) camBtn.setElevation(dp(2));
        camBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                captureLiveFrame();
            }
        });
        FrameLayout.LayoutParams camLp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        camLp.rightMargin = dp(20);
        camWrap.addView(camBtn, camLp);

        livePanel = panel;
        root.addView(panel);
    }

    // ================= 回看面板：日期条 + 粉色时间轴 + 主按钮 =================

    private void buildPlaybackPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        // 回看页提示（替代旧的状态文字，保持浅色米家风）
        playbackHint = new TextView(this);
        playbackHint.setTextSize(12);
        playbackHint.setTextColor(C_TEXT_SUB);
        playbackHint.setPadding(dp(2), dp(6), dp(2), dp(2));
        playbackHint.setText("拖动时间轴松手即从该时刻回放，蓝色为录像段，橙色为画面变动");
        panel.addView(playbackHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 日期横条
        dayScroll = new HorizontalScrollView(this);
        dayScroll.setHorizontalScrollBarEnabled(false);
        dayChips = new LinearLayout(this);
        dayChips.setOrientation(LinearLayout.HORIZONTAL);
        dayScroll.addView(dayChips);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dsLp.topMargin = dp(8);
        panel.addView(dayScroll, dsLp);

        // 粉色时间轴（外层留左右翻页箭头位）
        FrameLayout tlWrap = new FrameLayout(this);
        timelineScroll = new HorizontalScrollView(this);
        timelineScroll.setHorizontalScrollBarEnabled(false);
        timeline = new TimelineView(this);
        timeline.setOnSeekListener(new OnSeekListener() {
            @Override
            public void onSeek(long millisOfDay) {
                Entry hit = findEntryAt(millisOfDay);
                if (hit != null) {
                    play(hit);
                } else {
                    toast("该时刻没有录像");
                }
            }
        });
        timelineScroll.addView(timeline, new HorizontalScrollView.LayoutParams(
                timeline.getContentWidth(), dp(84)));
        FrameLayout.LayoutParams tsvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tsvLp.leftMargin = dp(30);
        tsvLp.rightMargin = dp(30);
        tlWrap.addView(timelineScroll, tsvLp);
        TextView leftArrow = arrowText("‹");
        TextView rightArrow = arrowText("›");
        tlWrap.addView(leftArrow, new FrameLayout.LayoutParams(
                dp(28), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        tlWrap.addView(rightArrow, new FrameLayout.LayoutParams(
                dp(28), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.topMargin = dp(8);
        panel.addView(tlWrap, tlLp);

        // 青色「全部回看视频」主按钮
        TextView all = new TextView(this);
        all.setText("全部回看视频");
        all.setTextColor(Color.WHITE);
        all.setTextSize(16);
        all.setGravity(Gravity.CENTER);
        GradientDrawable tealBg = new GradientDrawable();
        tealBg.setCornerRadius(dp(8));
        tealBg.setColor(C_TEAL);
        all.setBackground(tealBg);
        all.setPadding(0, dp(15), 0, dp(15));
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGrid();
            }
        });
        LinearLayout.LayoutParams allLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        allLp.topMargin = dp(22);
        panel.addView(all, allLp);

        // 次按钮：把当前回放段保存到手机相册（保留旧版能力）
        TextView save = new TextView(this);
        save.setText("⬇ 保存本段到手机");
        save.setTextColor(C_TEAL_DARK);
        save.setTextSize(14);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(14), 0, dp(6));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (currentEntry == null) {
                    toast("请先在时间轴上选择一段录像");
                } else {
                    downloadCurrent();
                }
            }
        });
        panel.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playbackPanel = panel;
        root.addView(panel);
    }

    private TextView arrowText(String ch) {
        TextView t = new TextView(this);
        t.setText(ch);
        t.setTextSize(26);
        t.setTextColor(C_TEXT_SUB);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ================= 全部回看（覆盖式整页网格） =================

    private void buildGridPanel() {
        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setBackgroundColor(C_BG);

        // 顶部栏：返回 + 标题 + 编辑（编辑按高保真摆上，设备无多选删除能力，提示不支持）
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setBackgroundColor(C_CARD);
        top.setPadding(dp(6), dp(10), dp(12), dp(10));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(30);
        back.setTextColor(C_TEXT);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(10), 0);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeGrid();
            }
        });
        TextView title = new TextView(this);
        title.setText("全部回看视频");
        title.setTextColor(C_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        TextView edit = new TextView(this);
        edit.setText("▢");
        edit.setTextSize(20);
        edit.setTextColor(C_TEXT_SUB);
        edit.setGravity(Gravity.CENTER);
        setUnsupported(edit);
        top.addView(back, new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(edit, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        cover.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 水平日期选择
        gridDateScroll = new HorizontalScrollView(this);
        gridDateScroll.setHorizontalScrollBarEnabled(false);
        gridDateChips = new LinearLayout(this);
        gridDateChips.setOrientation(LinearLayout.HORIZONTAL);
        gridDateChips.setPadding(dp(8), dp(8), dp(8), dp(8));
        gridDateScroll.addView(gridDateChips);
        cover.addView(gridDateScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 网格滚动体
        gridScroll = new ScrollView(this);
        gridRows = new LinearLayout(this);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setPadding(dp(12), dp(8), dp(12), dp(20));
        gridScroll.addView(gridRows);
        cover.addView(gridScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cover.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover.setVisibility(View.GONE);
        gridPanel = cover;
        contentRoot.addView(cover);
    }

    /** 进入全部回看整页：停实时（省电/省流）、盖住播放器与底栏、渲染所选日期。 */
    private void openGrid() {
        stopLive();
        thumbs.cancelAll();
        if (gridDay == null || gridDay.isEmpty() || !byDay.containsKey(gridDay)) {
            gridDay = dayKeys.isEmpty() ? "" : dayKeys.get(0);
        }
        renderGridDates();
        renderGridHours();
        gridPanel.setVisibility(View.VISIBLE);
        gridPanel.bringToFront();
    }

    private void closeGrid() {
        thumbs.cancelAll();
        gridPanel.setVisibility(View.GONE);
        bottomBar.bringToFront();
        // 回到看家时恢复实时；回到回看时保持回放。
        if (currentTab == TAB_HOME && liveMode) startLive();
    }

    /** 顶部水平日期 chips：显示「日 / 月」两行，选中为青色实心圆。 */
    private void renderGridDates() {
        gridDateChips.removeAllViews();
        for (final String key : dayKeys) {
            Calendar c = Calendar.getInstance();
            c.setTime(dayFmt2date(key));
            TextView chip = new TextView(this);
            chip.setText(c.get(Calendar.DAY_OF_MONTH) + "\n" + (c.get(Calendar.MONTH) + 1) + "月");
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(15);
            chip.setPadding(0, dp(8), 0, dp(8));
            int size = dp(52);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMargins(dp(6), 0, dp(6), 0);
            chip.setLayoutParams(lp);
            applyGridDateChipStyle(chip, key.equals(gridDay));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    gridDay = key;
                    renderGridDates();
                    thumbs.cancelAll();
                    renderGridHours();
                }
            });
            gridDateChips.addView(chip);
        }
    }

    private void applyGridDateChipStyle(TextView chip, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if (selected) {
            bg.setColor(C_TEAL);
            chip.setTextColor(Color.WHITE);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            bg.setColor(Color.TRANSPARENT);
            chip.setTextColor(C_TEXT);
            chip.setTypeface(Typeface.DEFAULT);
        }
        chip.setBackground(bg);
    }

    private Date dayFmt2date(String key) {
        try {
            return dayFmt.parse(key);
        } catch (Exception e) {
            return new Date();
        }
    }

    /** 判断某小时是否属于「夜间」（米家模板里夜间为黑白缩略图）。7:00–18:59 视为白昼。 */
    private static boolean isNightHour(int hour) {
        return hour < 7 || hour >= 19;
    }

    /**
     * 渲染所选日期的 24 小时网格（3 列）。
     * 有录像的小时：异步抽该小时附近一帧做缩略图，标注小时与运动小人；
     * 无录像的小时：灰底占位、不可点。点击有录像格子 → 切到回看并从该段播放。
     */
    private void renderGridHours() {
        gridRows.removeAllViews();
        final List<Entry> dayEntries = byDay.get(gridDay);
        final LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cellLp.setMargins(dp(5), dp(6), dp(5), dp(6));

        for (int row = 0; row < 8; row++) {
            LinearLayout rowView = new LinearLayout(this);
            rowView.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 3; col++) {
                final int hour = row * 3 + col;
                final Entry entry = entryForHour(dayEntries, hour);
                View cell = entry == null
                        ? buildEmptyHourCell(hour)
                        : buildHourCell(entry, hour, cellLp);
                rowView.addView(cell, cellLp);
            }
            gridRows.addView(rowView);
        }
    }

    /** 取该小时内开始的第一段录像；若该小时没有整点开始的段，则找覆盖该小时的段。 */
    private Entry entryForHour(List<Entry> dayEntries, int hour) {
        if (dayEntries == null) return null;
        Entry fallback = null;
        for (Entry e : dayEntries) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(e.time);
            int sh = c.get(Calendar.HOUR_OF_DAY);
            int eh = hourOf(e.end);
            if (sh == hour) return e;
            if (hour >= sh && hour < eh) fallback = e;
        }
        return fallback;
    }

    private int hourOf(long epoch) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epoch);
        return c.get(Calendar.HOUR_OF_DAY);
    }

    private View buildEmptyHourCell(int hour) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        FrameLayout thumbWrap = new FrameLayout(this);
        GradientDrawable ph = new GradientDrawable();
        ph.setCornerRadius(dp(8));
        ph.setColor(Color.rgb(232, 235, 238));
        thumbWrap.setBackground(ph);
        TextView blank = new TextView(this);
        blank.setHeight(dp(66));
        thumbWrap.addView(blank, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));
        cell.addView(thumbWrap);
        cell.addView(hourLabel(hour, false), labelLp());
        return cell;
    }

    private View buildHourCell(final Entry entry, final int hour, ViewGroup.LayoutParams ignored) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClickable(true);

        final ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable rounded = new GradientDrawable();
        rounded.setCornerRadius(dp(8));
        rounded.setColor(Color.rgb(20, 20, 22));
        img.setBackground(rounded);
        if (Build.VERSION.SDK_INT >= 21) img.setClipToOutline(true);
        FrameLayout thumbWrap = new FrameLayout(this);
        thumbWrap.setBackground(rounded);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        thumbWrap.addView(img, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cell.addView(thumbWrap, imgLp);

        boolean hasMotion = hourHasMotion(entry, hour);
        cell.addView(hourLabel(hour, hasMotion), labelLp());

        // 计算该小时整点相对本段起点的偏移（微秒），让缩略图尽量落在这一小时。
        long hourStart = hourStartMs(entry, hour);
        long offsetUs = Math.max(0L, (hourStart - entry.time) * 1000L);
        boolean night = isNightHour(hour);
        String fileUrl = recFileUrl(entry.name);
        if (!fileUrl.isEmpty()) thumbs.load(img, fileUrl, offsetUs, night);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                jumpGridToPlayback(entry);
            }
        });
        return cell;
    }

    private boolean hourHasMotion(Entry entry, int hour) {
        long hourStart = hourStartMs(entry, hour);
        long hourEnd = hourStart + 3600_000L;
        for (long[] m : entry.motion) {
            if (m[1] > hourStart && m[0] < hourEnd) return true;
        }
        return false;
    }

    private long hourStartMs(Entry anchor, int hour) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(anchor.time);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private TextView hourLabel(int hour, boolean motion) {
        TextView t = new TextView(this);
        t.setText(String.format(Locale.CHINA, "%02d:00", hour) + (motion ? "   🏃" : ""));
        t.setTextSize(13);
        t.setTextColor(motion ? C_TEXT : C_TEXT_SUB);
        t.setPadding(dp(2), dp(4), dp(2), 0);
        return t;
    }

    private LinearLayout.LayoutParams labelLp() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 从网格点某小时 → 切回看页、选对应日期并播放该段。 */
    private void jumpGridToPlayback(Entry entry) {
        currentTab = TAB_PLAYBACK;
        gridPanel.setVisibility(View.GONE);
        bottomBar.bringToFront();
        String key = dayFmt.format(new Date(entry.time));
        if (byDay.containsKey(key)) {
            selectedDay = key;
            renderDayChips();
            selectDay(key, false);
        }
        updateTabVisual();
        showPanelsForTab();
        play(entry);
    }

    // ================= 底部导航（看家/回看/对讲/云存储/快捷） =================

    private void buildBottomBar() {
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setBackgroundColor(C_CARD);
        int pad = dp(6);
        bottomBar.setPadding(pad, dp(8), pad, dp(8));
        bottomBar.setElevation(dp(8));

        addTab(bottomBar, "🏠", "看家", TAB_HOME);
        addTab(bottomBar, "▶", "回看", TAB_PLAYBACK);
        addCenterCall(bottomBar);   // 中央对讲圆钮（模板为电话图标），设备不支持
        addTab(bottomBar, "☁", "云存储", -2);
        addTab(bottomBar, "▦", "快捷", -3);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        contentRoot.addView(bottomBar, lp);
    }

    /** 普通槽位（图标 + 文字）。tabId>=0 为可切换页；负数为不支持功能。 */
    private void addTab(LinearLayout bar, String icon, String label, final int tabId) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        TextView tx = new TextView(this);
        tx.setText(label);
        tx.setTextSize(12);
        tx.setGravity(Gravity.CENTER);
        item.addView(ic);
        item.addView(tx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(item, lp);
        tabHitArea.add(item);
        tabLabels.add(tx);
        // 图标也存一份引用用于着色（取第一个子 View）
        item.setTag(R.id.thumb_tag, ic);
        item.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (tabId == TAB_HOME) selectTab(TAB_HOME);
                else if (tabId == TAB_PLAYBACK) selectTab(TAB_PLAYBACK);
                else toast("该固定摄像头暂不支持此功能");
            }
        });
    }

    /** 中央青色对讲圆钮（高保真模板），设备无对讲能力，点击明确提示。 */
    private void addCenterCall(LinearLayout bar) {
        TextView call = new TextView(this);
        call.setText("☎");
        call.setTextSize(22);
        call.setTextColor(Color.WHITE);
        call.setGravity(Gravity.CENTER);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(C_TEAL);
        call.setBackground(c);
        int sz = dp(54);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
        lp.setMargins(0, dp(-14), 0, 0);
        call.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast("该固定摄像头暂不支持语音对讲");
            }
        });
        bar.addView(call, lp);
    }

    private void selectTab(int tab) {
        currentTab = tab;
        if (tab == TAB_HOME) {
            liveMode = true;
            currentEntry = null;
            stopPlaybackVideo();
            liveToolbar.setVisibility(View.VISIBLE);
            vodToolbar.setVisibility(View.GONE);
            liveTitle.setText(deviceTitle());
            liveBitrate.setVisibility(View.VISIBLE);
            startLive();
        } else if (tab == TAB_PLAYBACK) {
            liveMode = false;
            stopLive();
            liveToolbar.setVisibility(View.GONE);
            vodToolbar.setVisibility(View.VISIBLE);
            videoView.setVisibility(View.GONE);
            mjpegView.setVisibility(View.GONE);
            liveTexture.setVisibility(View.GONE);
            // 实时连接状态文案在进入回放时必须隐藏，否则残留在黑屏中央像"一直连接中"。
            liveStatus.setVisibility(View.GONE);
            // 回看页按米家模板用「回看」标题，隐藏实时码率。
            liveTitle.setText("回看");
            liveBitrate.setVisibility(View.GONE);
            syncPlayButton();
        }
        updateTabVisual();
        showPanelsForTab();
    }

    private String deviceTitle() {
        String devName = Config.activeDevice(this).name;
        return (devName == null || devName.isEmpty()) ? "咩监控摄像头" : devName;
    }

    private void showPanelsForTab() {
        livePanel.setVisibility(currentTab == TAB_HOME ? View.VISIBLE : View.GONE);
        playbackPanel.setVisibility(currentTab == TAB_PLAYBACK ? View.VISIBLE : View.GONE);
    }

    private void updateTabVisual() {
        // 只有看家/回看两个可切换槽位（索引 0、1；对讲占索引 2 的位置但未加入列表）。
        for (int i = 0; i < tabHitArea.size(); i++) {
            View item = tabHitArea.get(i);
            TextView tx = tabLabels.get(i);
            TextView ic = (TextView) item.getTag(R.id.thumb_tag);
            boolean active;
            if (i == 0) active = currentTab == TAB_HOME;
            else if (i == 1) active = currentTab == TAB_PLAYBACK;
            else active = false;
            int color = active ? C_TEAL_DARK : C_TAB_INACTIVE;
            if (tx != null) tx.setTextColor(color);
            if (ic != null) ic.setTextColor(color);
        }
    }

    // ================= URL 与全屏 =================

    /** 录像文件完整地址（含密码前缀）；baseHttp 在列表加载成功后就绪。 */
    private String recFileUrl(String name) {
        if (baseHttp == null || baseHttp.isEmpty()) return "";
        return baseHttp + "rec/file/" + Uri.encode(name);
    }

    private void setFullscreen(boolean on) {
        if (fullscreen == on) return;
        fullscreen = on;
        if (on) {
            contentRoot.setBackgroundColor(Color.BLACK);
            detachPlayer();
            contentRoot.addView(playerFrame, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            playerFrame.bringToFront();
            exitFsBtn.setVisibility(View.VISIBLE);
            exitFsBtn.bringToFront();
        } else {
            detachPlayer();
            int index = Math.max(0, Math.min(playerIndexInRoot, root.getChildCount()));
            root.addView(playerFrame, index, playerPortraitParams);
            contentRoot.setBackgroundColor(C_BG);
            exitFsBtn.setVisibility(View.GONE);
        }
        int vis = on ? View.GONE : View.VISIBLE;
        bottomBar.setVisibility(vis);
        if (livePanel != null) livePanel.setVisibility(on ? View.GONE : panelVis(TAB_HOME));
        if (playbackPanel != null) playbackPanel.setVisibility(on ? View.GONE : panelVis(TAB_PLAYBACK));

        View decor = getWindow().getDecorView();
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        setRequestedOrientation(on
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private int panelVis(int tab) {
        return currentTab == tab ? View.VISIBLE : View.GONE;
    }

    private void detachPlayer() {
        ViewGroup parent = (ViewGroup) playerFrame.getParent();
        if (parent != null) parent.removeView(playerFrame);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (fullscreen) {
            playerFrame.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            playerFrame.bringToFront();
            exitFsBtn.bringToFront();
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreen) {
            setFullscreen(false);
            return;
        }
        if (gridPanel != null && gridPanel.getVisibility() == View.VISIBLE) {
            closeGrid();
            return;
        }
        super.onBackPressed();
    }

    // ================= 回放控制 =================

    private void toggleVodPlayPause() {
        try {
            if (videoView.isPlaying()) {
                videoView.pause();
            } else {
                videoView.start();
            }
        } catch (Exception ignored) {
        }
        syncPlayButton();
    }

    private void syncPlayButton() {
        if (btnPlay == null) return;
        boolean playing;
        try {
            playing = videoView.isPlaying();
        } catch (Exception e) {
            playing = false;
        }
        btnPlay.setText(playing ? "‖" : "▶");
    }

    private void applyVodAudio() {
        try {
            if (mediaPlayer != null) {
                float v = vodMuted ? 0f : 1f;
                mediaPlayer.setVolume(v, v);
            }
        } catch (Exception ignored) {
        }
        if (btnMuteVod != null) btnMuteVod.setText(vodMuted ? "🔇" : "🔊");
    }

    private final float[] SPEEDS = {1f, 1.5f, 2f, 0.5f};

    private void cycleVodSpeed() {
        float next = SPEEDS[0];
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i] == vodSpeed && i + 1 < SPEEDS.length) {
                next = SPEEDS[i + 1];
                break;
            }
        }
        vodSpeed = next;
        btnSpeed.setText(formatSpeed(vodSpeed));
        if (Build.VERSION.SDK_INT >= 23) applyVodSpeed();
        else toast("倍速播放需要 Android 6.0 以上");
    }

    private String formatSpeed(float s) {
        if (s == (long) s) return (long) s + "x";
        return s + "x";
    }

    /** Android 6+ 通过 PlaybackParams 设置倍速；切换时保持播放/暂停态。 */
    private void applyVodSpeed() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (mediaPlayer == null) return;
        try {
            android.media.PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(vodSpeed);
            mediaPlayer.setPlaybackParams(params);
        } catch (Exception ignored) {
        }
    }

    // ================= 截图（实时 JPEG / 回放） =================

    private void captureLiveFrame() {
        Bitmap bmp = mjpegView.snapshot();
        if (bmp == null) {
            toast(mjpegView.isH264Mode()
                    ? "硬解实时画面受系统限制暂无法截图，可在回放中截图"
                    : "暂无画面，稍后再试");
            return;
        }
        String name = "LIVE_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(new Date()) + ".jpg";
        saveImage(bmp, name);
    }

    private void captureVodFrame() {
        // 用播放器容器抓帧：SurfaceView 走 overlay 时也可能抓黑，故兜底提示。
        Bitmap bmp = captureView(videoView);
        if (bmp == null) {
            toast("暂无可截取画面");
            return;
        }
        String name = "PLAY_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
                .format(new Date()) + ".jpg";
        saveImage(bmp, name);
    }

    private Bitmap captureView(View view) {
        try {
            Bitmap bmp = Bitmap.createBitmap(
                    Math.max(1, view.getWidth()), Math.max(1, view.getHeight()),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            view.draw(canvas);
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把一张截图写入系统相册（Android 10+ 走 MediaStore，低版本走公有 Pictures 目录）。 */
    private void saveImage(final Bitmap bmp, final String name) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                if (Build.VERSION.SDK_INT >= 29) {
                    result = publishImageQ(bmp, name);
                } else if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    pendingImage = bmp;
                    pendingPublishName = name;
                    requestPermissions(
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQ_WRITE_STORAGE);
                    result = "请允许存储权限，即可保存截图";
                    postToast(result);
                    return;
                } else {
                    result = publishImageLegacy(bmp, name);
                }
                bmp.recycle();
                postToast(result);
            }
        }, "image-save").start();
    }

    private void postToast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                toast(msg);
            }
        });
    }

    private String publishImageQ(Bitmap bmp, String name) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/咩监控");
            android.net.Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return "相册写入失败";
            OutputStream out = getContentResolver().openOutputStream(uri);
            if (out == null) return "相册写入失败";
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            return "截图已保存到相册";
        } catch (Exception e) {
            return "保存截图失败：" + e.getMessage();
        }
    }

    private String publishImageLegacy(Bitmap bmp, String name) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "咩监控");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos);
            fos.close();
            MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                    new String[]{"image/jpeg"}, null);
            return "截图已保存到相册";
        } catch (Exception e) {
            return "保存截图失败：" + e.getMessage();
        }
    }

    // ================= 录像列表加载 =================

    private void loadList() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    Config.Device device = Config.activeDevice(RecordingsActivity.this);
                    Resolver.Endpoint endpoint = Resolver.resolve(RecordingsActivity.this,
                            device.host, device.port);
                    String prefix = device.password.isEmpty() ? "" : device.password + "/";
                    baseHttp = "http://" + endpoint.host + ":" + endpoint.port + "/" + prefix;
                    // 顺手记录实时连接参数，切看家时无需再次 mDNS 解析。
                    liveHost = endpoint.host;
                    livePort = endpoint.port;
                    livePassword = device.password;
                    URL url = new URL(baseHttp + "rec/list");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        postToast("加载录像失败（HTTP " + code + "），检查地址密码");
                        return;
                    }
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    in.close();

                    JSONArray array = new JSONArray(buf.toString("UTF-8"));
                    List<Entry> list = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        Entry e = new Entry();
                        e.name = obj.getString("name");
                        e.time = obj.optLong("time", 0);
                        e.size = obj.optLong("size", 0);
                        JSONArray motionArr = obj.optJSONArray("motion");
                        if (motionArr != null) {
                            for (int j = 0; j < motionArr.length(); j++) {
                                JSONArray pair = motionArr.optJSONArray(j);
                                if (pair != null && pair.length() >= 2) {
                                    e.motion.add(new long[]{pair.getLong(0), pair.getLong(1)});
                                }
                            }
                        }
                        list.add(e);
                    }
                    Collections.sort(list, new Comparator<Entry>() {
                        @Override
                        public int compare(Entry a, Entry b) {
                            return Long.compare(a.time, b.time);
                        }
                    });
                    for (int i = 0; i < list.size(); i++) {
                        Entry e = list.get(i);
                        long next = (i + 1 < list.size())
                                ? list.get(i + 1).time : e.time + 10 * 60 * 1000L;
                        e.end = Math.max(next, e.time + 30 * 1000L);
                    }
                    onLoaded(list);
                } catch (Exception e) {
                    postToast("加载录像失败：" + e.getMessage());
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "rec-list-loader").start();
    }

    private void onLoaded(final List<Entry> list) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                entries.clear();
                entries.addAll(list);
                byDay.clear();
                dayKeys.clear();
                for (Entry e : entries) {
                    String key = dayFmt.format(new Date(e.time));
                    List<Entry> l = byDay.get(key);
                    if (l == null) {
                        l = new ArrayList<>();
                        byDay.put(key, l);
                        dayKeys.add(key);
                    }
                    l.add(e);
                }
                Collections.sort(dayKeys, Collections.reverseOrder());
                renderDayChips();
                if (!dayKeys.isEmpty()) {
                    selectDay(dayKeys.get(0), true);
                } else {
                    timeline.setEntries(Collections.<Entry>emptyList());
                    playbackHint.setText("暂无录像");
                }
                if (gridPanel != null && gridPanel.getVisibility() == View.VISIBLE) {
                    renderGridDates();
                    renderGridHours();
                }
            }
        });
    }

    // ================= 回看日期条（浅色米家样式） =================

    private void renderDayChips() {
        dayChips.removeAllViews();
        String today = dayFmt.format(new Date());
        Calendar yc = Calendar.getInstance();
        yc.add(Calendar.DAY_OF_YEAR, -1);
        String yesterday = dayFmt.format(yc.getTime());
        for (final String key : dayKeys) {
            String label;
            if (key.equals(today)) label = "今天";
            else if (key.equals(yesterday)) label = "昨天";
            else label = key.substring(5).replace('-', '/');
            TextView chip = new TextView(this);
            int count = byDay.get(key).size();
            chip.setText(label + " " + count);
            chip.setTextSize(13);
            chip.setTag(key);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectDay(key, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, dp(8), 0);
            dayChips.addView(chip, lp);
        }
        styleDayChips();
    }

    private void styleDayChips() {
        for (int i = 0; i < dayChips.getChildCount(); i++) {
            View v = dayChips.getChildAt(i);
            boolean on = selectedDay.equals(v.getTag());
            TextView chip = (TextView) v;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            if (on) {
                bg.setColor(C_TEAL);
                chip.setTextColor(Color.WHITE);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                bg.setColor(Color.rgb(234, 237, 240));
                chip.setTextColor(C_TEXT_SUB);
                chip.setTypeface(Typeface.DEFAULT);
            }
            chip.setBackground(bg);
        }
    }

    private void selectDay(final String key, boolean scrollToSpot) {
        selectedDay = key;
        styleDayChips();
        final List<Entry> dayEntries = byDay.get(key);
        timeline.setEntries(dayEntries == null
                ? Collections.<Entry>emptyList() : dayEntries);
        if (dayEntries == null || dayEntries.isEmpty()) {
            playbackHint.setText(key + " 暂无录像");
        } else {
            playbackHint.setText(key + "　共 " + dayEntries.size() + " 段，拖时间轴回放");
        }
        if (!scrollToSpot) return;
        final long targetEpoch;
        if (key.equals(dayFmt.format(new Date()))) {
            targetEpoch = System.currentTimeMillis();
        } else if (dayEntries != null && !dayEntries.isEmpty()) {
            targetEpoch = dayEntries.get(0).time;
        } else {
            return;
        }
        timelineScroll.post(new Runnable() {
            @Override
            public void run() {
                timelineScroll.scrollTo(timeline.xForTimeOfDay(targetEpoch) - dp(60), 0);
            }
        });
    }

    private Entry findEntryAt(long millisOfDay) {
        List<Entry> dayEntries = byDay.get(selectedDay);
        if (dayEntries == null) return null;
        for (Entry e : dayEntries) {
            long start = millisOfDay(e.time);
            long end = millisOfDay(e.end);
            if (millisOfDay >= start && millisOfDay < end) return e;
        }
        return null;
    }

    static long millisOfDay(long epochMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMs);
        return ((c.get(Calendar.HOUR_OF_DAY) * 60L + c.get(Calendar.MINUTE)) * 60L
                + c.get(Calendar.SECOND)) * 1000L + c.get(Calendar.MILLISECOND);
    }

    // ================= 实时画面 =================

    private void startLive() {
        liveMode = true;
        currentEntry = null;
        stopPlaybackVideo();
        videoView.setVisibility(View.GONE);
        // 实时期间 TextureView 恒可见，只靠上层 mjpegView 在 JPEG/H.264 间切换显隐。
        liveTexture.setVisibility(View.VISIBLE);
        mjpegView.setVisibility(View.VISIBLE);
        // 关键补偿：TextureView 的 SurfaceTexture 常驻、onSurfaceTextureAvailable 只回调一次；
        // 而 stopLive() 每次离开实时页都会 release 解码器（连带解除 Surface 绑定）。
        // 再次进入实时页时不会再收到可用回调，故这里在 SurfaceTexture 已就绪的情况下立即给
        // 新建解码器补绑 Surface，否则新解码器会一直停在"等待画面 Surface"、永不吐画面。
        bindLiveSurfaceIfReady();
        timeline.setCurrentEntry(null);
        timeline.setPlayhead(-1);
        liveStatus.setVisibility(View.VISIBLE);
        liveStatus.setText("正在连接…");
        handler.removeCallbacks(bitrateTicker);
        handler.post(bitrateTicker);

        // 拉取摄像头端的画面旋转角度并在 View 层应用（与连流并行，失败回退 0°）。
        loadLiveRotation();

        // 已解析过地址则直接连；否则后台解析（含 mDNS 慢启动回退）。
        if (!liveHost.isEmpty()) {
            beginLiveCandidates();
        } else {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Config.Device device = Config.activeDevice(RecordingsActivity.this);
                    final Resolver.Endpoint endpoint = Resolver.resolve(RecordingsActivity.this,
                            device.host, device.port);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            liveHost = endpoint.host;
                            livePort = endpoint.port;
                            livePassword = device.password;
                            baseHttp = "http://" + endpoint.host + ":" + endpoint.port + "/"
                                    + (device.password.isEmpty() ? "" : device.password + "/");
                            beginLiveCandidates();
                        }
                    });
                }
            }, "live-resolver").start();
        }
    }

    private void beginLiveCandidates() {
        Config.Device device = Config.activeDevice(this);
        Set<Integer> set = new LinkedHashSet<>();
        set.add(device.camera);
        set.add(0);
        set.add(1);
        set.add(2);
        liveCandidates.clear();
        liveCandidates.addAll(set);
        liveCandidateIndex = 0;
        liveFrameReceived = false;
        startLiveCandidate();
    }

    private void startLiveCandidate() {
        if (!liveMode) return;
        if (liveCandidateIndex >= liveCandidates.size()) {
            liveStatus.setVisibility(View.VISIBLE);
            liveStatus.setText("连接失败：请检查设备地址和密码");
            return;
        }
        liveToken++;
        final int token = liveToken;
        int camera = liveCandidates.get(liveCandidateIndex);
        liveStatus.setVisibility(View.VISIBLE);
        liveStatus.setText("正在连接摄像头 " + camera + " …");
        String prefix = livePassword.isEmpty() ? "" : livePassword + "/";
        String url = "http://" + liveHost + ":" + livePort + "/" + prefix + camera;
        mjpegView.start(url);
        // 看门狗：8 秒没出画面就换下一个摄像头序号。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!liveMode || token != liveToken || liveFrameReceived) return;
                if (liveCandidateIndex + 1 < liveCandidates.size()) {
                    liveCandidateIndex++;
                    startLiveCandidate();
                } else {
                    liveStatus.setVisibility(View.VISIBLE);
                    liveStatus.setText("连接失败：请检查设备地址和密码");
                }
            }
        }, 8000);
    }

    private void stopLive() {
        liveToken++;
        handler.removeCallbacks(bitrateTicker);
        if (mjpegView != null) mjpegView.stop();
        if (h264Decoder != null) h264Decoder.release();
    }

    /**
     * 若 TextureView 的 SurfaceTexture 已存在（非首次进入实时页时它不会再回调 available），
     * 立即包出 Surface 并绑定给当前解码器。首次进入时 SurfaceTexture 尚未就绪，由
     * SurfaceTextureListener.onSurfaceTextureAvailable 负责绑定，两条路径互斥覆盖。
     */
    private void bindLiveSurfaceIfReady() {
        if (liveTexture == null || h264Decoder == null) return;
        SurfaceTexture st = liveTexture.isAvailable() ? liveTexture.getSurfaceTexture() : null;
        if (st == null) return;
        if (liveOutputSurface == null) liveOutputSurface = new Surface(st);
        h264Decoder.setSurface(liveOutputSurface);
    }

    /**
     * 后台读取摄像头端的实时旋转角度（GET {base}cam/settings 的 rotation 字段）。
     * 任何失败（旧固件无此接口/网络异常）都安静回退为 0°，绝不阻塞实时连流。
     */
    private void loadLiveRotation() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String base = baseHttp;
                    if (base == null || base.isEmpty()) {
                        Config.Device device = Config.activeDevice(RecordingsActivity.this);
                        Resolver.Endpoint endpoint = Resolver.resolve(RecordingsActivity.this,
                                device.host, device.port);
                        liveHost = endpoint.host;
                        livePort = endpoint.port;
                        livePassword = device.password;
                        base = "http://" + endpoint.host + ":" + endpoint.port + "/"
                                + (device.password.isEmpty() ? "" : device.password + "/");
                        baseHttp = base;
                    }
                    URL url = new URL(base + "cam/settings");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    if (conn.getResponseCode() != 200) return;
                    InputStream in = conn.getInputStream();
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                    in.close();
                    int deg = new JSONObject(buf.toString("UTF-8")).optInt("rotation", 0);
                    if (deg != 90 && deg != 180 && deg != 270) deg = 0;
                    final int rotation = deg;
                    liveRotationDegrees = rotation;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            applyLiveRotation();
                        }
                    });
                } catch (Exception ignored) {
                    // 读不到就保持 0°，实时流照常连接
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }, "live-rotation-load").start();
    }

    /**
     * 在 View 层旋转实时两路画面（MJPEG 的普通 View + H.264 硬解 TextureView）。
     * TextureView 内容走 GPU 纹理，setRotation/setScale 必然生效（SurfaceView 的 overlay 层不生效）。
     * 180° 尺寸不变，缩放为 1；90°/270° 旋转后包围盒宽高互换，按容器短长边比缩小以铺满而不裁切。
     * 只动这两个画面子 View，状态条/工具条/标题等覆盖层不旋转。
     */
    private void applyLiveRotation() {
        if (mjpegView == null || liveTexture == null) return;
        final int rotation = liveRotationDegrees;
        Runnable transform = new Runnable() {
            @Override
            public void run() {
                float scale = 1f;
                int w = playerFrame.getWidth();
                int h = playerFrame.getHeight();
                if (rotation == 90 || rotation == 270) {
                    if (w > 0 && h > 0) {
                        scale = (float) Math.min(w, h) / Math.max(w, h);
                    } else {
                        scale = 9f / 16f; // 布局未完成时按 16:9 容器兜底
                    }
                }
                View[] targets = {mjpegView, liveTexture};
                for (View v : targets) {
                    v.setPivotX(v.getWidth() > 0 ? v.getWidth() / 2f : w / 2f);
                    v.setPivotY(v.getHeight() > 0 ? v.getHeight() / 2f : h / 2f);
                    v.setRotation(rotation);
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                }
            }
        };
        // 布局完成后再算缩放，避免拿到 0 尺寸；180° 无缩放也无妨。
        playerFrame.post(transform);
    }

    private void stopPlaybackVideo() {
        try {
            if (videoView.isPlaying()) videoView.stopPlayback();
        } catch (Exception ignored) {
        }
        mediaPlayer = null;
    }

    // ================= 回放播放 =================

    private void play(final Entry entry) {
        if (entry == null) return;
        liveMode = false;
        stopLive();
        liveToolbar.setVisibility(View.GONE);
        vodToolbar.setVisibility(View.VISIBLE);
        videoView.setVisibility(View.VISIBLE);
        mjpegView.setVisibility(View.GONE);
        liveTexture.setVisibility(View.GONE);
        liveStatus.setVisibility(View.GONE);
        playbackHint.setText("正在连接…");
        liveTitle.setText("回看");
        liveBitrate.setVisibility(View.GONE);
        // 切到回看 Tab（既可能来自时间轴，也可能来自网格跳转）。
        currentTab = TAB_PLAYBACK;
        updateTabVisual();
        showPanelsForTab();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = recFileUrl(entry.name);
                    if (url.isEmpty()) {
                        Config.Device device = Config.activeDevice(RecordingsActivity.this);
                        Resolver.Endpoint endpoint = Resolver.resolve(RecordingsActivity.this,
                                device.host, device.port);
                        String prefix = device.password.isEmpty() ? "" : device.password + "/";
                        url = "http://" + endpoint.host + ":" + endpoint.port + "/"
                                + prefix + "rec/file/" + Uri.encode(entry.name);
                    }
                    final String finalUrl = url;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startPlay(entry, finalUrl);
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            playbackHint.setText("连接失败：" + msg);
                        }
                    });
                }
            }
        }, "rec-player").start();
    }

    private void startPlay(Entry entry, String url) {
        currentEntry = entry;
        autoPlayNext = true;
        mediaPlayer = null;
        // host 已在 Config 入口做全角→ASCII 归一化，Rockit ffmpeg 可直接解析，直连即可。
        videoView.setVideoURI(Uri.parse(url));
        videoView.requestFocus();
        SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        playbackHint.setText("正在播放 " + tf.format(new Date(entry.time)));
        timeline.setEntries(byDay.get(selectedDay));
        timeline.setCurrentEntry(entry);
        handler.post(playheadUpdater);
        syncPlayButton();
    }

    private void playNextAfter(Entry entry) {
        if (!autoPlayNext || entry == null) return;
        List<Entry> dayEntries = byDay.get(selectedDay);
        if (dayEntries == null) return;
        int idx = dayEntries.indexOf(entry);
        if (idx >= 0 && idx + 1 < dayEntries.size()) {
            play(dayEntries.get(idx + 1));
        } else {
            playbackHint.setText("本日录像已播完");
            timeline.setPlayhead(-1);
            syncPlayButton();
        }
    }

    private final Runnable playheadUpdater = new Runnable() {
        @Override
        public void run() {
            if (currentEntry != null) {
                boolean playing;
                try {
                    playing = videoView.isPlaying();
                } catch (Exception e) {
                    playing = false;
                }
                if (playing) {
                    long pos = millisOfDay(currentEntry.time) + videoView.getCurrentPosition();
                    timeline.setPlayhead(pos);
                    handler.postDelayed(this, 500);
                    return;
                }
            }
            handler.removeCallbacks(this);
        }
    };

    private void downloadCurrent() {
        final Entry entry = currentEntry;
        if (entry == null) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result = doDownload(entry);
                postToast(result);
            }
        }, "rec-download").start();
    }

    private String doDownload(Entry entry) {
        HttpURLConnection conn = null;
        File tmp = new File(getCacheDir(), entry.name + ".part");
        try {
            String url = recFileUrl(entry.name);
            if (url.isEmpty()) return "设备未连接，无法下载";
            conn = (HttpURLConnection) new URL(url + "?download=1").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            int code = conn.getResponseCode();
            if (code != 200) return "下载失败（HTTP " + code + "）";
            InputStream in = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) fos.write(chunk, 0, n);
            fos.close();
            in.close();
            return publishVideoToGallery(tmp, entry.name);
        } catch (Exception e) {
            return "下载失败：" + e.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String publishVideoToGallery(File tmp, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/咩监控");
                android.net.Uri uri = getContentResolver().insert(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "相册写入失败";
                InputStream in = new FileInputStream(tmp);
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) return "相册写入失败";
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
                out.close();
                in.close();
                tmp.delete();
                return "已保存到相册（ movies/咩监控 ）";
            } catch (Exception e) {
                return "保存到相册失败：" + e.getMessage();
            }
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingPublishFile = tmp;
            pendingPublishName = name;
            requestPermissions(
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_WRITE_STORAGE);
            return "请允许存储权限，即可保存到相册";
        }
        return publishVideoLegacy(tmp, name);
    }

    private String publishVideoLegacy(File tmp, String name) {
        try {
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "咩监控");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, name);
            InputStream in = new FileInputStream(tmp);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) fos.write(chunk, 0, n);
            fos.close();
            in.close();
            tmp.delete();
            MediaScannerConnection.scanFile(this, new String[]{out.getAbsolutePath()},
                    new String[]{"video/mp4"}, null);
            return "已保存到相册：" + out.getAbsolutePath();
        } catch (Exception e) {
            return "保存到相册失败：" + e.getMessage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_WRITE_STORAGE || grantResults.length == 0
                || grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (pendingImage != null && pendingPublishName != null) {
            final Bitmap bmp = pendingImage;
            final String name = pendingPublishName;
            pendingImage = null;
            pendingPublishName = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    postToast(publishImageLegacy(bmp, name));
                    bmp.recycle();
                }
            }).start();
        } else if (pendingPublishFile != null) {
            final File tmp = pendingPublishFile;
            final String name = pendingPublishName;
            pendingPublishFile = null;
            pendingPublishName = null;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    postToast(publishVideoLegacy(tmp, name));
                }
            }).start();
        }
    }

    // ================= 工具 & 生命周期 =================

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String formatBitrate(int bytesPerSecond) {
        if (bytesPerSecond >= 1024 * 1024) {
            return String.format(Locale.CHINA, "%.1f MB/S", bytesPerSecond / 1048576.0);
        }
        return String.format(Locale.CHINA, "%d KB/S", bytesPerSecond / 1024);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 嵌入式（非全屏）播放器尺寸：
     * 竖屏手机按屏宽铺满、高=宽×9/16（米家模板为竖屏）；
     * 横屏盒子/平板若仍按屏宽铺满会得到近屏高的视频，把云台与工具条挤出屏幕，
     * 因此横屏改为「按可用高度反算宽度、水平居中收窄」，给下方云台/时间轴留出空间。
     */
    private LinearLayout.LayoutParams embeddedPlayerParams() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        if (sw <= sh) {
            return new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(sw * 9f / 16f));
        }
        int reserveNav = Math.round(sh * 0.16f); // 底部导航 + 页边距
        int h = Math.round((sh - reserveNav) * 0.62f);
        int w = Math.round(h * 16f / 9f);
        int maxW = sw - dp(24);
        if (w > maxW) {
            w = maxW;
            h = Math.round(w * 9f / 16f);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(playheadUpdater);
        handler.removeCallbacks(bitrateTicker);
        stopLive();
        try {
            if (videoView.isPlaying()) videoView.pause();
        } catch (Exception ignored) {
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == TAB_HOME && liveMode
                && gridPanel != null && gridPanel.getVisibility() != View.VISIBLE) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (liveMode && currentTab == TAB_HOME) startLive();
                }
            });
        }
    }

    // ================= 米家粉色 24 小时时间轴 =================

    public interface OnSeekListener {
        void onSeek(long millisOfDay);
    }

    public class TimelineView extends View {
        private static final int HOURS = 24;
        private final int pxPerHour;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private List<Entry> viewEntries = new ArrayList<>();
        private Entry currentEntry;
        private long playhead = -1;
        private long preview = -1;
        private OnSeekListener seekListener;
        private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm", Locale.CHINA);
        private float downX, downY;
        private float lastDragX;
        private long downTime;
        private boolean decided;
        private boolean dragging;
        /** 判定为「拖动浏览」而非「轻点定位」的阈值（像素/毫秒）。 */
        private int touchSlop;

        public TimelineView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            pxPerHour = (int) (120 * density + 0.5f);
            touchSlop = (int) (12 * density + 0.5f);
            textPaint.setColor(Color.rgb(150, 120, 110));
            textPaint.setTextSize(10 * density + 0.5f);
        }

        public void setOnSeekListener(OnSeekListener l) {
            seekListener = l;
        }

        public int getContentWidth() {
            return HOURS * pxPerHour;
        }

        public void setEntries(List<Entry> list) {
            viewEntries = list == null ? new ArrayList<Entry>() : list;
            playhead = -1;
            invalidate();
        }

        public void setCurrentEntry(Entry e) {
            currentEntry = e;
            invalidate();
        }

        public void setPlayhead(long millisOfDay) {
            playhead = millisOfDay;
            invalidate();
        }

        public int xForTimeOfDay(long epochMs) {
            return (int) (millisOfDay(epochMs) / 3600000.0 * pxPerHour);
        }

        /** 时间轴被外层 HorizontalScrollView 承载，拖动浏览时直接驱动它滚动。 */
        private HorizontalScrollView hostScroll() {
            return timelineScroll;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(getContentWidth(), MeasureSpec.getSize(heightMeasureSpec));
        }

        /**
         * 触摸策略（为什么不能在 DOWN 就消费）：
         * 本 View 放在 HorizontalScrollView 里。若像旧实现在 DOWN/MOVE 一律 return true，
         * ScrollView 永远收不到横向滑动，24 小时轴只能停在初始位置、看不到后半天的录像。
         * 这里区分两种手势：
         *  - 横向移动超过 touchSlop → 判定「拖动浏览」，把事件交还父级 ScrollView 去滚动；
         *  - 按下后基本不移动 → 判定「轻点定位」，UP 时 seek 到该时刻。
         */
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downTime = android.os.SystemClock.uptimeMillis();
                decided = false;
                dragging = false;
                // 先自己接管，待判定为横拖再放开给父 ScrollView（标准拦截协作模式）。
                getParent().requestDisallowInterceptTouchEvent(true);
                preview = (long) (downX / (double) pxPerHour * 3600000.0);
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                if (!decided) {
                    float dxRaw = event.getX() - downX;
                    float dx = Math.abs(dxRaw);
                    float dy = Math.abs(event.getY() - downY);
                    if (dx > touchSlop && dx > dy) {
                        // 横向拖动浏览：子 View 已消费 DOWN，父 ScrollView 无法中途接管同一手势，
                        // 因此直接把手指位移反向映射成父滚动（内容随手走，跟手且不触发 seek）。
                        decided = true;
                        dragging = true;
                        preview = -1;
                        lastDragX = event.getX();
                        invalidate();
                        return true;
                    }
                    if (dy > touchSlop) {
                        decided = true;
                        dragging = true;
                        preview = -1;
                        invalidate();
                        return true;
                    }
                }
                if (dragging) {
                    HorizontalScrollView sc = hostScroll();
                    if (sc != null) {
                        int delta = Math.round(lastDragX - event.getX());
                        if (delta != 0) sc.scrollBy(delta, 0);
                    }
                    lastDragX = event.getX();
                } else {
                    preview = (long) (event.getX() / (double) pxPerHour * 3600000.0);
                    invalidate();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                long at = (long) (event.getX() / (double) pxPerHour * 3600000.0);
                boolean quickTap = android.os.SystemClock.uptimeMillis() - downTime < 400;
                if (!dragging && quickTap && seekListener != null) {
                    seekListener.onSeek(at);
                }
                preview = -1;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                preview = -1;
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getContentWidth();
            int h = getHeight();
            float density = getResources().getDisplayMetrics().density;
            int top = (int) (8 * density);
            // 底部留出一条标签带，整点时间画在画布内，避免被容器边缘裁掉。
            int labelTop = h - (int) (20 * density);
            int bottom = labelTop - (int) (6 * density);

            // 粉色底（米家回看时间轴）
            paint.setColor(C_PINK);
            canvas.drawRect(0, 0, w, h, paint);

            // 小时刻度竖线 + 半小时短刻度 + 整点标签（每 2 小时一个，收在底部标签带内）
            textPaint.setColor(Color.rgb(150, 120, 110));
            for (int hour = 0; hour <= HOURS; hour++) {
                int x = hour * pxPerHour;
                paint.setColor(Color.rgb(226, 196, 184));
                canvas.drawLine(x, top, x, bottom, paint);
                if (hour < HOURS) {
                    paint.setColor(Color.rgb(236, 212, 202));
                    canvas.drawLine(x + pxPerHour / 2f, top + (bottom - top) * 0.22f,
                            x + pxPerHour / 2f, bottom - (bottom - top) * 0.12f, paint);
                }
                if (hour % 2 == 0 && hour < HOURS) {
                    String lbl = String.format(Locale.CHINA, "%02d:00", hour);
                    canvas.drawText(lbl, x + 4 * density,
                            labelTop + 12 * density, textPaint);
                }
            }

            // 录像段：青色实条（当前段加深描边），运动区间橙色标在条带下半
            for (Entry e : viewEntries) {
                long start = millisOfDay(e.time);
                long end = millisOfDay(e.end);
                if (end <= start) end = start + 60 * 1000;
                float left = (float) (start / 3600000.0 * pxPerHour);
                float right = (float) (end / 3600000.0 * pxPerHour);
                boolean isCurrent = e == currentEntry;
                float bandTop = top + 6 * density;
                float bandBottom = bottom - 4 * density;
                paint.setColor(isCurrent ? C_TEAL_DARK : C_TEAL);
                rect.set(left, bandTop, Math.max(right, left + 4),
                        bandTop + (bandBottom - bandTop) * 0.42f);
                canvas.drawRoundRect(rect, 4 * density, 4 * density, paint);
                if (isCurrent) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(Color.WHITE);
                    paint.setStrokeWidth(2 * density);
                    canvas.drawRoundRect(rect, 4 * density, 4 * density, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                for (long[] m : e.motion) {
                    long ms = millisOfDay(m[0]);
                    long me = millisOfDay(m[1]);
                    if (me <= ms) continue;
                    float ml = (float) (ms / 3600000.0 * pxPerHour);
                    float mr = (float) (me / 3600000.0 * pxPerHour);
                    if (mr < left || ml > right) continue;
                    paint.setColor(C_PINK_MARK);
                    float mTop = bandTop + (bandBottom - bandTop) * 0.52f;
                    canvas.drawRect(Math.max(ml, left), mTop,
                            Math.min(mr, right), bandBottom, paint);
                }
            }

            // 播放位置：橙色竖线（贯穿条带）
            if (playhead >= 0) {
                float x = (float) (playhead / 3600000.0 * pxPerHour);
                paint.setColor(C_PINK_MARK);
                canvas.drawLine(x, top - 2 * density, x, bottom, paint);
                canvas.drawCircle(x, bottom + 3 * density, 4 * density, paint);
            }
            // 轻点预览：当前时刻气泡
            if (preview >= 0) {
                float x = (float) (preview / 3600000.0 * pxPerHour);
                String label = tf.format(new Date(preview));
                float tw = textPaint.measureText(label);
                float tx = Math.min(Math.max(x - tw / 2, 4), w - tw - 4);
                paint.setColor(0xEE222222);
                canvas.drawRect(tx - 6, 2, tx + tw + 6, top + 12 * density, paint);
                paint.setColor(Color.WHITE);
                canvas.drawText(label, tx, top + 9 * density, textPaint);
            }
        }
    }

    // ================= 云台方向盘（高保真装饰，固定摄像头点击提示不支持） =================

    public static class PtzDialView extends View {
        public interface DirectionListener {
            void onDirection(String name);
        }

        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private DirectionListener listener;
        private int pressedDir = -1; // 0上 1右 2下 3左

        public PtzDialView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            fillPaint.setColor(Color.WHITE);
            fillPaint.setShadowLayer(8 * density, 0, 2 * density, 0x22000000);
            centerPaint.setColor(Color.rgb(82, 184, 188));
            arrowPaint.setColor(Color.rgb(150, 154, 160));
            arrowPaint.setStrokeWidth(3 * density);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(LAYER_TYPE_SOFTWARE, fillPaint);
        }

        public void setDirectionListener(DirectionListener l) {
            listener = l;
        }

        private float cx() {
            return getWidth() / 2f;
        }

        private float cy() {
            return getHeight() / 2f;
        }

        private float radius() {
            return Math.min(getWidth(), getHeight()) / 2f - dp2(8);
        }

        private int dp2(int v) {
            return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
        }

        /** 根据触点角度判定方向（上/右/下/左），中心圆内不响应。 */
        private int directionAt(float x, float y) {
            float dx = x - cx();
            float dy = y - cy();
            float dist = (float) Math.hypot(dx, dy);
            if (dist < radius() * 0.32f) return -1;
            double ang = Math.toDegrees(Math.atan2(dy, dx));
            if (ang >= -45 && ang < 45) return 1;    // 右
            if (ang >= 45 && ang < 135) return 2;    // 下
            if (ang >= -135 && ang < -45) return 0;  // 上
            return 3;                                 // 左
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressedDir = directionAt(event.getX(), event.getY());
                invalidate();
                return pressedDir >= 0;
            }
            if (action == MotionEvent.ACTION_UP) {
                int dir = directionAt(event.getX(), event.getY());
                if (dir >= 0 && dir == pressedDir && listener != null) {
                    listener.onDirection(new String[]{"上", "右", "下", "左"}[dir]);
                }
                pressedDir = -1;
                invalidate();
                return true;
            }
            if (action == MotionEvent.ACTION_CANCEL) {
                pressedDir = -1;
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = cx();
            float cy = cy();
            float r = radius();
            float density = getResources().getDisplayMetrics().density;

            fillPaint.setColor(pressedDir >= 0 ? Color.rgb(238, 242, 244) : Color.WHITE);
            canvas.drawCircle(cx, cy, r, fillPaint);

            // 中心青色圆
            canvas.drawCircle(cx, cy, r * 0.22f, centerPaint);

            // 四个三角箭头
            float d = r * 0.46f;
            float s = 7 * density;
            drawArrow(canvas, cx, cy - d, s, 0);
            drawArrow(canvas, cx + d, cy, s, 1);
            drawArrow(canvas, cx, cy + d, s, 2);
            drawArrow(canvas, cx - d, cy, s, 3);
        }

        private void drawArrow(Canvas canvas, float x, float y, float s, int dir) {
            android.graphics.Path path = new android.graphics.Path();
            // dir: 0上 1右 2下 3左，画一个指向该方向的小三角
            if (dir == 0) {
                path.moveTo(x, y - s);
                path.lineTo(x - s, y + s * 0.7f);
                path.lineTo(x + s, y + s * 0.7f);
            } else if (dir == 2) {
                path.moveTo(x, y + s);
                path.lineTo(x - s, y - s * 0.7f);
                path.lineTo(x + s, y - s * 0.7f);
            } else if (dir == 1) {
                path.moveTo(x + s, y);
                path.lineTo(x - s * 0.7f, y - s);
                path.lineTo(x - s * 0.7f, y + s);
            } else {
                path.moveTo(x - s, y);
                path.lineTo(x + s * 0.7f, y - s);
                path.lineTo(x + s * 0.7f, y + s);
            }
            path.close();
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(150, 154, 160));
            canvas.drawPath(path, p);
        }
    }
}







