package com.zyyme.workdayviewer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 实时流 H.264 硬解码器：摄像头后台在录像进行中会把 H.264 Annex-B 码流
 * 通过 multipart 通道下发（复用录像编码管线）。本类负责：
 * 1. 从码流中提取 SPS/PPS，解析视频宽高；
 * 2. 把 Annex-B 帧转成 AVCC（4 字节长度前缀）格式喂给 MediaCodec；
 * 3. 解码输出到 Surface（播放器里的 SurfaceView）。
 * 全部方法在读取线程调用，内部已做同步。
 */
public class H264LiveDecoder {
    private static final int NAL_TYPE_SPS = 7;
    private static final int NAL_TYPE_PPS = 8;

    /** 持续等不到解码条件时，多久后提示一次原因，避免用户只看到黑屏。 */
    private static final long WAIT_REPORT_MS = 3000L;

    private MediaCodec decoder;
    private byte[] sps;   // 含 00 00 00 01 前缀
    private byte[] pps;   // 含 00 00 00 01 前缀
    private Surface surface;
    private boolean hevcDetected;
    /** 已通过 AVC 参数集（SPS/PPS）锁定为 H.264，锁定后不再对后续帧做 HEVC 猜测。 */
    private boolean codecLocked;
    private long frameIndex;
    private long waitSinceMs;
    private String lastWaitReason;
    private boolean stallReported;

    public interface ErrorListener {
        void onError(String message);
    }

    private ErrorListener errorListener;

    public void setErrorListener(ErrorListener listener) {
        this.errorListener = listener;
    }

    /** Surface 就绪/销毁时调用（UI 线程）。 */
    public synchronized void setSurface(Surface s) {
        surface = s;
        if (s == null && decoder != null) {
            releaseCodec();
        }
    }

    public synchronized void release() {
        releaseCodec();
        sps = null;
        pps = null;
        hevcDetected = false;
        codecLocked = false;
        frameIndex = 0;
        waitSinceMs = 0;
        lastWaitReason = null;
        stallReported = false;
    }

