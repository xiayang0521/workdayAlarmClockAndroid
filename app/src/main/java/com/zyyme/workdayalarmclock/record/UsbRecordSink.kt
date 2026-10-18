package com.zyyme.workdayalarmclock.record

import android.content.Context
import com.zyyme.workdayalarmclock.MeSettings
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把本地分钟录像分段复制到用户授权的本机 USB 目录。
 *
 * 行为与 NAS 上传器（[RecordingUploadManager]）保持一致：
 * - 串行处理队列，复制成功后按相同的配额策略（保留可用空间 / 占用上限）删最旧录像；
 * - U 盘未插入 / 目录不可访问 / 复制失败时延迟重试，应用重启自动补传；
 * - 写 USB 走 .part 临时文件再改名，断电不会留下半截 mp4。
 *
 * 与 NAS 同时启用时，同一段录像两边都投递成功后才允许清理本地暂存（见 [SegmentDeliveryTracker]）。
 */
internal class UsbRecordSink(
    context: Context,
    private val localDir: File,
    private val tracker: SegmentDeliveryTracker,
    private val janitor: LocalCacheJanitor,
    private val log: (String) -> Unit
) {
    private val appContext: Context = context.applicationContext

    companion object {
        private const val RETRY_DELAY_MS = 15_000L
        private const val IDLE_POLL_MS = 10_000L
        private const val MAX_DELETE_PER_ROUND = 30
    }

    private val running = AtomicBoolean(false)
    private val pending = LinkedBlockingQueue<File>()
    private val queuedNames = HashSet<String>()
    private val lock = Any()
    private var workerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        localDir.mkdirs()
        enqueueExisting()
        workerThread = Thread({ workerLoop() }, "record-usb").apply { start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        workerThread?.interrupt()
        try {
            workerThread?.join(1_500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        workerThread = null
    }

    fun enqueue(file: File) {
        if (!running.get()) return
        synchronized(lock) {
            if (queuedNames.contains(file.name)) return
            queuedNames.add(file.name)
            pending.offer(file)
        }
    }

    /**
     * 设置变更后调用：若 USB 已启用，把暂存目录里尚未在队列中的录像登记交付目标并补入队列，
     * 让用户在 App 运行中才插入 U 盘 / 新开启 USB 时也能立即补传。
     */
    fun kick() {
        if (!running.get() || !MeSettings.isUsbRecordConfigured(appContext)) return
        val files = localDir.listFiles()?.toList().orEmpty()
            .filter { it.isFile && RecordingNames.isRecording(it.name) }
            .sortedBy { RecordingNames.timestampOf(it.name) }
        synchronized(lock) {
            files.forEach { file ->
                tracker.register(
                    file.name,
                    buildList {
                        if (MeSettings.isNasConfigured(appContext)) add(DeliveryTarget.NAS)
                        add(DeliveryTarget.USB)
                    }
                )
                if (queuedNames.add(file.name)) pending.offer(file)
            }
        }
    }

    private fun enqueueExisting() {
        // 重启补传：USB 启用时，把暂存目录里尚未投递的录像重新排队（重复复制会覆盖同名文件）。
        if (!MeSettings.isUsbRecordConfigured(appContext)) return
        val files = localDir.listFiles()?.toList().orEmpty()
            .filter { it.isFile && RecordingNames.isRecording(it.name) }
            .sortedBy { RecordingNames.timestampOf(it.name) }
        synchronized(lock) {
            files.forEach { file ->
                if (queuedNames.add(file.name)) pending.offer(file)
            }
        }
    }

    private fun workerLoop() {
        while (running.get()) {
            val file = try {
                pending.poll(IDLE_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            if (file == null) continue
            copyOne(file)
        }
    }

    private fun copyOne(file: File) {
        // USB 被关闭：移出队列，不做任何复制（保留在本地暂存，等下次开启时补传）。
        if (!MeSettings.isUsbRecordConfigured(appContext)) {
            synchronized(lock) { queuedNames.remove(file.name) }
            return
        }
        if (!file.exists() || !file.isFile) {
            synchronized(lock) { queuedNames.remove(file.name) }
            return
        }
        val store = UsbRecordTargets.create(appContext)
        if (store == null) {
            requeue(file)
            return
        }
        if (!store.isAvailable()) {
            if (running.get()) log("USB 存储未就绪，等待存储接入后重试")
            requeue(file)
            return
        }
        val success = try {
            store.copyIn(file)
        } catch (e: Exception) {
            log("复制录像到 USB 失败：${e.message}")
            false
        }
        if (!success) {
            requeue(file)
            return
        }
        synchronized(lock) { queuedNames.remove(file.name) }
        tracker.markDone(file.name, DeliveryTarget.USB)
        try {
            enforceUsbQuota(store)
        } catch (e: Exception) {
            log("USB 空间清理异常：${e.message}")
        }
        val cacheBytes = MeSettings.getLocalCacheMb(appContext).toLong() * 1024L * 1024L
        janitor.enforce(tracker, cacheBytes)
    }

    /**
     * USB 侧配额，策略与 NAS 完全一致：
     * 占用上限模式按录像总大小删最旧；保留可用空间模式按卷可用空间删最旧。
     */
    private fun enforceUsbQuota(store: UsbRecordTarget) {
        val recordings = store.listRecordings()
            .sortedBy { RecordingNames.timestampOf(it.name) }
        if (recordings.isEmpty()) return

        val mode = MeSettings.getQuotaMode(appContext)
        if (mode == MeSettings.QUOTA_MODE_LIMIT) {
            val limitBytes = MeSettings.getQuotaLimitMb(appContext).toLong() * 1024L * 1024L
            var used = recordings.sumOf { it.size }
            var deleted = 0
            for (file in recordings) {
                if (used <= limitBytes || deleted >= MAX_DELETE_PER_ROUND) break
                if (store.delete(file)) {
                    used -= file.size
                    deleted++
                    log("USB空间清理：已删除最早录像 ${file.name}")
                }
            }
            return
        }

        val reserveBytes = MeSettings.getQuotaReserveMb(appContext).toLong() * 1024L * 1024L
        val available = store.availableBytes()
        if (available == null) {
            // 无法读取卷可用空间时，保留空间模式无从判断，跳过（不影响占用上限模式）。
            return
        }
        var availableNow = available
        var deleted = 0
        for (file in recordings) {
            if (availableNow >= reserveBytes || deleted >= MAX_DELETE_PER_ROUND) break
            if (store.delete(file)) {
                availableNow += file.size
                deleted++
                log("USB空间清理：已删除最早录像 ${file.name}")
            }
        }
    }

    private fun requeue(file: File) {
        if (!running.get()) {
            synchronized(lock) { queuedNames.remove(file.name) }
            return
        }
        Thread({
            if (sleep(RETRY_DELAY_MS) && running.get() && file.exists()) {
                synchronized(lock) {
                    if (MeSettings.isUsbRecordConfigured(appContext) &&
                        queuedNames.add(file.name)
                    ) {
                        pending.offer(file)
                    } else {
                        queuedNames.remove(file.name)
                    }
                }
            } else {
                synchronized(lock) { queuedNames.remove(file.name) }
            }
        }, "record-usb-retry").apply { isDaemon = true }.start()
    }

    private fun sleep(durationMs: Long): Boolean {
        return try {
            Thread.sleep(durationMs)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }
}
