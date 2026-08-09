package io.qzz.dfdvdsf.jarinjar;

import io.qzz.dfdvdsf.concurrent.Parallel;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Jar-in-Jar support: discovers jars nested inside a container jar (by
 * convention under
 * {@value #DEFAULT_NESTED_DIR}), extracts them into a local cache directory
 * with
 * SHA-256 based deduplication, and injects them into the
 * {@link LaunchClassLoader}
 * so their classes become loadable at runtime.
 * <p>
 * Also transparently supports the development workspace case where the
 * "container"
 * is a classes directory instead of a jar: nested jars are then plain files
 * under
 * that directory and are injected directly without extraction.
 * <p>
 * 嵌套 Jar（Jar-in-Jar）支持：按约定从容器 jar 的 {@value #DEFAULT_NESTED_DIR} 目录下
 * 发现内嵌 jar，以 SHA-256 去重的方式提取到本地缓存目录，并注入
 * {@link LaunchClassLoader}，使其中的类在运行时可被加载。
 * <p>
 * 同时透明兼容开发环境：当“容器”是 class 输出目录而非 jar 时，
 * 内嵌 jar 即目录下的普通文件，直接注入而无需提取。
 * <p>
 * 批量入口会对多个容器并发执行发现与提取（每个容器共享一个 JarFile 句柄），
 * 类路径注入保持串行，以避免 LaunchClassLoader 的并发竞态。
 */
public final class JarInJar {

    /**
     * Conventional directory inside the container jar that holds nested jars.
     * 容器 jar 内存放嵌套 jar 的约定目录。
     */
    public static final String DEFAULT_NESTED_DIR = "META-INF/jarjar/";

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|JarInJar");

    /**
     * Canonical paths of jars already injected, to guard against double injection.
     * 已注入 jar 的规范路径集合，防止重复注入。
     */
    private static final Set<String> INJECTED = Collections.synchronizedSet(new HashSet<String>());

    private JarInJar() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    // === === === High-level one-shot API / 高层一步式 API === === ===

    /**
     * One-shot entry point: locates the jar containing {@code ownerClass}, extracts
     * every
     * nested jar under {@value #DEFAULT_NESTED_DIR} into the default cache
     * directory, and
     * injects them into the {@link LaunchClassLoader}. Safe to call multiple times.
     * <p>
     * 一步式入口：定位 {@code ownerClass} 所在的 jar，将 {@value #DEFAULT_NESTED_DIR}
     * 下的全部嵌套 jar 提取到默认缓存目录并注入 {@link LaunchClassLoader}。可重复调用。
     *
     * @param ownerClass a class inside the container jar (typically the mod main
     *                   class)
     *                   （容器 jar 内的任意类，通常传模组主类）
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJars(Class<?> ownerClass) {
        return loadNestedJars(ownerClass, DEFAULT_NESTED_DIR, getDefaultCacheDir());
    }

    /**
     * Same as {@link #loadNestedJars(Class)} with a custom nested directory and
     * cache directory.
     * <p>
     * 与 {@link #loadNestedJars(Class)} 相同，但可自定义嵌套目录与缓存目录。
     *
     * @param ownerClass a class inside the container jar / 容器 jar 内的任意类
     * @param nestedDir  entry prefix of nested jars, e.g.
     *                   {@code "META-INF/jarjar/"}
     *                   （嵌套 jar 的条目前缀，如 {@code "META-INF/jarjar/"}）
     * @param cacheDir   directory to extract into / 提取目标缓存目录
     * @return the injected jar files / 已注入的 jar 文件列表
     */
    public static List<File> loadNestedJars(Class<?> ownerClass, String nestedDir, File cacheDir) {
        List<File> injected = new ArrayList<File>();
        File container = JarLocator.getContainingFile(ownerClass);
        if (container == null) {
            LOGGER.warn("Cannot locate container of {}; skip jar-in-jar loading", ownerClass.getName());
            return injected;
        }

        List<File> jars;
        if (container.isFile()) {
            // Packaged environment: extract nested entries out of the jar first.
            // 打包环境：先将嵌套条目从 jar 中提取出来。
            jars = extractAllNestedJars(container, nestedDir, cacheDir);
        } else {
            // Dev workspace: nested jars sit directly on disk, no extraction needed.
            // 开发环境：嵌套 jar 直接位于磁盘上，无需提取。
            jars = listNestedJarsInDirectory(container, nestedDir);
        }

        for (File jar : jars) {
            if (injectIntoClasspath(jar)) {
                injected.add(jar);
            }
        }
        return injected;
    }

    /**
     * Batch one-shot entry point: locates the container of every given owner class,
     * discovers/extracts their nested jars concurrently, and injects them into the
     * {@link LaunchClassLoader}. Containers that resolve to the same file are
     * processed
     * only once. Safe to call multiple times.
     * <p>
     * 批量一步式入口：定位每个给定类所在的容器，并发发现/提取其中的嵌套 jar 并注入
     * {@link LaunchClassLoader}。解析到同一文件的容器只会被处理一次。可重复调用。
     *
     * @param ownerClasses classes inside the container jars (typically mod main
     *                     classes)
     *                     （容器 jar 内的类，通常传模组主类）
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJars(Collection<Class<?>> ownerClasses) {
        return loadNestedJars(ownerClasses, DEFAULT_NESTED_DIR, getDefaultCacheDir());
    }

    /**
     * Same as {@link #loadNestedJars(Collection)} with a custom nested directory
     * and cache
     * directory.
     * <p>
     * 与 {@link #loadNestedJars(Collection)} 相同，但可自定义嵌套目录与缓存目录。
     *
     * @param ownerClasses classes inside the container jars / 容器 jar 内的类
     * @param nestedDir    entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir     directory to extract into / 提取目标缓存目录
     * @return the injected jar files / 已注入的 jar 文件列表
     */
    public static List<File> loadNestedJars(Collection<Class<?>> ownerClasses, String nestedDir, File cacheDir) {
        return loadNestedJarsFromJars(resolveContainers(ownerClasses), nestedDir, cacheDir);
    }

    /**
     * Batch one-shot entry point that takes container files directly: discovers and
     * extracts nested jars under {@code nestedDir} concurrently, then injects them
     * into
     * the {@link LaunchClassLoader}. Containers may be jars (packaged) or
     * directories
     * (dev workspace).
     * <p>
     * 直接接收容器文件列表的批量一步式入口：并发发现并提取 {@code nestedDir} 下的嵌套
     * jar，随后注入类路径。容器可以是 jar（打包环境）或目录（开发环境）。
     *
     * @param containerJars the container files / 容器文件列表
     * @param nestedDir     entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir      directory to extract into / 提取目标缓存目录
     * @return the injected jar files / 已注入的 jar 文件列表
     */
    public static List<File> loadNestedJarsFromJars(Collection<File> containerJars, String nestedDir, File cacheDir) {
        List<File> injected = new ArrayList<File>();
        for (File jar : extractAllNestedJars(containerJars, nestedDir, cacheDir)) {
            if (injectIntoClasspath(jar)) {
                injected.add(jar);
            }
        }
        return injected;
    }

    // === === === Recursive one-shot API / 递归一步式 API === === ===

    /**
     * Recursive one-shot entry point: like {@link #loadNestedJars(Class)} but keeps
     * descending — every nested jar that itself contains nested jars under
     * {@value #DEFAULT_NESTED_DIR} is discovered and extracted too, level by level,
     * until no more nested jars are found. Duplicate containers and content cycles
     * are handled safely. Safe to call multiple times.
     * <p>
     * 递归一步式入口：与 {@link #loadNestedJars(Class)} 相同，但会持续向下展开——
     * 嵌套 jar 自身若还含有 {@value #DEFAULT_NESTED_DIR} 下的嵌套 jar，也会逐层
     * 被发现并提取，直到不存在更多嵌套为止。重复容器与内容环均被安全处理。可重复调用。
     *
     * @param ownerClass a class inside the container jar (typically the mod main
     *                   class)
     *                   （容器 jar 内的任意类，通常传模组主类）
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJarsRecursive(Class<?> ownerClass) {
        return loadNestedJarsRecursive(ownerClass, DEFAULT_NESTED_DIR, getDefaultCacheDir());
    }

    /**
     * Same as {@link #loadNestedJarsRecursive(Class)} with a custom nested directory
     * and cache directory.
     * <p>
     * 与 {@link #loadNestedJarsRecursive(Class)} 相同，但可自定义嵌套目录与缓存目录。
     *
     * @param ownerClass a class inside the container jar / 容器 jar 内的任意类
     * @param nestedDir  entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir   directory to extract into / 提取目标缓存目录
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJarsRecursive(Class<?> ownerClass, String nestedDir, File cacheDir) {
        return loadNestedJarsRecursive(Collections.singleton(ownerClass), nestedDir, cacheDir);
    }

    /**
     * Batch recursive one-shot entry point: resolves the container of every given
     * owner class and loads their nested jars transitively. Safe to call multiple
     * times.
     * <p>
     * 批量递归一步式入口：解析每个给定类所在的容器，并传递式地加载其嵌套 jar。
     * 可重复调用。
     *
     * @param ownerClasses classes inside the container jars (typically mod main
     *                     classes)
     *                     （容器 jar 内的类，通常传模组主类）
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJarsRecursive(Collection<Class<?>> ownerClasses) {
        return loadNestedJarsRecursive(ownerClasses, DEFAULT_NESTED_DIR, getDefaultCacheDir());
    }

    /**
     * Same as {@link #loadNestedJarsRecursive(Collection)} with a custom nested
     * directory and cache directory.
     * <p>
     * 与 {@link #loadNestedJarsRecursive(Collection)} 相同，但可自定义嵌套目录与缓存目录。
     *
     * @param ownerClasses classes inside the container jars / 容器 jar 内的类
     * @param nestedDir    entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir     directory to extract into / 提取目标缓存目录
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJarsRecursive(Collection<Class<?>> ownerClasses, String nestedDir, File cacheDir) {
        return loadNestedJarsRecursiveFromJars(resolveContainers(ownerClasses), nestedDir, cacheDir);
    }

    /**
     * Batch recursive one-shot entry point that takes container files directly; see
     * {@link #extractAllNestedJarsRecursive(Collection, String, File)} for the
     * traversal semantics. Injection order follows discovery order.
     * <p>
     * 直接接收容器文件列表的批量递归一步式入口；遍历语义见
     * {@link #extractAllNestedJarsRecursive(Collection, String, File)}。
     * 注入顺序与发现顺序一致。
     *
     * @param containerJars the container files / 容器文件列表
     * @param nestedDir     entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir      directory to extract into / 提取目标缓存目录
     * @return the injected jar files, empty if none found / 已注入的 jar 文件列表，无则为空
     */
    public static List<File> loadNestedJarsRecursiveFromJars(Collection<File> containerJars, String nestedDir, File cacheDir) {
        List<File> injected = new ArrayList<File>();
        for (File jar : extractAllNestedJarsRecursive(containerJars, nestedDir, cacheDir)) {
            if (injectIntoClasspath(jar)) {
                injected.add(jar);
            }
        }
        return injected;
    }

    // === === === Discovery / 发现 === === ===

    /**
     * Lists entry names of nested jars inside the container jar under the given
     * prefix.
     * Only direct {@code .jar}/{@code .zip} entries are returned; directories are
     * skipped.
     * <p>
     * 列出容器 jar 中指定前缀目录下的嵌套 jar 条目名。
     * 仅返回 {@code .jar}/{@code .zip} 条目，目录条目会被跳过。
     *
     * @param containerJar the outer jar file / 外层容器 jar 文件
     * @param nestedDir    entry prefix, may be empty for whole-jar scan
     *                     （条目前缀，传空串表示全 jar 扫描）
     * @return matching entry names, never {@code null} / 匹配的条目名列表，恒非 {@code null}
     */
    public static List<String> listNestedJarEntries(File containerJar, String nestedDir) {
        JarFile jarFile = openQuietly(containerJar);
        if (jarFile == null) {
            return new ArrayList<String>();
        }
        try {
            return listNestedJarEntries(jarFile, nestedDir);
        } finally {
            closeQuietly(jarFile);
        }
    }

    /**
     * Shared-JarFile core of {@link #listNestedJarEntries(File, String)}; the
     * caller owns
     * the JarFile's lifetime. / 共享 JarFile 版的核心实现；JarFile 生命周期由调用方管理。
     */
    private static List<String> listNestedJarEntries(JarFile jarFile, String nestedDir) {
        List<String> entries = new ArrayList<String>();
        Enumeration<JarEntry> en = jarFile.entries();
        while (en.hasMoreElements()) {
            JarEntry entry = en.nextElement();
            if (entry.isDirectory())
                continue;
            String name = entry.getName();
            if (name.startsWith(nestedDir) && isJarLike(name)) {
                entries.add(name);
            }
        }
        return entries;
    }

    /**
     * Dev-workspace counterpart of {@link #listNestedJarEntries(File, String)}:
     * lists jar
     * files under {@code containerDir/nestedDir} on disk.
     * <p>
     * {@link #listNestedJarEntries(File, String)} 的开发环境对应实现：
     * 列出磁盘上 {@code containerDir/nestedDir} 目录内的 jar 文件。
     *
     * @param containerDir the classes directory / class 输出目录
     * @param nestedDir    relative sub-directory / 相对子目录
     * @return jar files found, never {@code null} / 找到的 jar 文件列表，恒非 {@code null}
     */
    public static List<File> listNestedJarsInDirectory(File containerDir, String nestedDir) {
        List<File> jars = new ArrayList<File>();
        File dir = new File(containerDir, nestedDir);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isJarLike(f.getName())) {
                    jars.add(f);
                }
            }
        }
        return jars;
    }

    // === === === Extraction / 提取 === === ===

    /**
     * Extracts every nested jar under the given prefix into {@code cacheDir}.
     * <p>
     * 将指定前缀目录下的全部嵌套 jar 提取到 {@code cacheDir}。
     *
     * @param containerJar the outer jar file / 外层容器 jar 文件
     * @param nestedDir    entry prefix / 条目前缀
     * @param cacheDir     target cache directory / 目标缓存目录
     * @return extracted files (existing cache hits included) / 提取出的文件（含缓存命中）
     */
    public static List<File> extractAllNestedJars(File containerJar, String nestedDir, File cacheDir) {
        List<File> extracted = new ArrayList<File>();
        JarFile jarFile = openQuietly(containerJar);
        if (jarFile == null) {
            return extracted;
        }
        try {
            List<String> entries = listNestedJarEntries(jarFile, nestedDir);
            if (entries.isEmpty()) {
                return extracted;
            }
            // Concurrent extraction over the single shared JarFile handle.
            // 基于单个共享 JarFile 句柄的并发提取。
            List<File> results = Parallel.map(entries, new Function<String, File>() {
                @Override
                public File apply(String entryName) {
                    return extractNestedJar(jarFile, entryName, cacheDir);
                }
            });
            for (File file : results) {
                if (file != null) {
                    extracted.add(file);
                }
            }
        } finally {
            closeQuietly(jarFile);
        }
        return extracted;
    }

    /**
     * Batch version of {@link #extractAllNestedJars(File, String, File)}: processes
     * all
     * given containers concurrently. Directory containers (dev workspace) are
     * listed
     * directly; jar containers are scanned and extracted in parallel, sharing one
     * {@link JarFile} handle per container to bound the number of open handles.
     * <p>
     * {@link #extractAllNestedJars(File, String, File)} 的批量版本：并发处理所有给定
     * 容器。目录容器（开发环境）直接列出；jar 容器并发扫描并提取，每个容器共享一个
     * {@link JarFile} 句柄，以约束打开句柄的总数。
     *
     * @param containerJars the container files (jars or directories) / 容器文件（jar
     *                      或目录）
     * @param nestedDir     entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir      target cache directory / 目标缓存目录
     * @return extracted files (existing cache hits included), never {@code null}
     *         （提取出的文件列表，含缓存命中；恒非 {@code null}）
     */
    public static List<File> extractAllNestedJars(Collection<File> containerJars, String nestedDir, File cacheDir) {
        List<File> extracted = new ArrayList<File>();
        if (containerJars == null || containerJars.isEmpty()) {
            return extracted;
        }
        // Dev-workspace containers are plain directories: list them directly (cheap).
        // 开发环境的容器是普通目录：直接列出（开销小）。
        List<File> jarContainers = new ArrayList<File>();
        for (File container : containerJars) {
            if (container == null) {
                continue;
            }
            if (container.isDirectory()) {
                extracted.addAll(listNestedJarsInDirectory(container, nestedDir));
            } else if (container.isFile()) {
                jarContainers.add(container);
            } else {
                LOGGER.warn("Container {} does not exist; skip", container);
            }
        }
        if (jarContainers.isEmpty()) {
            return extracted;
        }

        // Stage 1: discover nested entries of every jar container concurrently.
        // 第一阶段：并发发现每个 jar 容器中的嵌套条目。
        List<ContainerEntries> discovered = Parallel.map(jarContainers, new Function<File, ContainerEntries>() {
            @Override
            public ContainerEntries apply(File container) {
                return new ContainerEntries(container, listNestedJarEntries(container, nestedDir));
            }
        });

        // Stage 2: extract every entry concurrently, sharing one JarFile per container.
        // 第二阶段：并发提取全部条目；每个容器共享一个 JarFile。
        Map<File, JarFile> sharedJars = new ConcurrentHashMap<File, JarFile>();
        List<ExtractRequest> requests = new ArrayList<ExtractRequest>();
        try {
            for (ContainerEntries ce : discovered) {
                JarFile jarFile = openQuietly(ce.container);
                if (jarFile == null) {
                    continue;
                }
                sharedJars.put(ce.container, jarFile);
                for (String entryName : ce.entries) {
                    requests.add(new ExtractRequest(ce.container, entryName));
                }
            }
            if (!requests.isEmpty()) {
                List<File> results = Parallel.map(requests, new Function<ExtractRequest, File>() {
                    @Override
                    public File apply(ExtractRequest request) {
                        return extractNestedJar(sharedJars.get(request.container), request.entryName, cacheDir);
                    }
                });
                for (File file : results) {
                    if (file != null) {
                        extracted.add(file);
                    }
                }
            }
        } finally {
            for (JarFile jarFile : sharedJars.values()) {
                closeQuietly(jarFile);
            }
        }
        return extracted;
    }

    /**
     * Recursive version of {@link #extractAllNestedJars(Collection, String, File)}:
     * keeps descending into every extracted jar that itself contains nested jars,
     * level by level, until no more nested jars are found. All extracted files are
     * returned in discovery order, deduplicated by canonical path; a jar is never
     * scanned twice, which also breaks content cycles (the same content always
     * resolves to the same hash-named cache file).
     * <p>
     * {@link #extractAllNestedJars(Collection, String, File)} 的递归版本：持续下探到
     * 每个自身还含有嵌套 jar 的已提取 jar，逐层处理，直到不存在更多嵌套为止。
     * 返回全部提取文件，按发现顺序、以规范路径去重；每个 jar 只会被扫描一次，
     * 内容环也因此被截断（相同内容必然解析为同名哈希缓存文件）。
     *
     * @param containerJars the container files (jars or directories) / 容器文件（jar
     *                      或目录）
     * @param nestedDir     entry prefix of nested jars / 嵌套 jar 的条目前缀
     * @param cacheDir      target cache directory / 目标缓存目录
     * @return all extracted files, never {@code null} / 全部提取文件，恒非 {@code null}
     */
    public static List<File> extractAllNestedJarsRecursive(Collection<File> containerJars, String nestedDir, File cacheDir) {
        // Canonical paths of containers already scanned for nested jars.
        // 已作为容器扫描过嵌套 jar 的规范路径集合。
        Set<String> scanned = new HashSet<String>();
        // All extracted files, deduplicated by canonical path, discovery-ordered.
        // 全部提取结果：按规范路径去重，保持发现顺序。
        Map<String, File> extracted = new LinkedHashMap<String, File>();
        // Current level of containers to process. / 当前层待处理的容器。
        List<File> frontier = dedupContainers(containerJars);
        while (!frontier.isEmpty()) {
            // This level is about to be scanned by extractAllNestedJars below; mark
            // it now so the same jar (e.g. a cache hit) is never re-scanned in a
            // later level.
            // 本层即将被下面的 extractAllNestedJars 扫描，先打上标记，
            // 保证同一 jar（如缓存命中）在后续层不会被重复扫描。
            for (File container : frontier) {
                scanned.add(canonicalKey(container));
            }
            for (File file : extractAllNestedJars(frontier, nestedDir, cacheDir)) {
                extracted.put(canonicalKey(file), file);
            }
            // Next level: every extracted jar that has not been scanned yet.
            // 下一层：所有已提取但尚未被扫描过的 jar。
            List<File> next = new ArrayList<File>();
            for (File file : extracted.values()) {
                if (!scanned.contains(canonicalKey(file))) {
                    next.add(file);
                }
            }
            frontier = next;
        }
        return new ArrayList<File>(extracted.values());
    }

    /**
     * Single-container convenience of
     * {@link #extractAllNestedJarsRecursive(Collection, String, File)}.
     * <p>
     * {@link #extractAllNestedJarsRecursive(Collection, String, File)} 的单容器便捷版。
     *
     * @param containerJar the outer jar file / 外层容器 jar 文件
     * @param nestedDir    entry prefix / 条目前缀
     * @param cacheDir     target cache directory / 目标缓存目录
     * @return all extracted files / 全部提取文件
     */
    public static List<File> extractAllNestedJarsRecursive(File containerJar, String nestedDir, File cacheDir) {
        return extractAllNestedJarsRecursive(Collections.singletonList(containerJar), nestedDir, cacheDir);
    }

    /**
     * Extracts a single nested jar entry into {@code cacheDir}, using content-hash
     * deduplication: the output is named {@code <basename>-<sha256[0..8]>.jar}, and
     * if a
     * file with that name already exists it is reused without rewriting.
     * <p>
     * 提取单个嵌套 jar 条目到 {@code cacheDir}，采用内容哈希去重：
     * 输出文件命名为 {@code <原名>-<sha256前8位>.jar}，若同名文件已存在则直接复用，不再重写。
     *
     * @param containerJar the outer jar file / 外层容器 jar 文件
     * @param entryName    full entry name inside the jar / jar 内完整条目名
     * @param cacheDir     target cache directory / 目标缓存目录
     * @return the extracted (or cached) file, or {@code null} on failure
     *         （提取出的或缓存命中的文件；失败时返回 {@code null}）
     */
    public static File extractNestedJar(File containerJar, String entryName, File cacheDir) {
        JarFile jarFile = openQuietly(containerJar);
        if (jarFile == null) {
            return null;
        }
        try {
            return extractNestedJar(jarFile, entryName, cacheDir);
        } finally {
            closeQuietly(jarFile);
        }
    }

    /**
     * Shared-JarFile core of {@link #extractNestedJar(File, String, File)}; the
     * caller owns
     * the JarFile's lifetime, so this variant never closes it. Safe for concurrent
     * use as
     * long as different threads read different entries of the same JarFile.
     * <p>
     * 共享 JarFile 版的核心实现；JarFile 生命周期由调用方管理，本方法不关闭它。
     * 只要不同线程读取的是同一 JarFile 的不同条目，即为线程安全。
     */
    private static File extractNestedJar(JarFile jarFile, String entryName, File cacheDir) {
        try {
            JarEntry entry = jarFile.getJarEntry(entryName);
            if (entry == null) {
                LOGGER.warn("Entry {} not found in {}", entryName, jarFile.getName());
                return null;
            }
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                LOGGER.error("Cannot create cache directory {}", cacheDir);
                return null;
            }

            // Pass 1: digest the entry content to build the dedup file name.
            // 第一遍：对条目内容做摘要，用于构造去重文件名。
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = jarFile.getInputStream(entry);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            String hash = toHex(digest.digest()).substring(0, 8);

            String base = entryName.substring(entryName.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            String stem = (dot > 0) ? base.substring(0, dot) : base;
            File target = new File(cacheDir, stem + "-" + hash + ".jar");

            // Cache hit: same name implies same content hash, reuse directly.
            // 缓存命中：文件名相同即内容哈希相同，直接复用。
            if (target.isFile()) {
                LOGGER.debug("Cache hit for nested jar {}: {}", entryName, target);
                return target;
            }

            // Pass 2: write to a temp file, then atomically rename into place.
            // 第二遍：先写临时文件，再原子重命名到最终位置。
            File tmp = File.createTempFile(stem + "-", ".tmp", cacheDir);
            in = jarFile.getInputStream(entry);
            OutputStream outStream = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    outStream.write(buf, 0, n);
                }
            } finally {
                outStream.close();
                in.close();
            }
            if (!tmp.renameTo(target)) {
                // Rename raced with another process; if the target now exists, reuse it.
                // 重命名与其他进程竞争失败；若目标此时已存在则直接复用。
                if (target.isFile()) {
                    deleteQuietly(tmp);
                    return target;
                }
                LOGGER.error("Failed to move {} -> {}", tmp, target);
                deleteQuietly(tmp);
                return null;
            }
            LOGGER.info("Extracted nested jar {} -> {}", entryName, target);
            return target;
        } catch (Exception e) {
            LOGGER.error("Failed to extract nested jar {} from {}", entryName, jarFile.getName(), e);
            return null;
        }
    }

    // === === === Classpath injection / 类路径注入 === === ===

    /**
     * Injects a jar into the {@link LaunchClassLoader} (launchwrapper) so its
     * classes and
     * resources become visible to mod code. Duplicate calls for the same file are
     * no-ops.
     * <p>
     * 将 jar 注入 {@link LaunchClassLoader}（launchwrapper），使其类与资源对模组代码可见。
     * 同一文件重复调用为无操作。
     *
     * @param jar the jar file to inject / 待注入的 jar 文件
     * @return {@code true} if newly injected, {@code false} if skipped or failed
     *         （首次注入返回 {@code true}；跳过或失败返回 {@code false}）
     */
    public static boolean injectIntoClasspath(File jar) {
        try {
            String key = jar.getCanonicalPath();
            if (!INJECTED.add(key)) {
                return false;
            }
            URL url = jar.toURI().toURL();
            ClassLoader cl = JarInJar.class.getClassLoader();
            if (cl instanceof LaunchClassLoader) {
                ((LaunchClassLoader) cl).addURL(url);
            } else if (Launch.classLoader != null) {
                Launch.classLoader.addURL(url);
            } else {
                // Outside launchwrapper (unit tests etc.): try reflective
                // URLClassLoader#addURL.
                // 非 launchwrapper 环境（如单元测试）：反射调用 URLClassLoader#addURL 兜底。
                if (!injectIntoUrlClassLoader(cl, url)) {
                    INJECTED.remove(key);
                    LOGGER.error("No usable class loader to inject {}", jar);
                    return false;
                }
            }
            LOGGER.info("Injected nested jar into classpath: {}", jar);
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to inject {} into classpath", jar, e);
            return false;
        }
    }

    /**
     * Reflective fallback for plain {@link URLClassLoader}s (Java 8 only).
     * 针对普通 {@link URLClassLoader} 的反射兜底（仅 Java 8 可用）。
     */
    private static boolean injectIntoUrlClassLoader(ClassLoader cl, URL url) {
        if (!(cl instanceof URLClassLoader)) {
            return false;
        }
        try {
            java.lang.reflect.Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addURL.setAccessible(true);
            addURL.invoke(cl, url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // === === === Misc helpers / 杂项辅助 === === ===

    /**
     * Default extraction cache: {@code <gameDir>/jarutils/jarinjar}, falling back
     * to
     * {@code <user.dir>/jarutils/jarinjar} when launchwrapper's game dir is
     * unavailable.
     * <p>
     * 默认提取缓存目录：{@code <游戏目录>/jarutils/jarinjar}；
     * 当 launchwrapper 未提供游戏目录时回退到 {@code <user.dir>/jarutils/jarinjar}。
     *
     * @return the default cache directory (not necessarily existing yet)
     *         （默认缓存目录，可能尚未创建）
     */
    public static File getDefaultCacheDir() {
        File base = (Launch.minecraftHome != null) ? Launch.minecraftHome : new File(System.getProperty("user.dir"));
        return new File(base, "jarutils" + File.separator + "jarinjar");
    }

    /**
     * Canonical path of a file, falling back to the absolute path when the
     * canonical path is unavailable (e.g. on exotic file systems).
     * <p>
     * 文件的规范路径；规范路径不可用时（如特殊文件系统）回退到绝对路径。
     */
    private static String canonicalKey(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /**
     * Filters out {@code null} entries and duplicate containers (by canonical
     * path), preserving the order of first occurrences.
     * <p>
     * 过滤掉 {@code null} 与重复容器（按规范路径判断），保留首次出现的顺序。
     */
    private static List<File> dedupContainers(Collection<File> containers) {
        List<File> deduped = new ArrayList<File>();
        if (containers == null) {
            return deduped;
        }
        Set<String> seen = new HashSet<String>();
        for (File container : containers) {
            if (container == null) {
                continue;
            }
            if (seen.add(canonicalKey(container))) {
                deduped.add(container);
            }
        }
        return deduped;
    }

    /**
     * Resolves the container of every owner class, skipping unresolvable ones and
     * deduplicating containers that map to the same file.
     * <p>
     * 解析每个 owner 类所在的容器：跳过无法解析的类，并对映射到同一文件的容器去重。
     */
    private static List<File> resolveContainers(Collection<Class<?>> ownerClasses) {
        List<File> containers = new ArrayList<File>();
        Set<String> seen = new HashSet<String>();
        for (Class<?> owner : ownerClasses) {
            File container = JarLocator.getContainingFile(owner);
            if (container == null) {
                LOGGER.warn("Cannot locate container of {}; skip jar-in-jar loading", owner.getName());
                continue;
            }
            if (seen.add(canonicalKey(container))) {
                containers.add(container);
            }
        }
        return containers;
    }

    private static boolean isJarLike(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * Opens a JarFile, logging and returning {@code null} on failure.
     * 打开 JarFile；失败时记录日志并返回 {@code null}。
     */
    private static JarFile openQuietly(File jarFile) {
        try {
            return new JarFile(jarFile);
        } catch (IOException e) {
            LOGGER.error("Failed to open {}", jarFile, e);
            return null;
        }
    }

    private static void closeQuietly(JarFile jarFile) {
        if (jarFile != null) {
            try {
                jarFile.close();
            } catch (IOException ignored) {
                // Best-effort close. / 尽力关闭，忽略异常。
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && !file.delete()) {
            file.deleteOnExit();
        }
    }

    // === === === Internal batch structures / 批量处理内部结构 === === ===

    /**
     * Container jar plus the nested entry names discovered inside it. / 容器 jar
     * 及其内部发现的嵌套条目名。
     */
    private static final class ContainerEntries {
        final File container;
        final List<String> entries;

        ContainerEntries(File container, List<String> entries) {
            this.container = container;
            this.entries = entries;
        }
    }

    /**
     * One extraction request: a single nested entry inside a given container. /
     * 单个提取请求：某容器内的一个嵌套条目。
     */
    private static final class ExtractRequest {
        final File container;
        final String entryName;

        ExtractRequest(File container, String entryName) {
            this.container = container;
            this.entryName = entryName;
        }
    }
}
