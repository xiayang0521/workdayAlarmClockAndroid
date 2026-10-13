package com.zyyme.workdayalarmclock.record

/**
 * 供局域网 HTTP 服务浏览/播放/下载录像的数据访问抽象。
 */
internal interface RecordingAccess {
    fun listRecordings(): List<RecordingInfo>

    fun openForRead(name: String, rangeHeader: String?): RecordingReadHandle?
}
