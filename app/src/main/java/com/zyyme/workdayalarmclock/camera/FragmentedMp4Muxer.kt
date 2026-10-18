package com.zyyme.workdayalarmclock.camera

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.max

internal class FragmentedMp4Muxer(
    private val config: AvcStreamConfig
) {
    companion object {
        private const val TIMESCALE = 90_000
        private const val TRACK_ID = 1
        private val ASCII: Charset = Charset.forName("US-ASCII")

        fun parseAvcConfig(
            annexB: ByteArray,
            width: Int,
            height: Int,
            frameRate: Int
        ): AvcStreamConfig? {
            val units = splitAnnexB(annexB)
            // HEVC：SPS_NUT=33、PPS_NUT=34、VPS_NUT=32
            val hevcSps = units.firstOrNull { hevcNalType(it) == 33 }
            if (hevcSps != null) {
                val vps = units.firstOrNull { hevcNalType(it) == 32 } ?: return null
                val pps = units.firstOrNull { hevcNalType(it) == 34 } ?: return null
                if (hevcSps.size < 13) return null
                return AvcStreamConfig(
                    hevcSps.copyOf(),
                    pps.copyOf(),
                    width,
                    height,
                    frameRate.coerceAtLeast(1),
                    hevcCodecString(hevcSps),
                    codec = "hevc",
                    vps = vps.copyOf()
                )
            }
            val sps = units.firstOrNull { nalType(it) == 7 } ?: return null
            val pps = units.firstOrNull { nalType(it) == 8 } ?: return null
            if (sps.size < 4) return null
            val codecString = String.format(
                Locale.US,
                "avc1.%02X%02X%02X",
                sps[1].toInt() and 0xff,
                sps[2].toInt() and 0xff,
                sps[3].toInt() and 0xff
            )
            return AvcStreamConfig(
                sps.copyOf(),
                pps.copyOf(),
                width,
                height,
                frameRate.coerceAtLeast(1),
                codecString
            )
        }

        private fun hevcNalType(nal: ByteArray): Int {
            return if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3f
        }

        /** RFC 6381 HEVC codec string，如 hvc1.1.6.L120.B0。 */
        private fun hevcCodecString(sps: ByteArray): String {
            val idc = sps[1].toInt() and 0x3f
            val compat = ((sps[2].toInt() and 0xff) shl 24) or
                ((sps[3].toInt() and 0xff) shl 16) or
                ((sps[4].toInt() and 0xff) shl 8) or
                (sps[5].toInt() and 0xff)
            val level = sps[12].toInt() and 0xff
            val sb = StringBuilder(String.format(Locale.US, "hvc1.%d.%X.L%d", idc, compat, level))
            var lastNonZero = -1
            for (i in 17 downTo 13) {
                if (sps[i].toInt() != 0) {
                    lastNonZero = i
                    break
                }
            }
            if (lastNonZero >= 13) {
                sb.append('.')
                for (i in 13..lastNonZero) {
                    sb.append(String.format(Locale.US, "%02X", sps[i].toInt() and 0xff))
                }
            }
            return sb.toString()
        }

        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val units = mutableListOf<ByteArray>()
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

        private fun nalType(nal: ByteArray): Int {
            return if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f
        }
    }

    // 标称单帧时长（90kHz 时间基下）。用 Long，因为输出时间线是累加的微秒级游标，
    // 与 Int 混用会在掉帧间隔/钳制运算上触发类型错误。
    private val nominalSampleDuration = max(1L, TIMESCALE.toLong() / config.frameRate)
    private var fragmentSequence = 1
    private var firstPresentationTimeUs: Long? = null

    // 致密输出时间线游标：每个 fragment 的 tfdt 严格等于上一 fragment 的 tfdt+duration，
    // 片段之间绝不留空隙。弱光/高负载下编码器会掉帧（实测 20fps 设定只出 ~17fps 且抖动），
    // 若直接用原始 pts 做 tfdt、duration 却写死成标称帧长，相邻 fragment 之间就会出现
    // 几十到上百毫秒的"时间空洞"，Chrome MSE 播放头走到空洞末尾会判定缓冲不可连续而
    // 停顿——表现为网页实时画面只显示第一帧就不动。因此这里自行重建一条无缝时间线。
    private var outputDecodeTime = 0L

    // 上一帧的原始时间戳（90kHz），用于推算本帧真实持续时间；首帧无差分，用标称帧长。
    private var lastRawDecodeTime: Long? = null

    fun initializationSegment(): ByteArray {
        return concat(fileTypeBox(), movieBox())
    }

    fun mediaFragment(packet: CameraStreamPacket): ByteArray? {
        val sample = toAvcSample(packet.data) ?: return null
        val firstPts = firstPresentationTimeUs ?: packet.presentationTimeUs.also {
            firstPresentationTimeUs = it
        }
        val rawDecodeTime = max(
            0L,
            (packet.presentationTimeUs - firstPts) * TIMESCALE / 1_000_000L
        )

        // 本帧持续时间取"与上一帧的真实时间差"，让播放节奏贴合实际采集帧率；
        // 钳制在 [半帧, 三帧] 内，抑制时间戳回跳/异常大间隔造成的频闪或卡顿。
        val previous = lastRawDecodeTime
        val duration = if (previous == null) {
            nominalSampleDuration
        } else {
            (rawDecodeTime - previous).coerceIn(
                nominalSampleDuration / 2L,
                nominalSampleDuration * 3L
            )
        }
        lastRawDecodeTime = rawDecodeTime

        val decodeTime = outputDecodeTime
        outputDecodeTime += duration

        val moofWithoutOffset = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            sample.size,
            packet.keyFrame,
            duration,
            0
        )
        val dataOffset = moofWithoutOffset.size + 8
        val moof = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            sample.size,
            packet.keyFrame,
            duration,
            dataOffset
        )
        fragmentSequence++
        return concat(moof, box("mdat", sample))
    }

    private fun toAvcSample(data: ByteArray): ByteArray? {
        // 管线输出理论上已是干净 AnnexB，但编码器形态随厂商而异（AVCC/AnnexB/嵌套/
        // 起始码分段内又带长度前缀的混合形态）。统一交给 NalUnits 递归展平后再包成
        // AVCC，并剥除参数集（已由 avcC/hvcC 提供）。早期只按"长度链 or 起始码"
        // 二选一，遇到混合形态会把残留长度前缀当 NAL 头，网页直播出第一帧后
        // SourceBuffer 再无合法数据、一直转圈。
        val codec = if (config.codec == "hevc") {
            NalUnits.Codec.HEVC
        } else {
            NalUnits.Codec.AVC
        }
        return NalUnits.toAvccSample(data, codec)?.bytes
    }

    private fun fileTypeBox(): ByteArray {
        return box("ftyp", bytes {
            writeType("isom")
            writeInt(0x00000200)
            writeType("isom")
            writeType("iso6")
            writeType(if (config.codec == "hevc") "hvc1" else "avc1")
            writeType("mp41")
        })
    }

    private fun movieBox(): ByteArray {
        return box(
            "moov",
            movieHeaderBox(),
            trackBox(),
            box("mvex", trackExtendsBox())
        )
    }

    private fun movieHeaderBox(): ByteArray {
        return fullBox("mvhd", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TIMESCALE)
            writeInt(0)
            writeInt(0x00010000)
            writeShort(0x0100)
            writeShort(0)
            writeInt(0)
            writeInt(0)
            writeIdentityMatrix()
            repeat(6) { writeInt(0) }
            writeInt(2)
        })
    }

    private fun trackBox(): ByteArray {
        return box(
            "trak",
            trackHeaderBox(),
            box(
                "mdia",
                mediaHeaderBox(),
                handlerBox(),
                mediaInformationBox()
            )
        )
    }

    private fun trackHeaderBox(): ByteArray {
        return fullBox("tkhd", 0, 0x000007, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TRACK_ID)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeIdentityMatrix()
            writeInt(config.width shl 16)
            writeInt(config.height shl 16)
        })
    }

    private fun mediaHeaderBox(): ByteArray {
        return fullBox("mdhd", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TIMESCALE)
            writeInt(0)
            writeShort(0x55c4) // ISO-639-2/T language code: und
            writeShort(0)
        })
    }

    private fun handlerBox(): ByteArray {
        return fullBox("hdlr", 0, 0, bytes {
            writeInt(0)
            writeType("vide")
            writeInt(0)
            writeInt(0)
            writeInt(0)
            write("VideoHandler\u0000".toByteArray(ASCII))
        })
    }

    private fun mediaInformationBox(): ByteArray {
        val videoMediaHeader = fullBox("vmhd", 0, 1, bytes {
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
        })
        val dataReference = fullBox("dref", 0, 0, bytes {
            writeInt(1)
            write(fullBox("url ", 0, 1, ByteArray(0)))
        })
        val dataInformation = box("dinf", dataReference)
        return box("minf", videoMediaHeader, dataInformation, sampleTableBox())
    }

    private fun sampleTableBox(): ByteArray {
        val sampleDescription = fullBox("stsd", 0, 0, bytes {
            writeInt(1)
            write(videoSampleEntry())
        })
        val timeToSample = fullBox("stts", 0, 0, bytes { writeInt(0) })
        val sampleToChunk = fullBox("stsc", 0, 0, bytes { writeInt(0) })
        val sampleSize = fullBox("stsz", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
        })
        val chunkOffset = fullBox("stco", 0, 0, bytes { writeInt(0) })
        return box(
            "stbl",
            sampleDescription,
            timeToSample,
            sampleToChunk,
            sampleSize,
            chunkOffset
        )
    }

    private fun videoSampleEntry(): ByteArray {
        val visualSampleEntry = bytes {
            write(ByteArray(6))
            writeShort(1)
            writeShort(0)
            writeShort(0)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeShort(config.width)
            writeShort(config.height)
            writeInt(0x00480000)
            writeInt(0x00480000)
            writeInt(0)
            writeShort(1)
            write(ByteArray(32))
            writeShort(0x0018)
            writeShort(0xffff)
        }
        return if (config.codec == "hevc") {
            box("hvc1", visualSampleEntry, hevcConfigurationBox())
        } else {
            box("avc1", visualSampleEntry, avcConfigurationBox())
        }
    }

    private fun hevcConfigurationBox(): ByteArray {
        val vps = config.vps
        val sps = config.sps
        val pps = config.pps
        return box("hvcC", bytes {
            writeByte(1) // configurationVersion
            writeByte(sps[1].toInt() and 0xff) // profile space/tier/idc
            write(sps.copyOfRange(2, 6)) // compatibility
            write(sps.copyOfRange(6, 12)) // constraint indicator
            writeByte(sps[12].toInt() and 0xff) // level
            writeShort(0xf000) // min_spatial_segmentation_idc
            writeByte(0xfc) // parallelismType
            writeByte(0xfd) // chromaFormat 4:2:0
            writeByte(0xf8) // bitDepthLumaMinus8
            writeByte(0xf8) // bitDepthChromaMinus8
            writeShort(0) // avgFrameRate
            writeByte(0x0f) // constantFrameRate=0, temporalLayers=1, nested=1, lengthSize=4
            writeByte(3) // numOfArrays
            fun array(nalType: Int, nal: ByteArray) {
                writeByte(0x80 or nalType)
                writeShort(1)
                writeShort(nal.size)
                write(nal)
            }
            array(32, vps)
            array(33, sps)
            array(34, pps)
        })
    }

    private fun avcConfigurationBox(): ByteArray {
        val sps = config.sps
        val pps = config.pps
        return box("avcC", bytes {
            writeByte(1)
            writeByte(sps[1].toInt() and 0xff)
            writeByte(sps[2].toInt() and 0xff)
            writeByte(sps[3].toInt() and 0xff)
            writeByte(0xff)
            writeByte(0xe1)
            writeShort(sps.size)
            write(sps)
            writeByte(1)
            writeShort(pps.size)
            write(pps)
        })
    }

    private fun trackExtendsBox(): ByteArray {
        return fullBox("trex", 0, 0, bytes {
            writeInt(TRACK_ID)
            writeInt(1)
            writeInt(nominalSampleDuration.toInt())
            writeInt(0)
            writeInt(0x01010000)
        })
    }

    private fun movieFragmentBox(
        sequence: Int,
        decodeTime: Long,
        sampleSize: Int,
        keyFrame: Boolean,
        sampleDur: Long,
        dataOffset: Int
    ): ByteArray {
        val movieFragmentHeader = fullBox("mfhd", 0, 0, bytes {
            writeInt(sequence)
        })
        val trackFragmentHeader = fullBox("tfhd", 0, 0x020000, bytes {
            writeInt(TRACK_ID)
        })
        val decodeTimeBox = fullBox("tfdt", 1, 0, bytes {
            writeLong(decodeTime)
        })
        val sampleFlags = if (keyFrame) 0x02000000 else 0x01010000
        val trackRun = fullBox("trun", 0, 0x000701, bytes {
            writeInt(1)
            writeInt(dataOffset)
            writeInt(sampleDur.toInt())
            writeInt(sampleSize)
            writeInt(sampleFlags)
        })
        return box(
            "moof",
            movieFragmentHeader,
            box("traf", trackFragmentHeader, decodeTimeBox, trackRun)
        )
    }

    private fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        return box(type, bytes {
            writeByte(version)
            writeByte((flags ushr 16) and 0xff)
            writeByte((flags ushr 8) and 0xff)
            writeByte(flags and 0xff)
            write(payload)
        })
    }

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payloadSize = payloads.sumOf { it.size }
        return bytes {
            writeInt(payloadSize + 8)
            writeType(type)
            payloads.forEach { write(it) }
        }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data -> data.block() }
        return output.toByteArray()
    }

    private fun concat(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(values.sumOf { it.size })
        values.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeType(type: String) {
        require(type.length == 4)
        write(type.toByteArray(ASCII))
    }

    private fun DataOutputStream.writeIdentityMatrix() {
        writeInt(0x00010000)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeInt(0x00010000)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeInt(0x40000000)
    }
}
