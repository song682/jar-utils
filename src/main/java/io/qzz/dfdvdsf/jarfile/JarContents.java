package io.qzz.dfdvdsf.jarfile;

import io.qzz.dfdvdsf.concurrent.Parallel;
import io.qzz.dfdvdsf.jarinjar.JarLocator;
import net.minecraft.launchwrapper.Launch;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Read-only access to the files inside a jar: list entry names, open/read entry
 * content, and check whether a jar / resource / class is already loaded (visible
 * to the class loader).
 * <p>
 * All jar-based methods transparently support the development workspace case where
 * the "jar" is actually a classes directory: entries are then plain files on disk,
 * addressed by the same '/'-separated relative names.
 * <p>
 * 对 jar 内部文件的只读访问：列出条目名、打开/读取条目内容，
 * 以及检查某个 jar、资源或类是否已被加载（对类加载器可见）。
 * <p>
 * 所有基于 jar 的方法都透明兼容开发环境：当“jar”实际是 class 输出目录时，
 * 条目即磁盘上的普通文件，使用相同的 '/' 分隔相对路径寻址。
 */
public final class JarContents {

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|JarContents");

    private JarContents() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    // === === === Entry names / 条目名称 === === ===

    /**
     * Lists the names of all file entries in the container (directories excluded).
     * <p>
     * 列出容器内全部文件条目的名称（不含目录条目）。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @return '/'-separated entry names, never {@code null}
     *         （以 '/' 分隔的条目名列表，恒非 {@code null}）
     */
    public static List<String> listEntryNames(File container) {
        return listEntryNames(container, "", null);
    }

