package com.zyyme.workdayviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MJPEG 播放器：读取摄像头后台的 multipart/x-mixed-replace 视频流，
 * 逐帧解码显示。断线由 OnErrorListener 通知外层重连。
 */
public class MjpegView extends View {
    public interface OnErrorListener {
        void onError(String message);
    }

    public interface OnFrameListener {
        /** 成功收到并显示第一帧（连接成功）。 */
        void onFirstFrame();
    }

    /** 摄像头在录像中时会通过本通道下发 H.264 码流（复用录像编码管线）。 */
    public interface OnH264FrameListener {
        void onH264Frame(byte[] annexbData);
    }

    /**
     * 流内容模式变化回调：isH264=true 表示当前下发的是 H.264（应由外置 SurfaceView
     * 硬解显示），false 表示是 JPEG（本 View 自己绘制）。外层据此让两个视图互斥显隐，
     * 避免黑色占位的一方盖住真正出画的另一方。
     */
    public interface OnStreamModeListener {
        void onStreamMode(boolean isH264);
    }

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 实时流累计接收字节数（含 JPEG/H.264 载荷），供界面计算实时码率。 */
    private final AtomicLong totalBytes = new AtomicLong(0);
    /** 滑动码率窗口：只统计最近 1 秒收到的字节，避免平均值被连接时长稀释。 */
    private final AtomicLong windowBytes = new AtomicLong(0);
    private volatile long windowStartNs = 0L;
    private volatile Bitmap bitmap;
    private Thread thread;
    private OnErrorListener errorListener;
    private OnFrameListener frameListener;
    private OnH264FrameListener h264Listener;
    private OnStreamModeListener streamModeListener;
    private String url;
    private boolean frameNotified = false;
    private int retryCount = 0;
    /** 当前连接已识别出的流模式：-1 未知，0 JPEG，1 H.264。 */
    private int streamMode = -1;

