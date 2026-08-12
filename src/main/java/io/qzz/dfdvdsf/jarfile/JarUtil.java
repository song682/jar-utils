package io.qzz.dfdvdsf.jarfile;

import io.qzz.dfdvdsf.concurrent.Parallel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Indexes the resource files of a modded environment — every non-{@code .class}
 * / non-{@code .png} entry of the jars in the mods directory, plus every file
 * under the locally scanned directories (a {@code data} directory by default,
 * configurable via {@code JarUtilsConfig}) — and later serves those files back
 * as {@link InputStream}s or text content by their indexed path.
 * <p>
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
 * 索引模组环境中的资源文件——mods 目录下各 jar 中除 {@code .class}、{@code .png}
 * 外的全部条目，以及本地扫描目录（默认 {@code data} 目录，可通过
 * {@code JarUtilsConfig} 配置）下的全部文件——之后按索引路径将文件以
 * {@link InputStream} 或文本内容的形式提供出去。
 * <p>
 * 扫描目标有三类来源，按需叠加：mods 目录下的 jar（可通过
 * {@code JarUtilsConfig#scanModsJars} 关闭）、配置文件指定的本地目录
 * （{@code JarUtilsConfig#scanDirectories}，默认为空）、以及开发者通过
 * {@link #addScanDirectories(File...)} 在代码中注册的本地目录——后者让
 * 模组开发者无需玩家改配置即可内置扫描目标。
 * <p>
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

    private static final String CLASS_SUFFIX = ".class";
    private static final String PNG_SUFFIX = ".png";
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

    /**
     * Local directories registered from code via {@link #addScanDirectories(File...)};
     * scanned on top of whatever the config file says.
     * <p>
     * 通过 {@link #addScanDirectories(File...)} 从代码注册的本地目录；
     * 在配置文件指定目录之上叠加扫描。
     */
    private static final Set<File> REGISTERED_DIRS = new HashSet<File>();

    private JarUtil() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    // === === === Scanning / 扫描 === === ===

    /**
     * Clears both index sets. Package-private: exposed for tests to isolate
     * themselves and for a future re-scan flow after a config reload.
     * <p>
     * 清空两个索引集。包私有：供测试隔离用例，以及未来配置重载后的重新扫描流程。
     */
    static void reset() {
        URL_LIST.clear();
        DATA_LIST.clear();
        REGISTERED_DIRS.clear();
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
    public static synchronized void addScanDirectories(File... dirs) {
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            if (dir != null) {
                REGISTERED_DIRS.add(dir.getAbsoluteFile());
            }
        }
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
        scanJars(modsDir);
        Set<File> dirs = new HashSet<File>(REGISTERED_DIRS);
        if (scanDirs != null) {
            for (File dir : scanDirs) {
                if (dir != null) {
                    dirs.add(dir.getAbsoluteFile());
                }
            }
        }
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                scanDirectory(dir, recursive, URL_LIST);
                scanDirectory(dir, recursive, DATA_LIST);
            } else {
                LOGGER.warn("Scan directory does not exist or is not a directory: {}", dir);
            }
        }
        LOGGER.info("Indexed {} files in total, {} under data", URL_LIST.size(), DATA_LIST.size());
    }

    /**
     * Opens every jar under the mods directory concurrently and merges their
     * indexed entries. / 并发打开 mods 目录下的全部 jar，合并其索引条目。
     *
     * @param modsDir the mods directory, or {@code null} / mods 目录，可为 {@code null}
     */
    private static void scanJars(@Nullable File modsDir) {
        if (modsDir == null || !modsDir.isDirectory()) {
            return;
        }
        File[] children = modsDir.listFiles();
        if (children == null) {
            return;
        }
        List<File> jars = new ArrayList<File>();
        for (File child : children) {
            if (JarNames.isJarFile(child)) {
                jars.add(child);
            }
        }
        if (jars.isEmpty()) {
            return;
        }
        // Each jar indexes itself, results are merged afterwards: the index sets
        // are not thread-safe. / 每个 jar 独立索引，事后合并结果：索引集并非线程安全。
        List<List<UrlBuffered>> perJar = Parallel.map(jars, new Function<File, List<UrlBuffered>>() {
            @Override
            public List<UrlBuffered> apply(File jar) {
                return indexJar(jar);
            }
        });
        for (List<UrlBuffered> entries : perJar) {
            for (UrlBuffered entry : entries) {
                URL_LIST.add(entry);
                if (entry.filePath.startsWith(DATA_PREFIX)) {
                    DATA_LIST.add(entry);
                }
            }
        }
    }

    /**
     * Indexes the file entries of a single jar: directories and
     * {@code .class}/{@code .png} entries are skipped, the rest is returned.
     * <p>
     * 索引单个 jar 的文件条目：跳过目录及 {@code .class}/{@code .png} 条目，
     * 其余条目被返回。
     *
     * @param jar the jar file to index / 待索引的 jar 文件
     * @return the indexed entries, never {@code null} / 索引到的条目，恒非 {@code null}
     */
    private static List<UrlBuffered> indexJar(File jar) {
        List<UrlBuffered> entries = new ArrayList<UrlBuffered>();
        JarFile jarFile = null;
        try {
            jarFile = new JarFile(jar);
            Enumeration<JarEntry> en = jarFile.entries();
            while (en.hasMoreElements()) {
                JarEntry entry = en.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.endsWith(CLASS_SUFFIX) || name.endsWith(PNG_SUFFIX)) {
                    continue;
                }
                entries.add(new UrlBuffered(jar, name));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to index jar {}", jar, e);
        } finally {
            if (jarFile != null) {
                try {
                    jarFile.close();
                } catch (IOException ignored) {
                    // Best effort; nothing sensible to do on close failure.
                    // 关闭失败时仅尽力而为，无进一步处理。
                }
            }
        }
        return entries;
    }

    /**
     * Recursively walks a local directory and indexes every file into the
     * given set. / 递归遍历本地目录，将每个文件索引到给定集合。
     *
     * @param dir       the directory to walk / 待遍历的目录
     * @param recursive whether to descend into subdirectories / 是否递归子目录
     * @param target    the index set to fill / 待填充的索引集
     */
    private static void scanDirectory(File dir, boolean recursive, Set<UrlBuffered> target) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (recursive) {
                    scanDirectory(child, recursive, target);
                }
            } else if (child.isFile()) {
                target.add(new UrlBuffered(child));
            }
        }
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
    public static Set<UrlBuffered> getDataSet() {
        return new HashSet<UrlBuffered>(DATA_LIST);
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
        if (url == null) {
            return null;
        }
        InputStream in = null;
        if (url.isJar) {
            in = JarContents.openEntry(url.source, url.filePath);
        } else {
            File file = new File(url.filePath);
            if (file.isFile()) {
                try {
                    in = new FileInputStream(file);
                } catch (IOException e) {
                    // Fall through and report as missing. / 落入下方统一按缺失处理。
                }
            }
        }
        if (in == null) {
            new ObjectNotFindException(url.filePath).printStackTrace();
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

    /**
     * A data class holding a file path and marking where it lives: inside a jar
     * (with the owning jar remembered) or as a plain local file. {@code equals}/
     * {@code hashCode} are path-based so the index sets deduplicate entries
     * naturally. <p />
     * 用于标记文件路径及其所在位置的数据类：位于 jar 内（并记住所属 jar 文件），
     * 或为普通本地文件。{@code equals}/{@code hashCode} 基于路径实现，
     * 使索引集天然去重。
     */
    public static final class UrlBuffered {

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
}
