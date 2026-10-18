package com.zyyme.workdayalarmclock.record

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.DocumentsContract
import java.io.File

/**
 * 通过 SAF（Storage Access Framework）访问用户授权的本机目录（通常是 OTG U 盘）。
 *
 * 之所以不直接写 /storage/xxxx 路径：Android 11+ 对可移动存储的裸路径访问不稳定，
 * 而 SAF 的 tree uri 可持久化授权、随拔插自动失效/恢复，更适合无人值守的监控设备。
 *
 * 所有方法在目录不可访问（U 盘未插入、授权丢失）时返回 null / false / 空列表，
 * 由上层 [UsbRecordSink] 按"拔盘重试"处理，不抛到录像主线程。
 */
internal class UsbRecordStore(
    context: Context,
    private val treeUri: String
) : UsbRecordTarget {
    private val resolver = context.applicationContext.contentResolver

    /** SAF 后端内部条目，[documentUri] 即对外 [UsbRecordTarget.RecordedFile.handle]。 */
    private data class UsbFile(
        val documentUri: Uri,
        val name: String,
        val size: Long
    )

    override fun isAvailable(): Boolean {
        val root = rootDocumentUri() ?: return false
        return try {
            resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 列出目录下的录像文件（仅文件、文件名符合录像规则），按文件名时间戳升序由上层再排序。
     */
    override fun listRecordings(): List<UsbRecordTarget.RecordedFile> {
        val childrenUri = childrenUri() ?: return emptyList()
        val result = ArrayList<UsbRecordTarget.RecordedFile>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val mime = if (mimeIndex >= 0) cursor.getString(mimeIndex) else null
                    if (name.isNullOrEmpty()) continue
                    // 目录的 mime 是标准目录类型；只保留普通文件，并过滤残留的 .part 临时文件。
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    if (name.endsWith(PART_SUFFIX)) continue
                    if (!RecordingNames.isRecording(name)) continue
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    if (id.isNullOrEmpty()) continue
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    result.add(
                        UsbRecordTarget.RecordedFile(
                            name = name,
                            size = size,
                            handle = DocumentsContract.buildDocumentUriUsingTree(uri(), id)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return result
    }

    /**
     * 复制本地录像到 USB。先写同名 .part，成功后在 API24+ 原子改名；
     * 低版本或改名失败时退化为直接写最终文件名。返回是否成功。
     */
    override fun copyIn(localFile: File): Boolean {
        if (!localFile.isFile) return false
        val finalName = localFile.name
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            copyViaPart(localFile, finalName)
        } else {
            directCopy(localFile, finalName)
        }
    }

    private fun copyViaPart(localFile: File, finalName: String): Boolean {
        val partName = finalName + PART_SUFFIX
        // 幂等清理：删除可能残留的 .part 以及上次补传留下的同名正式文件，
        // 避免 createDocument/renameDocument 被系统自动追加 "(1)" 产生重复录像。
        listOf(partName, finalName).forEach { stale ->
            findByName(stale)?.let { deleteUri(it.documentUri) }
        }
        val partUri = createFile(partName) ?: return directCopy(localFile, finalName)
        val written = try {
            resolver.openOutputStream(partUri, "w")?.use { output ->
                localFile.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_SIZE) }
            } != null
        } catch (_: Exception) {
            false
        }
        if (!written) {
            deleteUri(partUri)
            return false
        }
        return try {
            DocumentsContract.renameDocument(resolver, partUri, finalName) != null
        } catch (_: Exception) {
            // 改名不支持时，删掉 part 直接写最终文件，保证文件可用（牺牲原子性）。
            deleteUri(partUri)
            directCopy(localFile, finalName)
        }
    }

    private fun directCopy(localFile: File, finalName: String): Boolean {
        // 同名文件已存在（可能内容不完整）时先删，避免 createDocument 生成重复名。
        findByName(finalName)?.let { deleteUri(it.documentUri) }
        val target = createFile(finalName) ?: return false
        return try {
            resolver.openOutputStream(target, "w")?.use { output ->
                localFile.inputStream().use { input -> input.copyTo(output, COPY_BUFFER_SIZE) }
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /** 接口删除入口：handle 即 SAF document Uri。 */
    override fun delete(file: UsbRecordTarget.RecordedFile): Boolean {
        val uri = file.handle as? Uri ?: return false
        return deleteUri(uri)
    }

    /**
     * 返回该目录所在卷的可用字节数，取不到（非 externalstorage 提供者等）返回 null。
     * 配额"保留可用空间"模式依赖此值；为 null 时上层跳过该模式，不影响"占用上限"模式。
     */
    override fun availableBytes(): Long? {
        return try {
            val statFs = resolveStatFs() ?: return null
            statFs.availableBytes
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveStatFs(): StatFs? {
        // externalstorage 提供者的 tree documentId 形如 "XXXX-YYYY:相对目录" 或 "primary:目录"。
        val rootId = DocumentsContract.getTreeDocumentId(uri())
        val volumeId = rootId.substringBefore(':', missingDelimiterValue = rootId)
        val candidatePaths = when {
            volumeId.equals("primary", ignoreCase = true) ->
                listOf("/storage/emulated/0", "/sdcard")
            volumeId.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}.*")) ->
                listOf("/storage/$volumeId")
            else -> emptyList()
        }
        for (path in candidatePaths) {
            val file = File(path)
            if (file.exists()) {
                runCatching { return StatFs(path) }
            }
        }
        return null
    }

    private fun createFile(name: String): Uri? {
        val parent = rootDocumentUri() ?: return null
        return try {
            DocumentsContract.createDocument(resolver, parent, GENERIC_MIME, name)
        } catch (_: Exception) {
            null
        }
    }

    private fun findByName(name: String): UsbFile? {
        return listRecordingsRaw().firstOrNull { it.name == name }
    }

    private fun listRecordingsRaw(): List<UsbFile> {
        val childrenUri = childrenUri() ?: return emptyList()
        val result = ArrayList<UsbFile>()
        try {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = if (idIndex >= 0) cursor.getString(idIndex) else null
                    val fileName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    if (id.isNullOrEmpty() || fileName.isNullOrEmpty()) continue
                    result.add(UsbFile(DocumentsContract.buildDocumentUriUsingTree(uri(), id), fileName, 0L))
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun deleteUri(documentUri: Uri): Boolean {
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (_: Exception) {
            false
        }
    }

    private fun uri(): Uri = Uri.parse(treeUri)

    private fun rootDocumentUri(): Uri? {
        return try {
            val tree = uri()
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        } catch (_: Exception) {
            null
        }
    }

    private fun childrenUri(): Uri? {
        val root = rootDocumentUri() ?: return null
        return try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                uri(),
                DocumentsContract.getDocumentId(root)
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        internal const val PART_SUFFIX = UsbRecordTarget.PART_SUFFIX
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val GENERIC_MIME = "application/octet-stream"
    }
}