    /**
     * Lists file entry names filtered by prefix and suffix, e.g.
     * {@code listEntryNames(jar, "assets/", ".json")}.
     * <p>
     * 按前缀与后缀过滤列出文件条目名，例如
     * {@code listEntryNames(jar, "assets/", ".json")}。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @param prefix    required entry-name prefix, empty for no restriction
     *                  （条目名前缀，空串表示不限制）
     * @param suffix    required entry-name suffix, {@code null} for no restriction
     *                  （条目名后缀，{@code null} 表示不限制）
     * @return matching entry names, never {@code null} / 匹配的条目名列表，恒非 {@code null}
     */
    public static List<String> listEntryNames(File container, String prefix, String suffix) {
        List<String> names = new ArrayList<String>();
        if (container == null) {
            return names;
        }
        if (container.isFile()) {
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(container);
                Enumeration<JarEntry> en = jarFile.entries();
                while (en.hasMoreElements()) {
                    JarEntry entry = en.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (matches(name, prefix, suffix)) {
                        names.add(name);
                    }
                }
            } catch (IOException e) {
                LOGGER.error("Failed to list entries of {}", container, e);
            } finally {
                closeQuietly(jarFile);
            }
        } else if (container.isDirectory()) {
            // Dev workspace: walk the directory tree and build jar-style relative names.
            // 开发环境：递归遍历目录树，构造 jar 风格的相对条目名。
            collectFileNames(container, "", names, prefix, suffix);
        }
        return names;
    }

    /**
     * Convenience overload: lists entry names of the container that the given class
     * was loaded from (jar in production, classes directory in dev).
     * <p>
     * 便捷重载：列出给定类所在容器的条目名（正式环境为 jar，开发环境为 class 目录）。
     *
     * @param ownerClass a class inside the container / 容器内的任意类
     * @param prefix     entry-name prefix filter / 条目名前缀过滤
     * @param suffix     entry-name suffix filter, {@code null} for none / 后缀过滤，{@code null} 表示不限
     * @return matching entry names, empty if the container cannot be located
     *         （匹配的条目名列表；容器无法定位时为空）
     */
    public static List<String> listEntryNames(Class<?> ownerClass, String prefix, String suffix) {
        return listEntryNames(JarLocator.getContainingFile(ownerClass), prefix, suffix);
    }

    /**
     * Batch version of {@link #listEntryNames(File, String, String)}: lists matching entry
     * names of many containers concurrently. The result maps each container to its own
     * entry-name list, preserving the input container order.
     * <p>
     * {@link #listEntryNames(File, String, String)} 的批量版本：并发列出多个容器中匹配的
     * 条目名。结果以 容器 → 条目名列表 的形式返回，保持容器输入顺序。
     *
     * @param containers the jar files or classes directories / jar 文件或 class 输出目录
     * @param prefix     entry-name prefix filter, empty for no restriction
     *                   （条目名前缀，空串表示不限制）
     * @param suffix     entry-name suffix filter, {@code null} for none / 后缀过滤，{@code null} 表示不限
     * @return per-container entry names, never {@code null}
     *         （各容器的条目名映射，恒非 {@code null}）
     */
    public static Map<File, List<String>> listEntryNames(Collection<File> containers, String prefix, String suffix) {
        Map<File, List<String>> perContainer = new LinkedHashMap<File, List<String>>();
        if (containers == null || containers.isEmpty()) {
            return perContainer;
        }
        List<File> inputs = new ArrayList<File>(containers);
        List<List<String>> results = Parallel.map(inputs, new Function<File, List<String>>() {
            @Override
            public List<String> apply(File container) {
                return listEntryNames(container, prefix, suffix);
            }
        });
        for (int i = 0; i < inputs.size(); i++) {
            perContainer.put(inputs.get(i), results.get(i));
        }
        return perContainer;
    }

    /**
     * Checks whether the container holds an entry with the given name.
     * <p>
     * 检查容器中是否存在指定名称的条目。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @param entryName '/'-separated entry name / 以 '/' 分隔的条目名
     * @return {@code true} if the entry exists / 条目存在则为 {@code true}
     */
    public static boolean hasEntry(File container, String entryName) {
        if (container == null || entryName == null) {
            return false;
        }
        if (container.isFile()) {
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(container);
                return jarFile.getJarEntry(entryName) != null;
            } catch (IOException e) {
                return false;
            } finally {
                closeQuietly(jarFile);
            }
        }
        return new File(container, entryName).isFile();
    }

    // === === === Entry content / 条目内容 === === ===

    /**
     * Opens a stream over an entry's content. The returned stream keeps the underlying
     * {@link JarFile} open and closes it together when the stream itself is closed,
     * so callers only need to close the returned stream.
     * <p>
     * 打开条目内容的输入流。返回的流会持有底层 {@link JarFile}，
     * 并在流本身关闭时一并关闭它 —— 调用方只需关闭返回的流即可。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @param entryName '/'-separated entry name / 以 '/' 分隔的条目名
     * @return the entry's stream, or {@code null} if missing or unreadable
     *         （条目输入流；不存在或不可读时返回 {@code null}）
     */
    public static InputStream openEntry(File container, String entryName) {
        if (container == null || entryName == null) {
            return null;
        }
        if (container.isFile()) {
            JarFile jarFile = null;
            try {
                jarFile = new JarFile(container);
                JarEntry entry = jarFile.getJarEntry(entryName);
                if (entry == null) {
                    closeQuietly(jarFile);
                    return null;
                }
                // Tie the JarFile's lifetime to the returned stream.
                // 将 JarFile 的生命周期绑定到返回的流上。
                final JarFile owned = jarFile;
                return new FilterInputStream(jarFile.getInputStream(entry)) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            owned.close();
                        }
                    }
                };
            } catch (IOException e) {
                LOGGER.error("Failed to open entry {} in {}", entryName, container, e);
                closeQuietly(jarFile);
                return null;
            }
        }
        try {
            File file = new File(container, entryName);
            return file.isFile() ? new FileInputStream(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Reads an entry's full content into a byte array.
     * <p>
     * 将条目的完整内容读入字节数组。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @param entryName '/'-separated entry name / 以 '/' 分隔的条目名
     * @return the content bytes, or {@code null} if missing or unreadable
     *         （内容字节；不存在或读取失败时返回 {@code null}）
     */
    public static byte[] readEntryBytes(File container, String entryName) {
        InputStream in = openEntry(container, entryName);
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
            LOGGER.error("Failed to read entry {} in {}", entryName, container, e);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * Reads an entry's full content as a UTF-8 string.
     * <p>
     * 以 UTF-8 编码读取条目的完整文本内容。
     *
     * @param container a jar file or a classes directory / jar 文件或 class 输出目录
     * @param entryName '/'-separated entry name / 以 '/' 分隔的条目名
     * @return the text content, or {@code null} if missing or unreadable
     *         （文本内容；不存在或读取失败时返回 {@code null}）
     */
    public static String readEntryText(File container, String entryName) {
        byte[] bytes = readEntryBytes(container, entryName);
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

    // === === === Loaded-state checks / 加载状态检测 === === ===

    /**
     * Checks whether a resource is loaded, i.e. reachable through the runtime class
     * loader ({@link Launch#classLoader} when available). A {@code true} result means
     * some classpath source — this mod's jar, a nested jar, or any other mod — provides it.
     * <p>
     * 检查资源是否已被加载，即能否通过运行时类加载器
     * （可用时为 {@link Launch#classLoader}）访问到。返回 {@code true} 表示
     * 某个类路径来源（本模组 jar、嵌套 jar 或其他任意模组）提供了该资源。
     *
     * @param resourcePath '/'-separated resource path without leading slash
     *                     （以 '/' 分隔、不带前导斜杠的资源路径）
     * @return {@code true} if visible to the class loader / 对类加载器可见则为 {@code true}
     */
    public static boolean isResourceLoaded(String resourcePath) {
        if (resourcePath == null) {
            return false;
        }
        ClassLoader cl = (Launch.classLoader != null)
                ? Launch.classLoader
                : JarContents.class.getClassLoader();
        return cl != null && cl.getResource(resourcePath) != null;
    }

    /**
     * Checks whether the given jar file is loaded on the classpath, by comparing its
     * canonical path against the URLs of the runtime {@link URLClassLoader} chain.
     * <p>
     * 通过与运行时 {@link URLClassLoader} 链上各 URL 的规范路径比对，
     * 检查给定 jar 文件是否已挂载到类路径上。
     *
     * @param jar the jar file to check / 待检查的 jar 文件
     * @return {@code true} if the jar is on the classpath / jar 已在类路径上则为 {@code true}
     */
    public static boolean isJarLoaded(File jar) {
        if (jar == null) {
            return false;
        }
        String canonical;
        try {
            canonical = jar.getCanonicalPath();
        } catch (IOException e) {
            canonical = jar.getAbsolutePath();
        }
        // Walk the loader parent chain so both LaunchClassLoader and the app loader are covered.
        // 沿加载器父链遍历，同时覆盖 LaunchClassLoader 与应用类加载器。
        ClassLoader cl = (Launch.classLoader != null)
                ? Launch.classLoader
                : JarContents.class.getClassLoader();
        while (cl != null) {
            if (cl instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) cl).getURLs()) {
                    File f = JarLocator.urlToFile(url);
                    if (f != null) {
                        try {
                            if (f.getCanonicalPath().equals(canonical)) {
                                return true;
                            }
                        } catch (IOException ignored) {
                            // Skip unresolvable URLs. / 跳过无法规范化的 URL。
                        }
                    }
                }
            }
            cl = cl.getParent();
        }
        return false;
    }

    /**
     * Checks whether a class has already been defined (initialized or not) by the
     * runtime class loader, without triggering class loading. Implemented via
     * reflective {@code ClassLoader#findLoadedClass}.
     * <p>
     * 检查某个类是否已被运行时类加载器定义（无论是否初始化），且不会触发类加载。
     * 通过反射调用 {@code ClassLoader#findLoadedClass} 实现。
     *
     * @param className fully qualified binary class name / 完全限定的二进制类名
     * @return {@code true} if already defined; {@code false} if not, or if the check
     *         itself is unavailable（已定义则为 {@code true}；未定义或检测不可用时为 {@code false}）
     */
    public static boolean isClassLoaded(String className) {
        if (className == null) {
            return false;
        }
        ClassLoader cl = (Launch.classLoader != null)
                ? Launch.classLoader
                : JarContents.class.getClassLoader();
        try {
            Method findLoaded = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
            findLoaded.setAccessible(true);
            while (cl != null) {
                if (findLoaded.invoke(cl, className) != null) {
                    return true;
                }
                cl = cl.getParent();
            }
        } catch (Exception e) {
            LOGGER.debug("findLoadedClass check unavailable for {}", className, e);
        }
        return false;
    }

    // === === === Misc helpers / 杂项辅助 === === ===

    /**
     * Recursively collects '/'-separated relative file names under a directory.
     * 递归收集目录下以 '/' 分隔的相对文件名。
     */
    private static void collectFileNames(File dir, String relative, List<String> out,
                                         String prefix, String suffix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = relative.isEmpty() ? child.getName() : relative + "/" + child.getName();
            if (child.isDirectory()) {
                collectFileNames(child, name, out, prefix, suffix);
            } else if (matches(name, prefix, suffix)) {
                out.add(name);
            }
        }
    }

    private static boolean matches(String name, String prefix, String suffix) {
        return (prefix == null || prefix.isEmpty() || name.startsWith(prefix))
                && (suffix == null || suffix.isEmpty() || name.endsWith(suffix));
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

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Best-effort close. / 尽力关闭，忽略异常。
            }
        }
    }
}