    /**
     * 解码器迟迟建不起来时，把原因回报给界面（而不是让用户对着黑屏猜）。
     * 同一条原因只提示一次，条件变化后允许再次提示。
     */
    private void reportWaitingOnce(String reason, long afterMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!reason.equals(lastWaitReason)) {
            lastWaitReason = reason;
            waitSinceMs = now;
            stallReported = false;
            return;
        }
        if (stallReported || now - waitSinceMs < afterMs) return;
        stallReported = true;
        onError(reason);
    }

    private void releaseCodec() {
        if (decoder != null) {
            try {
                decoder.stop();
            } catch (Exception ignored) {
            }
            try {
                decoder.release();
            } catch (Exception ignored) {
            }
            decoder = null;
        }
    }

    private void onError(String msg) {
        if (errorListener != null) errorListener.onError(msg);
    }

    /**
     * 喂入一帧 Annex-B 数据（可含多个 NAL）。
     * 返回 false 表示本帧被丢弃（等关键帧/等 Surface/解码器满）。
     */
    public synchronized boolean feed(byte[] annexb) {
        if (hevcDetected) return false;
        List<byte[]> nals = splitAnnexB(annexb);
        if (nals.isEmpty()) return false;

        // 编码格式判定必须由"参数集"驱动，不能只看第一个 NAL 头。
        // 旧逻辑对任意帧套用 HEVC 公式 ((b0 & 0x7E) >> 1)：AVC 非关键帧头 0x41
        // （ref_idc=2, slice 类型=1）代入恰好等于 32，被误当成 HEVC VPS，于是正常的
        // H.264 实时流被错误提示"摄像头实时编码为 H.265"。
        // 可靠判据：
        //  - 帧内出现 AVC SPS(7)/PPS(8) => 锁定 H.264（HEVC 流绝不会带 AVC 参数集）；
        //  - 仅当帧内 HEVC SPS(33) 与 PPS(34) 成对出现（HEVC 配置帧的充要特征），
        //    才认定 H.265。单个 AVC slice 头即使数值碰巧等于 33/34 也无法成对伪造。
        boolean sawAvcParamSet = false;
        boolean sawHevcSps = false;
        boolean sawHevcPps = false;
        for (byte[] nal : nals) {
            int header = nal[0] & 0xFF;
            int avcType = header & 0x1F;
            int hevcType = (header >> 1) & 0x3F;
            if (avcType == NAL_TYPE_SPS || avcType == NAL_TYPE_PPS) {
                sawAvcParamSet = true;
            }
            if (hevcType == 33) sawHevcSps = true;
            if (hevcType == 34) sawHevcPps = true;
        }
        if (sawAvcParamSet) {
            codecLocked = true;   // 首帧带 SPS/PPS，永久锁定为 H.264
        } else if (!codecLocked && sawHevcSps && sawHevcPps) {
            hevcDetected = true;
            onError("摄像头实时编码为 H.265，本播放器暂不支持，请在摄像头设置中改用 H.264");
            return false;
        }

        boolean hasIdr = false;
        for (byte[] nal : nals) {
            int type = nal[0] & 0x1F;
            if (type == 7 && sps == null) {
                sps = withStartCode(nal);
            } else if (type == 8 && pps == null) {
                pps = withStartCode(nal);
            }
            if (type == 5) hasIdr = true;
        }

        if (decoder == null) {
            if (sps == null || pps == null) {
                reportWaitingOnce("等待摄像头参数集…", WAIT_REPORT_MS);
                return false;
            }
            if (surface == null) {
                reportWaitingOnce("等待画面 Surface…", WAIT_REPORT_MS);
                return false;            // 等 Surface
            }
            if (!hasIdr) {
                reportWaitingOnce("等待关键帧…", WAIT_REPORT_MS);
                return false;            // 等关键帧起手
            }
            stallReported = true;
            if (!createDecoder()) return false;
        }

        // 输入统一喂 Annex-B（带 00 00 00 01 起始码），并在每个 IDR 关键帧前内联
        // SPS/PPS。原因：瑞芯微 OMX 硬解不会把 configure 时的 csd 自动拼到后续 IDR 前，
        // 单独喂入"剥光参数集的 IDR"会让解码器一直停在 TRY_AGAIN_LATER、永不产出画面。
        // 在关键帧里内联参数集是 H.264 直播最通用的做法，不依赖各芯片对 csd 的处理差异。
        boolean frameContainsSps = false;
        for (byte[] nal : nals) {
            if ((nal[0] & 0x1F) == NAL_TYPE_SPS) { frameContainsSps = true; break; }
        }
        ByteArrayOutputStream annexB = new ByteArrayOutputStream();
        byte[] startCode = {0, 0, 0, 1};
        if (hasIdr && !frameContainsSps && sps != null && pps != null) {
            annexB.write(sps, 0, sps.length); // sps/pps 内部已带 00 00 00 01 前缀
            annexB.write(pps, 0, pps.length);
        }
        for (byte[] nal : nals) {
            annexB.write(startCode, 0, startCode.length);
            annexB.write(nal, 0, nal.length);
        }
        byte[] sampleBytes = annexB.toByteArray();
        int total = sampleBytes.length;

        int index;
        try {
            index = decoder.dequeueInputBuffer(0);
        } catch (Exception e) {
            releaseCodec();
            return false;
        }
        if (index < 0) return false; // 解码器忙，丢帧
        ByteBuffer input = decoder.getInputBuffer(index);
        if (input == null || input.capacity() < total) {
            decoder.queueInputBuffer(index, 0, 0, 0, 0);
            return false;
        }
        input.clear();
        input.put(sampleBytes);
        int flags = hasIdr ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
        decoder.queueInputBuffer(index, 0, total,
                frameIndex * 33333 + 33333, flags);
        frameIndex++;

        drain();
        return true;
    }

    private boolean createDecoder() {
        try {
            int[] wh = parseSpsSize(sps);
            MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIMETYPE_VIDEO_AVC, wh[0], wh[1]);
            // csd-0/csd-1 用带 00 00 00 01 起始码的形态（本机硬解实测接受）。
            // 注意：真正让瑞芯微 OMX 解码器开始产出的是"每个 IDR 前内联 SPS/PPS"
            //（见 feed），csd 仅用于 configure 阶段建立初始分辨率，二者缺一在该芯片上
            // 都可能出现"能建解码器、帧也喂得进、但永不吐画面"的情况。
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            return true;
        } catch (Exception e) {
            releaseCodec();
            onError("H.264 解码器启动失败：" + (e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage()));
            return false;
        }
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int out;
            try {
                out = decoder.dequeueOutputBuffer(info, 0);
            } catch (Exception e) {
                releaseCodec();
                return;
            }
            if (out >= 0) {
                // 解码到 Surface 时 BufferInfo.size 恒为 0（帧直接进 graphic buffer，
                // 不回读字节），因此绝不能拿 size>0 当渲染开关——那会让 render 恒为 false，
                // 解码器照常产出 gralloc 缓冲却一帧都不上屏，表现为"有解码、纯黑屏"。
                // info.presentationTimeUs > 0 用于跳过空的格式/配置输出，真实帧一律渲染。
                boolean render = info.size > 0 || info.presentationTimeUs > 0;
                decoder.releaseOutputBuffer(out, render);
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
                    || out == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                continue;
            } else {
                break; // INFO_TRY_AGAIN_LATER
            }
        }
    }

    /** 拆分 Annex-B 码流为 NAL 数组（去掉起始码）。 */
    private static List<byte[]> splitAnnexB(byte[] data) {
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i + 3 < data.length) {
            if (data[i] == 0 && data[i + 1] == 0
                    && ((data[i + 2] == 1)
                    || (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1))) {
                int start = (data[i + 2] == 1) ? i + 3 : i + 4;
                spans.add(new int[]{start, -1});
                i = start;
            } else {
                i++;
            }
        }
        List<byte[]> nals = new ArrayList<>();
        for (int j = 0; j < spans.size(); j++) {
            int start = spans.get(j)[0];
            int end = (j + 1 < spans.size()) ? findPrefixStart(data, spans.get(j + 1)[0]) : data.length;
            if (end > start) {
                // 去掉结尾的 0 字节（trailing zero）
                int e = end;
                while (e > start && data[e - 1] == 0) e--;
                nals.add(java.util.Arrays.copyOfRange(data, start, e));
            }
        }
        return nals;
    }

    /** 从后往前找下一个 NAL 起始码的起点（处理 3/4 字节起始码）。 */
    private static int findPrefixStart(byte[] data, int nalStart) {
        if (nalStart >= 4 && data[nalStart - 4] == 0 && data[nalStart - 3] == 0
                && data[nalStart - 2] == 0 && data[nalStart - 1] == 1) {
            return nalStart - 4;
        }
        return nalStart - 3;
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    /** 极简 SPS 解析：去掉起始码与防竞争字节后按位读取，提取宽高。 */
    private static int[] parseSpsSize(byte[] spsWithPrefix) {
        int start = (spsWithPrefix[2] == 1) ? 3 : 4;
        byte[] rbsp = new byte[spsWithPrefix.length - start];
        int rbspLen = 0;
        for (int i = start; i < spsWithPrefix.length; i++) {
            if (i + 2 < spsWithPrefix.length
                    && spsWithPrefix[i] == 0 && spsWithPrefix[i + 1] == 0 && spsWithPrefix[i + 2] == 3) {
                rbsp[rbspLen++] = 0;
                rbsp[rbspLen++] = 0;
                i += 2;
            } else {
                rbsp[rbspLen++] = spsWithPrefix[i];
            }
        }
        BitReader br = new BitReader(rbsp, rbspLen);
        br.u(8); // NAL 头（0x67）
        int profile = br.u(8); // profile_idc
        br.u(8); // constraint flags
        br.u(8); // level_idc
        br.ue(); // seq_parameter_set_id
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244
                || profile == 44 || profile == 83 || profile == 86 || profile == 118
                || profile == 128 || profile == 138 || profile == 139 || profile == 134
                || profile == 135) {
            int chroma = br.ue();
            if (chroma == 3) br.u(1);
            br.ue();
            br.ue();
            br.u(1);
            if (br.u(1) == 1) {
                int count = (chroma != 3) ? 8 : 12;
                for (int k = 0; k < count; k++) {
                    if (br.u(1) == 1) {
                        skipScalingList(br, k < 6 ? 16 : 64);
                    }
                }
            }
        }
        br.ue(); // log2_max_frame_num_minus4
        int pocType = br.ue();
        if (pocType == 0) {
            br.ue();
        } else if (pocType == 1) {
            br.u(1);
            br.se();
            br.se();
            int n = br.ue();
            for (int k = 0; k < n; k++) br.se();
        }
        br.ue(); // max_num_ref_frames
        br.u(1); // gaps_in_frame_num_value_allowed_flag
        int widthMbs = br.ue() + 1;
        int heightMbs = br.ue() + 1;
        int frameOnly = br.u(1);
        int width = widthMbs * 16;
        int height = heightMbs * 16 * (2 - frameOnly);
        return new int[]{width, height};
    }

    private static void skipScalingList(BitReader br, int size) {
        int lastScale = 8;
        int nextScale = 8;
        for (int j = 0; j < size; j++) {
            if (nextScale != 0) {
                int delta = br.se();
                nextScale = (lastScale + delta + 256) % 256;
            }
            lastScale = (nextScale == 0) ? lastScale : nextScale;
        }
    }

    private static class BitReader {
        private final byte[] data;
        private final int length;
        private int pos;

        BitReader(byte[] data, int length) {
            this.data = data;
            this.length = length;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (pos < length * 8 && bit() == 0) zeros++;
            return (1 << zeros) - 1 + (zeros > 0 ? u(zeros) : 0);
        }

        int se() {
            int k = ue();
            return (k % 2 == 1) ? (k + 1) / 2 : -(k / 2);
        }

        private int bit() {
            if (pos >= length * 8) return 0;
            int b = (data[pos / 8] >> (7 - (pos % 8))) & 1;
            pos++;
            return b;
        }
    }
}
