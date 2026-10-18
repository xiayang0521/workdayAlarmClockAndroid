package com.zyyme.storageserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 基于本地文件系统的存储（默认使用 App 专属目录，无需权限）。 */
public class FileStorage implements Storage {
    private final File root;

    public FileStorage(File root) {
        this.root = root;
        if (!root.exists()) root.mkdirs();
    }

    public File root() {
        return root;
    }

    private File resolve(String path) {
        String norm = Storage.normalize(path);
        return norm.isEmpty() ? root : new File(root, norm);
    }

    @Override
    public boolean exists(String path) {
        return resolve(path).exists();
    }

    @Override
    public boolean isDirectory(String path) {
        return resolve(path).isDirectory();
    }

    @Override
    public List<Entry> list(String path) {
        File dir = resolve(path);
        File[] files = dir.listFiles();
        List<Entry> out = new ArrayList<>();
        if (files == null) return out;
        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            Entry e = new Entry();
            e.name = f.getName();
            e.dir = f.isDirectory();
            e.size = f.length();
            e.modified = f.lastModified();
            out.add(e);
        }
        return out;
    }

    @Override
    public boolean mkdirs(String path) {
        return resolve(path).mkdirs() || resolve(path).isDirectory();
    }

    @Override
    public boolean delete(String path) {
        File f = resolve(path);
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null && children.length > 0) return false;
        }
        return f.delete();
    }

    @Override
    public boolean rename(String from, String to) {
        File src = resolve(from);
        File dst = resolve(to);
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return src.renameTo(dst);
    }

    @Override
    public long size(String path) {
        return resolve(path).length();
    }

    @Override
    public long modified(String path) {
        return resolve(path).lastModified();
    }

    @Override
    public OutputStream openWrite(String path) throws IOException {
        File f = resolve(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        return new FileOutputStream(f);
    }

    @Override
    public InputStream openRead(String path) throws IOException {
        return new FileInputStream(resolve(path));
    }
}
