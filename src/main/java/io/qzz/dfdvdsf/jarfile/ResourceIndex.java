package io.qzz.dfdvdsf.jarfile;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Owns the two index sets of the scanning pipeline — the full set of every
 * indexed file and the data subset of jar entries under the {@code data/}
 * prefix plus all locally scanned files — and serves them back as defensive
 * copies. All mutating access is {@code synchronized}: the sets are plain
 * {@link HashSet}s, so writers from concurrent jar scanning and directory
 * walking must go through this class.
 * <p>
 * 持有扫描管线的两个索引集——全部已索引文件的完整集，以及 {@code data/}
 * 前缀下的 jar 条目加全部本地扫描文件组成的 data 子集——并以防御性拷贝的
 * 形式对外提供。所有写操作均加 {@code synchronized}：索引集为普通
 * {@link HashSet}，并发 jar 扫描与目录遍历的写入方都必须经由本类。
 */
public final class ResourceIndex {

    private static final String DATA_PREFIX = "data/";

    /**
     * All indexed files: jar entries without {@code .class}/{@code .png} suffix
     * plus every locally scanned file. / 全部已索引文件：无 {@code .class}/
     * {@code .png} 后缀的 jar 条目，以及全部本地扫描文件。
     */
    private static final Set<UrlBuffered> URL_LIST = new HashSet<UrlBuffered>();

    /**
     * Jar entries under the {@code data/} prefix plus every locally scanned
     * file. / {@code data/} 前缀下的 jar 条目，以及全部本地扫描文件。
     */
    private static final Set<UrlBuffered> DATA_LIST = new HashSet<UrlBuffered>();

    private ResourceIndex() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Merges a batch of jar entries into both index sets; entries under the
     * {@code data/} prefix additionally land in the data set. Deduplication is
     * path-based, so re-adding the same entries is harmless.
     * <p>
     * 将一批 jar 条目合并进两个索引集；{@code data/} 前缀下的条目额外进入
     * data 集。去重基于路径，重复添加相同条目无副作用。
     *
     * @param entries the jar entries to index / 待索引的 jar 条目
     */
    public static synchronized void addJarEntries(Collection<UrlBuffered> entries) {
        for (UrlBuffered entry : entries) {
            URL_LIST.add(entry);
            if (entry.getFileUrl().startsWith(DATA_PREFIX)) {
                DATA_LIST.add(entry);
            }
        }
    }

    /**
     * Adds a locally scanned file to both index sets. / 将本地扫描文件加入两个索引集。
     *
     * @param entry the local file entry / 本地文件条目
     */
    public static synchronized void addLocalFile(UrlBuffered entry) {
        URL_LIST.add(entry);
        DATA_LIST.add(entry);
    }

    /**
     * Returns a defensive copy of all indexed files.
     * <p>
     * 返回全部已索引文件的防御性拷贝。
     *
     * @return all indexed files, never {@code null} / 全部已索引文件，恒非 {@code null}
     */
    public static synchronized Set<UrlBuffered> getAll() {
        return new HashSet<UrlBuffered>(URL_LIST);
    }

    /**
     * Returns a defensive copy of the data index: jar entries under the
     * {@code data/} prefix plus every locally scanned file.
     * <p>
     * 返回 data 索引的防御性拷贝：{@code data/} 前缀下的 jar 条目，
     * 以及全部本地扫描文件。
     *
     * @return the data files, never {@code null} / data 文件，恒非 {@code null}
     */
    public static synchronized Set<UrlBuffered> getData() {
        return new HashSet<UrlBuffered>(DATA_LIST);
    }

    /**
     * Returns the number of all indexed files. / 返回全部已索引文件的数量。
     *
     * @return the full index size / 完整索引大小
     */
    public static synchronized int size() {
        return URL_LIST.size();
    }

    /**
     * Returns the number of indexed data files. / 返回已索引 data 文件的数量。
     *
     * @return the data index size / data 索引大小
     */
    public static synchronized int dataSize() {
        return DATA_LIST.size();
    }

    /**
     * Clears both index sets. Package-private: exposed for tests to isolate
     * themselves and for a future re-scan flow after a config reload.
     * <p>
     * 清空两个索引集。包私有：供测试隔离用例，以及未来配置重载后的重新扫描流程。
     */
    static synchronized void clear() {
        URL_LIST.clear();
        DATA_LIST.clear();
    }
}
