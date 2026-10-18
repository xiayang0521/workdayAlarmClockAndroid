package com.zyyme.workdayalarmclock.camera

import java.io.ByteArrayOutputStream

/**
 * H.264/H.265 NAL 单元分帧工具：把编码器输出的任意封装形态统一展平为"裸 NAL 列表"。
 *
 * 背景：MediaCodec 编码器的输出形态随厂商/ROM 而变，实测遇到过：
 *  - 标准 AVCC：L4(NAL) L4(NAL)（4 字节大端长度前缀）；
 *  - 标准 AnnexB：SC NAL SC NAL（00 00 00 01 起始码）；
 *  - 嵌套封装：L4(L4(NAL))、SC+L4(L4(NAL))；
 *  - 混合封装：SC·SPS SC·PPS SC·L4(IDR)——用起始码分段，但其中 IDR 段
 *    自身还带 4 字节长度前缀（一加/ColorOS 关键帧实测）。
 *
 * 最后一种正是早期录像"黑屏有声"的根因：旧逻辑按起始码分段后不剥段内长度前缀，
 * 残留的 00 00 00 CC 被录像侧当成 NAL 头再包一层长度，MP4 样本变成
 * L4(L4(IDR))，解码器逐帧报 "missing picture in access unit"。
 *
 * 因此这里的关键约定是：**分帧必须递归到底**——无论 NAL 藏在长度前缀还是
 * 起始码后面，都持续剥到负载首字节是真正的 NAL 头为止（NAL 头不可能以
 * 0x00 开头，而 4 字节长度高位几乎总是 0x00，二者可可靠区分；每深入一层
 * 负载严格变短，递归必然终止）。
 */
internal object NalUnits {
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    // AVC NAL 类型
    const val AVC_SPS = 7
    const val AVC_PPS = 8
    const val AVC_IDR = 5

    // HEVC NAL 类型（nal_unit_type = (首字节>>1)&0x3F）
    const val HEVC_VPS = 32
    const val HEVC_SPS = 33
    const val HEVC_PPS = 34

