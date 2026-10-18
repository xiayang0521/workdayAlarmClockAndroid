package com.zyyme.workdayalarmclock.record

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 录像动态标记：与每个 mp4 同目录同名加 .motion.json 副作用，
 * 记录"有画面变动"的时间区间（相对段起点的毫秒偏移），供回放界面在时间轴上标注。
 */
internal object MotionMarks {
    fun write(segmentFile: File, intervalsMs: List<LongArray>) {
        try {
            val array = JSONArray()
            for (range in intervalsMs) {
                if (range.size < 2 || range[1] <= range[0]) continue
                array.put(JSONArray().put(range[0]).put(range[1]))
            }
            val target = File(segmentFile.absolutePath + ".motion.json")
            if (array.length() == 0) {
                target.delete()
                return
            }
            target.writeText(JSONObject().put("motion", array).toString())
        } catch (_: Exception) {
        }
    }

    /** 删除录像文件时同步删除其动态标签副作用。 */
    fun deleteFor(segmentFile: File) {
        try {
            File(segmentFile.absolutePath + ".motion.json").delete()
        } catch (_: Exception) {
        }
    }

    fun readFor(segmentFile: File): List<LongArray> {
        return try {
            val source = File(segmentFile.absolutePath + ".motion.json")
            if (!source.isFile) return emptyList()
            val root = JSONObject(source.readText())
            val array = root.optJSONArray("motion") ?: return emptyList()
            val result = ArrayList<LongArray>()
            for (i in 0 until array.length()) {
                val pair = array.optJSONArray(i) ?: continue
                if (pair.length() < 2) continue
                result.add(longArrayOf(pair.getLong(0), pair.getLong(1)))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
