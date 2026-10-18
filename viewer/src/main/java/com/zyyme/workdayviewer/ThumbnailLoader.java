package com.zyyme.workdayviewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回看网格缩略图加载器：
 * 用 MediaMetadataRetriever 直接对录像 HTTP 地址按需抽帧，三级保障——
 * 内存 LruCache（最快）→ 磁盘缓存(cacheDir/thumbs，二次进入秒开)→ 网络抽帧（慢，限 2 线程）。
 *
 * 设计要点（为什么这样做）：
 * - 一个小时格子对应"该小时附近某段录像的一帧"。同一段录像可能被多个小时格子复用，
 *   因此缓存 key 必须把"录像文件名 + 抽取时间点 + 是否灰度"都纳入，避免串图。
 * - MediaMetadataRetriever 非线程安全且占用大，这里每个任务独立创建并在 finally 释放，
 *   用固定 2 线程池限流，防止 24 个格子同时拉流把弱机（瑞芯微盒子）和网络压垮。
 * - 用 View 上的 tag 做"世代校验"：列表复用/切换日期后，晚到的旧任务结果不能覆盖新格子。
 */
public class ThumbnailLoader {
    /** 缩略图目标短边宽度（像素），网格里无需全分辨率，抽帧后主动缩放省内存。 */
    private static final int TARGET_WIDTH = 360;
    /** JPEG 磁盘缓存质量。 */
    private static final int JPEG_QUALITY = 80;
    /** 内存缓存上限（字节），取应用可用堆的 1/8，属于 LruCache 的经验值。 */
    private static final int MEM_CACHE_BYTES = (int) (Runtime.getRuntime().maxMemory() / 8);

    private final Context appContext;
    private final File diskDir;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memCache = new LruCache<String, Bitmap>(
            MEM_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** 进行中的任务世代：每次 cancelAll 自增，作废此前所有排队/在途回调。 */
    private final AtomicInteger generation = new AtomicInteger(0);

    public ThumbnailLoader(Context context) {
        appContext = context.getApplicationContext();
        diskDir = new File(appContext.getCacheDir(), "thumbs");
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    /** 切换日期 / 退出页面时作废所有在途结果（不清缓存，缓存仍可复用）。 */
    public void cancelAll() {
        generation.incrementAndGet();
    }

    /**
     * 把一帧绑定到指定 ImageView。
     *
     * @param target 目标格子
     * @param fileUrl 录像完整 HTTP 地址（含密码前缀）
     * @param frameUs 该段录像内的抽取时间点（微秒）
     * @param gray    是否转灰度（米家模板里夜间小时是黑白画面）
     */
    public void load(ImageView target, String fileUrl, long frameUs, boolean gray) {
        final String key = cacheKey(fileUrl, frameUs, gray);
        target.setTag(R.id.thumb_tag, key);

        Bitmap cached = memCache.get(key);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        final int gen = generation.get();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = loadFromDisk(key);
                if (bmp == null) {
                    bmp = extractFromNetwork(fileUrl, frameUs);
                    if (bmp != null) {
                        bmp = scaleToWidth(bmp, TARGET_WIDTH);
                        if (gray) bmp = toGray(bmp);
                        saveToDisk(key, bmp);
                    }
                }
                if (bmp != null) memCache.put(key, bmp);
                final Bitmap result = bmp;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // 世代或 tag 不匹配说明结果已过期（用户已切日期/复用了格子），丢弃。
                        if (gen != generation.get()) return;
                        Object tag = target.getTag(R.id.thumb_tag);
                        if (!key.equals(tag)) return;
                        if (result != null) target.setImageBitmap(result);
                    }
                });
            }
        });
    }

    /**
     * 网络抽帧：独立创建 retriever，finally 必释放，避免 native 上下文泄漏。
     *
     * 为什么不用 setDataSource(url, headers) 重载（实测根因）：
     * 瑞芯微 RK3566/3568 把 MediaMetadataRetriever 底层换成 Rockit(内置 ffmpeg)，
     * 该重载会把数字 IP 主机错误截断成首段去做 DNS 解析，日志明确报
     * "ffmpeg: Failed to resolve hostname 192: No address associated with hostname"，
     * 导致连不上服务端、抽帧全黑。改用 setDataSource(Context, Uri) 后，
     * 网络由系统 Java HTTP DataSource 处理（原生 AOSP 路径），不再经 Rockit 自己解析 URL，
     * 从而绕过该 native 兼容缺陷。
     */
    private Bitmap extractFromNetwork(String fileUrl, long frameUs) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(appContext, android.net.Uri.parse(fileUrl));
            // OPTION_CLOSEST_SYNC 优先取最近同步帧，seek 快、解码稳，适合缩略图。
            return retriever.getFrameAtTime(
                    Math.max(0L, frameUs),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private Bitmap scaleToWidth(Bitmap src, int targetWidth) {
        if (src.getWidth() <= targetWidth) return src;
        float ratio = (float) targetWidth / src.getWidth();
        int h = Math.round(src.getHeight() * ratio);
        Bitmap scaled = Bitmap.createBitmap(targetWidth, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        canvas.drawBitmap(src,
                new android.graphics.Rect(0, 0, src.getWidth(), src.getHeight()),
                new android.graphics.Rect(0, 0, targetWidth, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        if (scaled != src) src.recycle();
        return scaled;
    }

    /** 用 ColorMatrix 去色，模拟米家模板里夜间红外画面的黑白观感。 */
    private Bitmap toGray(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(src, 0f, 0f, paint);
        src.recycle();
        return out;
    }

    private String cacheKey(String url, long frameUs, boolean gray) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String raw = url + "#" + frameUs + (gray ? "#g" : "#c");
            byte[] digest = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 取不到 MD5 算法时退化为文件名替换，仍能保证基本唯一。
            return Integer.toHexString(rawHashCode(url, frameUs, gray));
        }
    }

    private static int rawHashCode(String url, long frameUs, boolean gray) {
        int result = url.hashCode();
        result = 31 * result + (int) (frameUs ^ (frameUs >>> 32));
        result = 31 * result + (gray ? 1 : 0);
        return result;
    }

    private File diskFile(String key) {
        return new File(diskDir, key + ".jpg");
    }

    private Bitmap loadFromDisk(String key) {
        File f = diskFile(key);
        if (!f.exists()) return null;
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void saveToDisk(String key, Bitmap bmp) {
        OutputStream os = null;
        try {
            os = new FileOutputStream(diskFile(key));
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, os);
        } catch (Exception ignored) {
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
