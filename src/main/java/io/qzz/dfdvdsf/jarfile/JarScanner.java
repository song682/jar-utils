package io.qzz.dfdvdsf.jarfile;

import io.qzz.dfdvdsf.concurrent.Parallel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the jars of a directory and indexes their non-{@code .class}/
 * non-{@code .png} file entries into {@link ResourceIndex}. Jars are opened
 * concurrently via {@link Parallel}, each jar indexing itself in isolation —
 * the results are merged into the shared index afterwards, keeping the plain
 * {@code HashSet}s of {@link ResourceIndex} free of concurrent writes.
 * <p>
 * 扫描目录下的 jar，将其非 {@code .class}/{@code .png} 的文件条目索引进
 * {@link ResourceIndex}。jar 经 {@link Parallel} 并发打开，每个 jar 独立索引，
 * 结果事后合并进共享索引——避免对 {@link ResourceIndex} 中的普通
 * {@code HashSet} 产生并发写入。
 */
public final class JarScanner {

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|JarScanner");

    private static final String CLASS_SUFFIX = ".class";
    private static final String PNG_SUFFIX = ".png";

    private JarScanner() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Opens every jar under the mods directory concurrently and merges their
     * indexed entries into {@link ResourceIndex}. / 并发打开 mods 目录下的
     * 全部 jar，将其索引条目合并进 {@link ResourceIndex}。
     *
     * @param modsDir the mods directory, or {@code null} / mods 目录，可为 {@code null}
     */
    public static void scanJars(@Nullable File modsDir) {
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
            ResourceIndex.addJarEntries(entries);
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
    public static List<UrlBuffered> indexJar(File jar) {
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
}
