package com.zyyme.storageserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 录像存储抽象：FTP/WebDAV 服务器都通过它读写文件。
 * 路径统一为相对根目录的 "a/b/c" 形式（"" 表示根目录）。
 */
public interface Storage {
    class Entry {
        public String name;
        public boolean dir;
        public long size;
        public long modified;
    }

    boolean exists(String path) throws IOException;

    boolean isDirectory(String path) throws IOException;

    List<Entry> list(String path) throws IOException;

    boolean mkdirs(String path) throws IOException;

    /** 删除文件或空目录。 */
    boolean delete(String path) throws IOException;

    boolean rename(String from, String to) throws IOException;

    long size(String path) throws IOException;

    long modified(String path) throws IOException;

    /** 创建/覆盖写入文件（父目录须已存在）。 */
    OutputStream openWrite(String path) throws IOException;

    InputStream openRead(String path) throws IOException;

    /** 路径规范化：去首尾斜杠、拒绝越出根目录的 ".."，返回 "" 表示根。 */
    static String normalize(String path) {
        if (path == null) return "";
        String p = path.trim().replace('\\', '/');
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.startsWith("/")) p = p.substring(1);
        String[] parts = p.split("/");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String seg : parts) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        return sb.toString();
    }
}
