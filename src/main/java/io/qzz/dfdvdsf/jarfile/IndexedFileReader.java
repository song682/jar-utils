package io.qzz.dfdvdsf.jarfile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

/**
 * Reads the content of files indexed by the scanning pipeline back from their
 * source: jar entries are opened straight from the jar they were indexed from
 * via {@link JarContents#openEntry(File, String)} — never from the classpath —
 * which keeps identically-named resources of different jars independent; local
 * files are opened from disk. When a file can no longer be opened, an
 * {@link ObjectNotFindException} is printed and {@code null} is returned.
 * <p>
 * 回读扫描管线已索引文件的内容：jar 条目直接从其索引来源的 jar 打开——
 * 经 {@link JarContents#openEntry(File, String)}，绝不走 classpath——从而保证
 * 不同 jar 中的同名资源彼此独立；本地文件从磁盘打开。当文件无法再被打开时，
 * 打印 {@link ObjectNotFindException} 并返回 {@code null}。
 */
public final class IndexedFileReader {

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|IndexedFileReader");

    private IndexedFileReader() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Opens a stream over an indexed file. Jar entries are read straight from
     * the jar they were indexed from; local files from disk. When the file can
     * no longer be opened — jar removed, local file deleted — an
     * {@link ObjectNotFindException} is printed and {@code null} is returned.
     * <p>
     * 打开已索引文件的输入流。jar 条目直接从其索引来源的 jar 读取；
     * 本地文件从磁盘读取。当文件无法再被打开——jar 被移除、本地文件被删除——
     * 打印 {@link ObjectNotFindException} 并返回 {@code null}。
     *
     * @param url the indexed file / 已索引的文件
     * @return the file's stream, or {@code null} if it cannot be opened
     *         （文件的输入流；无法打开时返回 {@code null}）
     */
    @Nullable
    public static InputStream getInputStreamFromUrl(UrlBuffered url) {
        if (url == null) {
            return null;
        }
        InputStream in = null;
        if (url.isJar()) {
            in = JarContents.openEntry(url.getSource(), url.getFileUrl());
        } else {
            File file = new File(url.getFileUrl());
            if (file.isFile()) {
                try {
                    in = new FileInputStream(file);
                } catch (IOException e) {
                    // Fall through and report as missing. / 落入下方统一按缺失处理。
                }
            }
        }
        if (in == null) {
            new ObjectNotFindException(url.getFileUrl()).printStackTrace();
        }
        return in;
    }

    /**
     * Reads an indexed file's full content into a byte array.
     * <p>
     * 将已索引文件的完整内容读入字节数组。
     *
     * @param url the indexed file / 已索引的文件
     * @return the content bytes, or {@code null} if missing or unreadable
     *         （内容字节；缺失或读取失败时返回 {@code null}）
     */
    @Nullable
    public static byte[] readBytesFromUrl(UrlBuffered url) {
        InputStream in = getInputStreamFromUrl(url);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.error("Failed to read file from {}", url, e);
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Best effort; nothing sensible to do on close failure.
                // 关闭失败时仅尽力而为，无进一步处理。
            }
        }
    }

    /**
     * Reads an indexed file's full content as a UTF-8 string.
     * <p>
     * 以 UTF-8 编码读取已索引文件的完整文本内容。
     *
     * @param url the indexed file / 已索引的文件
     * @return the text content, or {@code null} if missing or unreadable
     *         （文本内容；缺失或读取失败时返回 {@code null}）
     */
    @Nullable
    public static String readFileFromUrl(UrlBuffered url) {
        byte[] bytes = readBytesFromUrl(url);
        if (bytes == null) {
            return null;
        }
        try {
            return new String(bytes, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is mandated by the JLS; unreachable in practice.
            // JLS 规定 UTF-8 必然受支持，此分支实际不可达。
            return new String(bytes);
        }
    }
}
