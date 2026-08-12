package io.qzz.dfdvdsf.jarfile;

import javax.annotation.Nullable;
import java.io.File;

/**
 * A data class holding a file path and marking where it lives: inside a jar
 * (with the owning jar remembered) or as a plain local file. {@code equals}/
 * {@code hashCode} are path-based so the index sets deduplicate entries
 * naturally. <p />
 * 用于标记文件路径及其所在位置的数据类：位于 jar 内（并记住所属 jar 文件），
 * 或为普通本地文件。{@code equals}/{@code hashCode} 基于路径实现，
 * 使索引集天然去重。
 */
public final class UrlBuffered {

    private final boolean isJar;
    private final String filePath;
    private final File source;

    /**
     * Creates an entry for a local file. / 为本地文件创建条目。
     *
     * @param localFile the local file / 本地文件
     */
    UrlBuffered(File localFile) {
        this.isJar = false;
        this.filePath = localFile.getAbsolutePath();
        this.source = null;
    }

    /**
     * Creates an entry for a jar file's resource. / 为 jar 文件中的资源创建条目。
     *
     * @param sourceJar the jar holding the entry / 承载该条目的 jar
     * @param entryName the '/'-separated entry name / 以 '/' 分隔的条目名
     */
    UrlBuffered(File sourceJar, String entryName) {
        this.isJar = true;
        this.filePath = entryName;
        this.source = sourceJar;
    }

    /**
     * Returns the indexed file path: the '/'-separated entry name for jar
     * entries, the absolute disk path for local files.
     * <p>
     * 返回已索引的文件路径：jar 条目为以 '/' 分隔的条目名，
     * 本地文件为磁盘绝对路径。
     *
     * @return the file path / 文件路径
     */
    public String getFileUrl() {
        return filePath;
    }

    /**
     * Whether this entry lives inside a jar. / 该条目是否位于 jar 内。
     *
     * @return {@code true} for jar entries / jar 条目为 {@code true}
     */
    public boolean isJar() {
        return isJar;
    }

    /**
     * The jar this entry was indexed from, {@code null} for local files.
     * <p>
     * 该条目的索引来源 jar；本地文件为 {@code null}。
     *
     * @return the owning jar file, or {@code null} / 所属 jar 文件，或 {@code null}
     */
    @Nullable
    public File getSource() {
        return source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UrlBuffered)) {
            return false;
        }
        UrlBuffered that = (UrlBuffered) o;
        return isJar == that.isJar && filePath.equals(that.filePath);
    }

    @Override
    public int hashCode() {
        return 31 * (isJar ? 1 : 0) + filePath.hashCode();
    }

    @Override
    public String toString() {
        return (isJar ? "jar:" : "file:") + filePath;
    }
}
