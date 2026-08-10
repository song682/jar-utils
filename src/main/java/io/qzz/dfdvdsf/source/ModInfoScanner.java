package io.qzz.dfdvdsf.source;

import io.qzz.dfdvdsf.jarfile.JarNames;
import io.qzz.dfdvdsf.jarinjar.JarInJar;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recursively scans a container jar for mod candidates: the container itself
 * plus every jar nested inside it — jar-in-jar, by convention under
 * {@code META-INF/jarjar/} — and guesses the mod metadata of each one via
 * {@link ModInfoGuesser#guessJar(File)}.
 * <p>
 * Nested jars are extracted into a cache directory (SHA-256 deduplicated,
 * exactly as {@link JarInJar} does) and the scan descends level by level, so
 * deeply nested mods are found too. A jar is never scanned twice (same content
 * always resolves to the same hash-named cache file), which also breaks
 * content cycles. Each {@link ScanResult} carries the display name of the jar
 * — the outer file name, or the full entry name for nested jars — so results
 * are traceable back to their location in the container.
 * <p>
 * This class is a pure JDK utility with no Minecraft dependencies: it only
 * reads jar files and writes extracted copies into the cache directory.
 * <p>
 * 递归扫描容器 jar 中的模组候选：容器本身，以及所有内嵌于其中的 jar——
 * jar-in-jar，按约定位于 {@code META-INF/jarjar/} 下——并借助
 * {@link ModInfoGuesser#guessJar(File)} 逐个猜测模组元数据。
 * <p>
 * 嵌套 jar 会被提取到缓存目录（SHA-256 去重，与 {@link JarInJar} 完全一致），
 * 扫描逐层下探，因此深层嵌套的模组也能被发现。同一 jar 只会被扫描一次
 * （相同内容必然解析为同名哈希缓存文件），内容环也因此被截断。每个
 * {@link ScanResult} 都携带 jar 的显示名——外层为文件名，嵌套层为完整条目名——
 * 使结果可回溯到其在容器中的位置。
 * <p>
 * 本类为纯 JDK 工具，不依赖任何 Minecraft 类：只读取 jar 文件，
 * 并将提取副本写入缓存目录。
 */
public final class ModInfoScanner {

    private ModInfoScanner() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * The scan result of one jar: where it lives in the container hierarchy and
     * what metadata was guessed for it.
     * <p>
     * 单个 jar 的扫描结果：它在容器层级中的位置，以及为其猜测到的元数据。
     */
    public static final class ScanResult {

        private final String jarName;
        private final File jarFile;
        private final boolean nested;
        private final ModInfoGuesser.Guess guess;

        ScanResult(String jarName, File jarFile, boolean nested, ModInfoGuesser.Guess guess) {
            this.jarName = jarName;
            this.jarFile = jarFile;
            this.nested = nested;
            this.guess = guess;
        }

        /**
         * Display name of the jar: the outer file name, or the full entry name
         * for nested jars, e.g. {@code "META-INF/jarjar/lib-1.0.jar"}.
         * <p>
         * jar 的显示名：外层为文件名，嵌套层为完整条目名，如
         * {@code "META-INF/jarjar/lib-1.0.jar"}。
         */
        public String jarName() {
            return jarName;
        }

        /**
         * The physical jar file: the outer jar itself, or the extracted cache
         * copy of a nested jar. / jar 的物理文件：外层 jar 本身，或嵌套 jar 的
         * 提取缓存副本。
         */
        public File jarFile() {
            return jarFile;
        }

        /**
         * Whether this jar was nested inside another jar. / 该 jar 是否嵌套于其他 jar 内。
         */
        public boolean nested() {
            return nested;
        }

        /**
         * The guessed mod metadata of this jar. / 该 jar 猜测到的模组元数据。
         */
        public ModInfoGuesser.Guess guess() {
            return guess;
        }

        @Override
        public String toString() {
            return "ScanResult{jarName='" + jarName + "', nested=" + nested + ", guess=" + guess + "}";
        }
    }

    /**
     * Scans the given jar and every jar nested inside it, using the conventional
     * nested directory {@value JarInJar#DEFAULT_NESTED_DIR} and the default cache
     * directory. / 扫描给定 jar 及其全部内嵌 jar，使用约定嵌套目录
     * {@value JarInJar#DEFAULT_NESTED_DIR} 与默认缓存目录。
     *
     * @param outerJar the container jar file / 容器 jar 文件
     * @return scan results, discovery-ordered, never {@code null}
     *         （按发现顺序排列的扫描结果，恒非 {@code null}）
     */
    public static List<ScanResult> scanJar(File outerJar) {
        return scanJar(outerJar, JarInJar.DEFAULT_NESTED_DIR, JarInJar.getDefaultCacheDir());
    }

    /**
     * Scans the given jar and every jar nested inside it, level by level, with a
     * custom nested directory and cache directory. The outer jar is always first
     * in the result list.
     * <p>
     * 以自定义嵌套目录与缓存目录逐层扫描给定 jar 及其全部内嵌 jar。
     * 外层 jar 始终位于结果列表首位。
     *
     * @param outerJar the container jar file / 容器 jar 文件
     * @param nestedDir entry prefix of nested jars, e.g. {@code "META-INF/jarjar/"}
     *                  （嵌套 jar 的条目前缀，如 {@code "META-INF/jarjar/"}）
     * @param cacheDir  directory to extract nested jars into / 嵌套 jar 提取缓存目录
     * @return scan results, never {@code null}; empty for a missing input
     *         （扫描结果，恒非 {@code null}；输入缺失时为空列表）
     */
    public static List<ScanResult> scanJar(File outerJar, String nestedDir, File cacheDir) {
        List<ScanResult> results = new ArrayList<ScanResult>();
        if (outerJar == null || !outerJar.isFile() || !JarNames.isJarLike(outerJar.getName())) {
            return results;
        }
        // Canonical paths of jars already scanned; breaks content cycles too,
        // since identical content always extracts to the same hash-named file.
        // 已扫描 jar 的规范路径集合；同时也截断内容环——
        // 相同内容必然提取为同名哈希文件。
        Set<String> scanned = new HashSet<String>();
        Deque<Frame> frontier = new ArrayDeque<Frame>();
        frontier.add(new Frame(outerJar, outerJar.getName(), false));
        while (!frontier.isEmpty()) {
            Frame frame = frontier.poll();
            String key = canonicalKey(frame.jarFile);
            if (!scanned.add(key)) {
                continue;
            }
            // The logical name feeds the file-name fallback, so a hash-named cache
            // copy must not leak into the guess. / 逻辑名参与文件名兜底，
            // 哈希命名的缓存副本不得泄漏进猜测结果。
            results.add(new ScanResult(frame.jarName, frame.jarFile, frame.nested,
                    ModInfoGuesser.guessJar(frame.jarFile, frame.jarName)));
            for (String entry : JarInJar.listNestedJarEntries(frame.jarFile, nestedDir)) {
                File extracted = JarInJar.extractNestedJar(frame.jarFile, entry, cacheDir);
                if (extracted != null) {
                    frontier.add(new Frame(extracted, entry, true));
                }
            }
        }
        return results;
    }

    /**
     * A jar pending scan plus its display name and nesting flag.
     * 待扫描的 jar 及其显示名与嵌套标志。
     */
    private static final class Frame {

        final File jarFile;
        final String jarName;
        final boolean nested;

        Frame(File jarFile, String jarName, boolean nested) {
            this.jarFile = jarFile;
            this.jarName = jarName;
            this.nested = nested;
        }
    }

    private static String canonicalKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }
}