    fun avcType(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f

    fun hevcType(nal: ByteArray): Int =
        if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3f

    /**
     * 把一帧（任意封装形态）彻底展平为裸 NAL 列表。
     *
     * 判定优先级与编码器实际输出对齐：先看是否为完整 AVCC 长度链，否则按 AnnexB
     * 起始码分段；两种入口最终都走同一套递归展平，因此混合/嵌套形态也能正确拆开。
     * 两种形态都无法识别（例如剥离了分隔符的裸数据）时返回空列表，由调用方丢帧，
     * 绝不靠"长得像 NAL 头"去猜边界——猜测只会把噪声写进 MP4。
     */
    fun extract(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val units = ArrayList<ByteArray>()
        if (isLengthChain(data)) {
            collectLengthChain(data, units)
            if (units.isNotEmpty()) return units
        }
        val segments = splitByStartCode(data)
        if (segments.isNotEmpty()) {
            segments.forEach { segment ->
                // 段内可能又嵌着一整条长度链（混合形态），也可能本身就是裸 NAL。
                if (isLengthChain(segment)) {
                    collectLengthChain(segment, units)
                } else {
                    units.add(segment)
                }
            }
        }
        return units
    }

    /** 转成 AnnexB（起始码分隔），每个 NAL 前补 4 字节起始码。 */
    fun toAnnexB(data: ByteArray): ByteArray {
        val units = extract(data)
        if (units.isEmpty()) return START_CODE + data
        val output = ByteArrayOutputStream(data.size + 16)
        units.forEach { nal ->
            output.write(START_CODE)
            output.write(nal)
        }
        return output.toByteArray()
    }

    /**
     * 转成 AVCC（每个 NAL 前置 4 字节大端长度），并剥除参数集（SPS/PPS/VPS）——
     * 参数集已由 MP4 的 avcC/hvcC 提供，样本里再带一份会让部分解码器困惑。
     * 返回 [AvccSample]；剥完没有可写 NAL（纯参数集帧）时返回 null。
     */
    fun toAvccSample(data: ByteArray, codec: Codec = Codec.AVC): AvccSample? {
        val units = extract(data)
        if (units.isEmpty()) return null
        val output = ByteArrayOutputStream(data.size + 16)
        var written = 0
        var hasIdr = false
        units.forEach { nal ->
            if (isParameterSet(nal, codec)) return@forEach
            if (codec == Codec.AVC && avcType(nal) == AVC_IDR) hasIdr = true
            val size = nal.size
            output.write(byteArrayOf(
                ((size ushr 24) and 0xff).toByte(),
                ((size ushr 16) and 0xff).toByte(),
                ((size ushr 8) and 0xff).toByte(),
                (size and 0xff).toByte()
            ))
            output.write(nal)
            written++
        }
        return if (written > 0) AvccSample(output.toByteArray(), hasIdr) else null
    }

    enum class Codec { AVC, HEVC }

    class AvccSample(val bytes: ByteArray, val hasIdr: Boolean)

    fun isParameterSet(nal: ByteArray, codec: Codec): Boolean {
        if (nal.isEmpty()) return true
        return if (codec == Codec.HEVC) {
            val type = hevcType(nal)
            type == HEVC_VPS || type == HEVC_SPS || type == HEVC_PPS
        } else {
            val type = avcType(nal)
            type == AVC_SPS || type == AVC_PPS
        }
    }

    /** 帧内是否含 IDR（AVC type 5）；用于可靠地打关键帧标记，兼容两种封装。 */
    fun containsAvcIdr(data: ByteArray): Boolean {
        return extract(data).any { avcType(it) == AVC_IDR }
    }

    /**
     * 递归展开一条 AVCC 长度链。NAL 负载若自身仍是完整长度链/起始码分段，
     * 继续向下剥，直到得到裸 NAL。
     */
    private fun collectLengthChain(data: ByteArray, out: MutableList<ByteArray>) {
        var offset = 0
        val size = data.size
        while (offset + 4 <= size) {
            val length = readLength(data, offset)
            val payloadStart = offset + 4
            if (length <= 0 || payloadStart + length > size) return
            val payload = data.copyOfRange(payloadStart, payloadStart + length)
            when {
                isLengthChain(payload) -> collectLengthChain(payload, out)
                hasStartCode(payload, 0) -> splitByStartCode(payload).forEach { seg ->
                    if (isLengthChain(seg)) collectLengthChain(seg, out) else out.add(seg)
                }
                else -> out.add(payload)
            }
            offset = payloadStart + length
        }
    }

    /** 按 AnnexB 起始码切成不含起始码的段（允许 3 或 4 字节起始码）。 */
    private fun splitByStartCode(data: ByteArray): List<ByteArray> {
        val units = ArrayList<ByteArray>()
        var start = findStartCode(data, 0) ?: return units
        while (true) {
            val nalStart = start.first + start.second
            val next = findStartCode(data, nalStart)
            val nalEnd = next?.first ?: data.size
            if (nalEnd > nalStart) {
                units.add(data.copyOfRange(nalStart, nalEnd))
            }
            if (next == null) break
            start = next
        }
        return units
    }

    /**
     * 是否为"恰好走到结尾"的完整 AVCC 长度链。空缓冲不算。
     * 真 NAL 首字节作为长度高位时几乎必然越界（帧长 > 1600 万），因此不会误判。
     */
    private fun isLengthChain(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        var offset = 0
        val size = data.size
        while (offset + 4 <= size) {
            val length = readLength(data, offset)
            offset += 4
            if (length <= 0 || offset + length > size) return false
            offset += length
        }
        return offset == size
    }

    private fun readLength(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
    }

    private fun findStartCode(data: ByteArray, fromIndex: Int): Pair<Int, Int>? {
        var index = fromIndex.coerceAtLeast(0)
        while (index + 2 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) return Pair(index, 3)
                if (index + 3 < data.size && data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte()
                ) return Pair(index, 4)
            }
            index++
        }
        return null
    }

    private fun hasStartCode(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= data.size) return false
        return data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            (data[offset + 2] == 1.toByte() ||
                (data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()))
    }
}
