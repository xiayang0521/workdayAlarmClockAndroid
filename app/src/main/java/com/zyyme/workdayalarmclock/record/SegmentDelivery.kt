package com.zyyme.workdayalarmclock.record

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 一段分钟录像的投递目的地。
 */
internal enum class DeliveryTarget { NAS, USB }

/**
 * 记录每个分段需要投递到哪些目的地、以及各自是否已完成。
 *
 * 同一段录像可同时发 NAS 和本机 USB；只有"该分段要求的所有目的地都完成"后，
 * 本地暂存文件才允许被缓存上限清理删除——避免某一目的地失败时提前丢失原件。
 */
internal class SegmentDeliveryTracker {
    private class State {
        val required: MutableSet<DeliveryTarget> = HashSet()
        val done: MutableSet<DeliveryTarget> = HashSet()
    }

    private val states = ConcurrentHashMap<String, State>()

    /** 注册一个分段所需的投递目的地（重复注册取并集，不覆盖已完成状态）。 */
    fun register(key: String, targets: Collection<DeliveryTarget>) {
        if (targets.isEmpty()) return
        val state = states.getOrPut(key) { State() }
        synchronized(state) {
            state.required.addAll(targets)
        }
    }

    fun requires(key: String, target: DeliveryTarget): Boolean {
        val state = states[key] ?: return false
        return synchronized(state) { state.required.contains(target) }
    }

    fun markDone(key: String, target: DeliveryTarget) {
        val state = states.getOrPut(key) { State() }
        synchronized(state) {
            state.required.add(target)
            state.done.add(target)
        }
    }

    fun isFullyDelivered(key: String): Boolean {
        val state = states[key] ?: return false
        return synchronized(state) { state.done.containsAll(state.required) && state.required.isNotEmpty() }
    }

    fun forget(key: String) {
        states.remove(key)
    }
}

/**
 * 本地暂存目录的容量清理：超过上限时，仅删除"所有要求的目的地都已投递完成"的最旧录像。
 * 与 NAS 上的配额策略共用同一组缓存上限设置，但删除门槛是双目的地都完成。
 */
internal class LocalCacheJanitor(
    private val localDir: File,
    private val log: (String) -> Unit
) {
    fun enforce(tracker: SegmentDeliveryTracker, cacheBytes: Long) {
        try {
            val files = localDir.listFiles()
                ?.filter { it.isFile && RecordingNames.isRecording(it.name) }
                ?.sortedBy { RecordingNames.timestampOf(it.name) }
                ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= cacheBytes) break
                if (!tracker.isFullyDelivered(file.name)) continue
                val length = file.length()
                if (file.delete()) {
                    MotionMarks.deleteFor(file)
                    tracker.forget(file.name)
                    total -= length
                }
            }
        } catch (e: Exception) {
            log("本地缓存清理失败：${e.message}")
        }
    }
}
