package com.zyyme.storageserver;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SAF（Storage Access Framework）目录树的存储，
 * 用于把录像保存到用户通过系统目录选择器指定的位置（如 SD 卡）。
 */
public class SafStorage implements Storage {
    private final Context context;
    private final Uri treeUri;

    public SafStorage(Context context, Uri treeUri) {
        this.context = context.getApplicationContext();
        this.treeUri = treeUri;
    }

    private ContentResolver resolver() {
        return context.getContentResolver();
    }

    private String rootDocId() {
        return DocumentsContract.getTreeDocumentId(treeUri);
    }

    /** 解析路径为 documentId；不存在时返回 null。 */
    private String findDocId(String path) throws IOException {
        String norm = Storage.normalize(path);
        String current = rootDocId();
        if (norm.isEmpty()) return current;
        for (String seg : norm.split("/")) {
            current = findChild(current, seg);
            if (current == null) return null;
        }
        return current;
    }

    private String findChild(String parentDocId, String name) throws IOException {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, parentDocId);
        Cursor c = resolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (c == null) return null;
        try {
            while (c.moveToNext()) {
                if (name.equals(c.getString(1))) return c.getString(0);
            }
            return null;
        } finally {
            c.close();
        }
    }

    /** 逐段创建目录，返回末段 documentId。 */
    private String mkdirsInternal(String path) throws IOException {
        String norm = Storage.normalize(path);
        String current = rootDocId();
        if (norm.isEmpty()) return current;
        for (String seg : norm.split("/")) {
            String child = findChild(current, seg);
            if (child == null) {
                Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, current);
                Uri created = DocumentsContract.createDocument(resolver(), parentUri,
                        DocumentsContract.Document.MIME_TYPE_DIR, seg);
                if (created == null) throw new IOException("无法创建目录 " + seg);
                child = DocumentsContract.getDocumentId(created);
            }
            current = child;
        }
        return current;
    }

    @Override
    public boolean exists(String path) throws IOException {
        return findDocId(path) != null;
    }

    @Override
    public boolean isDirectory(String path) throws IOException {
        String docId = findDocId(path);
        if (docId == null) return false;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        Cursor c = resolver().query(uri, new String[]{
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
        if (c == null) return false;
        try {
            if (!c.moveToFirst()) return false;
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
        } finally {
            c.close();
        }
    }

    @Override
    public List<Entry> list(String path) throws IOException {
        String docId = findDocId(path);
        List<Entry> out = new ArrayList<>();
        if (docId == null) return out;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        Cursor c = resolver().query(childrenUri, new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
        if (c == null) return out;
        try {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.name = c.getString(0);
                e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1));
                e.size = c.isNull(2) ? 0 : c.getLong(2);
                e.modified = c.isNull(3) ? 0 : c.getLong(3);
                out.add(e);
            }
            return out;
        } finally {
            c.close();
        }
    }

    @Override
    public boolean mkdirs(String path) throws IOException {
        try {
            mkdirsInternal(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean delete(String path) throws IOException {
        String docId = findDocId(path);
        if (docId == null) return false;
        // 非空目录不允许删除
        if (isDirectory(path) && !list(path).isEmpty()) return false;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        return DocumentsContract.deleteDocument(resolver(), uri);
    }

    @Override
    public boolean rename(String from, String to) throws IOException {
        String srcId = findDocId(from);
        if (srcId == null) return false;
        String toNorm = Storage.normalize(to);
        int slash = toNorm.lastIndexOf('/');
        String parentPath = slash >= 0 ? toNorm.substring(0, slash) : "";
        String newName = slash >= 0 ? toNorm.substring(slash + 1) : toNorm;
        String parentId = mkdirsInternal(parentPath);
        Uri srcUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, srcId);
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
        Uri renamed = DocumentsContract.renameDocument(resolver(), srcUri, newName);
        return renamed != null;
    }

    @Override
    public long size(String path) throws IOException {
        String docId = findDocId(path);
        if (docId == null) return 0;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        Cursor c = resolver().query(uri, new String[]{
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
        if (c == null) return 0;
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    @Override
    public long modified(String path) throws IOException {
        String docId = findDocId(path);
        if (docId == null) return 0;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        Cursor c = resolver().query(uri, new String[]{
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null);
        if (c == null) return 0;
        try {
            return c.moveToFirst() && !c.isNull(0) ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        String norm = Storage.normalize(path);
        int slash = norm.lastIndexOf('/');
        String parentPath = slash >= 0 ? norm.substring(0, slash) : "";
        String name = slash >= 0 ? norm.substring(slash + 1) : norm;
        String parentId = mkdirsInternal(parentPath);
        String existing = findChild(parentId, name);
        Uri docUri;
        if (existing != null) {
            docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existing);
        } else {
            Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId);
            docUri = DocumentsContract.createDocument(resolver(), parentUri,
                    "application/octet-stream", name);
            if (docUri == null) throw new IOException("无法创建文件 " + name);
        }
        OutputStream out = resolver().openOutputStream(docUri, "wt");
        if (out == null) throw new IOException("无法打开文件写入 " + name);
        return out;
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        String docId = findDocId(path);
        if (docId == null) throw new IOException("文件不存在: " + path);
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        InputStream in = resolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开文件读取 " + path);
        return in;
    }
}