    public MjpegView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
    }

    public void setOnErrorListener(OnErrorListener listener) {
        this.errorListener = listener;
    }

    public void setOnFrameListener(OnFrameListener listener) {
        this.frameListener = listener;
    }

    public void setOnH264FrameListener(OnH264FrameListener listener) {
        this.h264Listener = listener;
    }

    public void setOnStreamModeListener(OnStreamModeListener listener) {
        this.streamModeListener = listener;
    }

    public synchronized void start(String url) {
        stop();
        this.url = url;
        frameNotified = false;
        retryCount = 0;
        streamMode = -1;
        totalBytes.set(0);
        windowStartNs = System.nanoTime();
        windowBytes.set(0);
        running.set(true);
        thread = new Thread(readerLoop, "mjpeg-reader");
        thread.start();
    }

    /**
     * 抓拍当前 JPEG 帧的副本（供「截图」保存到相册）。
     * 必须拷贝：内部 bitmap 会随下一帧被 recycle，直接返回会导致保存线程读到已回收位图。
     * H.264 硬解模式下本 View 不持有位图（帧在 graphic buffer），返回 null 由上层提示。
     */
    public Bitmap snapshot() {
        Bitmap b = bitmap;
        if (b == null || b.isRecycled()) return null;
        try {
            return b.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前是否处于 H.264 硬解模式（此模式下无法用 snapshot 抓拍）。 */
    public boolean isH264Mode() {
        return streamMode == 1;
    }

    public synchronized void stop() {
        running.set(false);
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        Bitmap old = bitmap;
        bitmap = null;
        if (old != null) old.recycle();
        postInvalidate();
    }

    private final Runnable readerLoop = new Runnable() {
        @Override
        public void run() {
            while (running.get()) {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(0);
                    conn.connect();
                    int code = conn.getResponseCode();
                    if (code != 200) {
                        notifyError("连接失败（HTTP " + code + "），检查地址密码");
                        sleepRetry();
                        continue;
                    }
                    String contentType = conn.getContentType();
                    if (contentType == null || !contentType.toLowerCase().contains("multipart")) {
                        notifyError("摄像头未开启实时视频");
                        sleepRetry();
                        continue;
                    }
                    InputStream raw = conn.getInputStream();
                    readStream(new BufferedInputStream(raw));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    if (running.get()) {
                        retryCount++;
                        notifyError("画面中断，第 " + retryCount + " 次重连…");
                        try {
                            sleepRetry();
                        } catch (InterruptedException ie) {
                            return;
                        }
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }
    };

    private void sleepRetry() throws InterruptedException {
        Thread.sleep(2000);
    }

    private void notifyError(final String message) {
        if (errorListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (errorListener != null) errorListener.onError(message);
                }
            });
        }
    }

    private void notifyFirstFrame() {
        if (frameListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (frameListener != null) frameListener.onFirstFrame();
                }
            });
        }
    }

    /** 流模式发生变化时才回调一次（H.264 帧很密，避免每帧都 post 到主线程）。 */
    private void notifyStreamMode(final boolean isH264) {
        int mode = isH264 ? 1 : 0;
        if (streamMode == mode) return;
        streamMode = mode;
        if (isH264) {
            // 切到 H.264：本 View 不再绘制，释放旧 JPEG 位图，避免互斥显隐切换前闪一帧旧图。
            Bitmap old = bitmap;
            bitmap = null;
            if (old != null) old.recycle();
        }
        if (streamModeListener != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    if (streamModeListener != null) streamModeListener.onStreamMode(isH264);
                }
            });
        }
    }

    private void readStream(BufferedInputStream in) throws IOException {
        // HttpURLConnection 已消费掉 HTTP 响应头，流里直接就是 multipart 帧：
        // --frame\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<jpeg>\r\n
        while (running.get()) {
            // multipart 帧：--frame\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<jpeg>\r\n
            String line = readLine(in);
            if (line == null) throw new IOException("stream closed");
            if (!line.startsWith("--")) continue;
            int contentLength = -1;
            while (true) {
                line = readLine(in);
                if (line == null) throw new IOException("stream closed");
                if (line.isEmpty()) break;
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            byte[] jpeg;
            if (contentLength > 0) {
                jpeg = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(jpeg, off, contentLength - off);
                    if (n < 0) throw new IOException("stream closed");
                    off += n;
                }
            } else {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                int prev = -1, cur;
                jpeg = null;
                while ((cur = in.read()) >= 0) {
                    if (prev == 13 && cur == 10) {
                        byte[] all = buf.toByteArray();
                        if (all.length >= 2) {
                            jpeg = new byte[all.length - 1];
                            System.arraycopy(all, 0, jpeg, 0, all.length - 1);
                        }
                        break;
                    }
                    buf.write(cur);
                    prev = cur;
                }
                if (jpeg == null) throw new IOException("stream closed");
            }
            Bitmap decoded = null;
            accountBytes(jpeg.length);
            if (looksLikeH264(jpeg)) {
                // 录像进行中：摄像头复用编码管线，下发的是 H.264 码流
                notifyStreamMode(true);
                if (!frameNotified) {
                    frameNotified = true;
                    notifyFirstFrame();
                }
                if (h264Listener != null) h264Listener.onH264Frame(jpeg);
                continue;
            }
            decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (decoded != null) {
                notifyStreamMode(false);
                if (!frameNotified) {
                    frameNotified = true;
                    notifyFirstFrame();
                }
                Bitmap old = bitmap;
                bitmap = decoded;
                if (old != null && old != decoded) old.recycle();
                postInvalidate();
            }
        }
    }

    private void accountBytes(int len) {
        totalBytes.addAndGet(len);
        windowBytes.addAndGet(len);
    }

    /**
     * 最近约 1 秒的实时码率（字节/秒）。每次调用滚动一次窗口，
     * 让标题下的「xx KB/S」随网络与编码波动刷新，而不是整条连接的平均值。
     */
    public int bytesPerSecond() {
        long now = System.nanoTime();
        long start = windowStartNs;
        long elapsedMs = (now - start) / 1_000_000L;
        if (elapsedMs >= 900L) {
            int bytes = (int) windowBytes.getAndSet(0);
            windowStartNs = now;
            if (elapsedMs <= 0) return 0;
            return (int) (bytes * 1000L / elapsedMs);
        }
        // 窗口未满时按已过时间折算，避免刚连接时显示成 0。
        if (elapsedMs > 0) {
            return (int) (windowBytes.get() * 1000L / elapsedMs);
        }
        return 0;
    }

    /** 判断载荷是否为 H.264 Annex-B 码流（00 00 00 01 / 00 00 01 开头）。 */
    private static boolean looksLikeH264(byte[] data) {
        return data.length >= 5
                && data[0] == 0 && data[1] == 0
                && (data[2] == 1 || (data[2] == 0 && data[3] == 1));
    }

    /** 读一行（到 \n 为止，去掉结尾 \r），返回 null 表示流结束。 */
    private static String readLine(BufferedInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == 10) {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == 13) sb.setLength(len - 1);
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Bitmap b = bitmap;
        if (b == null || b.isRecycled()) return;
        float scale = Math.min(
                (float) getWidth() / b.getWidth(),
                (float) getHeight() / b.getHeight());
        float w = b.getWidth() * scale;
        float h = b.getHeight() * scale;
        float left = (getWidth() - w) / 2f;
        float top = (getHeight() - h) / 2f;
        canvas.drawBitmap(b, null, new android.graphics.RectF(left, top, left + w, top + h), paint);
    }
}
