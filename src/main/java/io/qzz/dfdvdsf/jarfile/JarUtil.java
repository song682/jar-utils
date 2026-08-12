package io.qzz.dfdvdsf.jarfile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.InputStream;
import java.util.Collection;
import java.util.Set;

/**
 * Facade of the resource-indexing pipeline: scans the jars of the mods
 * directory and every locally scanned directory, maintains the two index sets,
 * and serves indexed files back as {@link InputStream}s or text content by
 * their indexed path. The actual work is delegated to single-purpose classes:
 * <ul>
 * <li>{@link JarScanner} — concurrent jar scanning / 并发 jar 扫描</li>
 * <li>{@link DirectoryScanner} — local directory scanning plus code-registered
 *     scan targets / 本地目录扫描与代码注册的扫描目标</li>
 * <li>{@link ResourceIndex} — the two index sets / 两个索引集</li>
 * <li>{@link IndexedFileReader} — reading indexed content back /
 *     按索引回读内容</li>
 * </ul>
 * Two index sets are maintained, both exposed as defensive copies:
 * <ul>
 * <li>{@link #getSet()} — all indexed files, jar entries and local files.</li>
 * <li>{@link #getDataSet()} — jar entries under the {@code data/} prefix plus
 * every locally scanned file.</li>
 * </ul>
 * Indexed jar entries remember the jar file they came from, so content is read
 * straight from that jar via {@link JarContents#openEntry(File, String)} —
 * never from the classpath — which keeps identically-named resources of
 * different jars independent.
 * <p>
 * This class is a pure JDK utility with no Minecraft dependencies; it only
 * reads jar files and local directories, and can be used by any mod as a
 * drop-in backup utility library.
 * <p>
 * 资源索引管线的门面：扫描 mods 目录下的 jar 与全部本地扫描目录，维护两个
 * 索引集，并按索引路径将文件以 {@link InputStream} 或文本内容的形式提供出去。
 * 实际工作委托给单一职责的专门类：
 * <ul>
 * <li>{@link JarScanner} —— 并发 jar 扫描</li>
 * <li>{@link DirectoryScanner} —— 本地目录扫描与代码注册的扫描目标</li>
 * <li>{@link ResourceIndex} —— 两个索引集</li>
 * <li>{@link IndexedFileReader} —— 按索引回读内容</li>
 * </ul>
 * 维护两个索引集，均以防御性拷贝的形式对外暴露：
 * <ul>
 * <li>{@link #getSet()} —— 全部已索引文件，含 jar 条目与本地文件。</li>
 * <li>{@link #getDataSet()} —— jar 中 {@code data/} 前缀下的条目，
 * 以及全部本地扫描文件。</li>
 * </ul>
 * 已索引的 jar 条目会记住其来源 jar 文件，读取时直接经由
 * {@link JarContents#openEntry(File, String)} 从该 jar 取内容——绝不走
 * classpath——从而保证不同 jar 中的同名资源彼此独立。
 * <p>
 * 本类为纯 JDK 工具，不依赖任何 Minecraft 类：只读取 jar 文件与本地目录，
 * 可作为任意 mod 直接调用的后备工具库。
 */
public final class JarUtil {

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|JarUtil");

    private JarUtil() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    // === === === Scanning / 扫描 === === ===

    /**
     * Clears both index sets and the registered directories. Package-private:
     * exposed for tests to isolate themselves and for a future re-scan flow
     * after a config reload.
     * <p>
     * 清空两个索引集与已注册目录。包私有：供测试隔离用例，以及未来配置重载后
     * 的重新扫描流程。
     */
    static void reset() {
        ResourceIndex.clear();
        DirectoryScanner.clear();
    }

    /**
     * Registers local directories to scan from code, e.g. a mod developer
     * calling this from its own pre-initialization handler. Registered
     * directories are scanned on top of whatever the config file says, so
     * developers can ship scan targets without asking players to edit the
     * config. Relative paths are resolved against the working directory;
     * re-registering the same directory is harmless — the set deduplicates.
     * <p>
     * 从代码注册需要扫描的本地目录，例如模组开发者在自身的预初始化处理器中
     * 调用。注册的目录会在配置文件指定目录之上叠加扫描，使开发者无需玩家
     * 修改配置即可内置扫描目标。相对路径基于工作目录解析；重复注册同一目录
     * 无副作用——集合按路径去重。
     *
     * @param dirs the directories to scan / 需要扫描的目录
     */
    public static void addScanDirectories(File... dirs) {
        DirectoryScanner.addScanDirectories(dirs);
    }

    /**
     * Scans the jars of the mods directory (when {@code modsDir} is non-null)
     * and every local directory — the given ones plus the ones registered via
     * {@link #addScanDirectories(File...)} — filling both index sets. Jar entries
     * that are directories or end with {@code .class}/{@code .png} are skipped;
     * local files are indexed regardless of suffix. Indexing the same file
     * twice is harmless — the sets deduplicate by path.
     * <p>
     * 扫描 mods 目录下的 jar（当 {@code modsDir} 非空时）与全部本地目录——
     * 给定的目录加上经 {@link #addScanDirectories(File...)} 注册的目录——
     * 填充两个索引集。目录条目及以 {@code .class}/{@code .png} 结尾的 jar 条目
     * 会被跳过；本地文件不论后缀一律索引。同一文件被重复索引无副作用——
     * 索引集按路径去重。
     *
     * @param modsDir   the mods directory whose jars are scanned, or {@code null}
     *                  to skip jar scanning / mods 目录，其下 jar 将被扫描；
     *                  {@code null} 表示跳过 jar 扫描
     * @param scanDirs  local directories to scan, relative paths are resolved
     *                  against the working directory / 需要扫描的本地目录，
     *                  相对路径基于工作目录解析
     * @param recursive whether to descend into subdirectories when scanning
     *                  local directories / 扫描本地目录时是否递归子目录
     */
    public static synchronized void scan(@Nullable File modsDir, Collection<File> scanDirs, boolean recursive) {
        JarScanner.scanJars(modsDir);
        DirectoryScanner.scanDirectories(scanDirs, recursive);
        LOGGER.info("Indexed {} files in total, {} under data", ResourceIndex.size(), ResourceIndex.dataSize());
    }

    // === === === Index access / 索引访问 === === ===

    /**
     * Returns a defensive copy of all indexed files.
     * <p>
     * 返回全部已索引文件的防御性拷贝。
     *
     * @return all indexed files, never {@code null} / 全部已索引文件，恒非 {@code null}
     */
    public static Set<UrlBuffered> getSet() {
        return ResourceIndex.getAll();
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
    public static Set<UrlBuffered> getDataSet() {
        return ResourceIndex.getData();
    }

    // === === === Reading / 读取 === === ===

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
        return IndexedFileReader.getInputStreamFromUrl(url);
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
        return IndexedFileReader.readBytesFromUrl(url);
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
        return IndexedFileReader.readFileFromUrl(url);
    }
}
