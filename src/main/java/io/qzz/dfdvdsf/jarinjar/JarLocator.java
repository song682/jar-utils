package io.qzz.dfdvdsf.jarinjar;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Utility for locating the physical container of a loaded class, i.e. the jar file
 * it was loaded from, or the classes directory when running in a development workspace.
 * <p>
 * 用于定位某个已加载类所在的物理容器：正式运行时为其所在的 jar 文件，
 * 在开发环境（dev workspace）下则为对应的 class 输出目录。
 */
public final class JarLocator {

    private JarLocator() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Resolves the container (jar file or directory) that the given class was loaded from.
     * First tries {@link ProtectionDomain#getCodeSource()}, then falls back to resolving
     * the {@code .class} resource URL when the code source is unavailable.
     * <p>
     * 解析给定类被加载时所在的容器（jar 文件或目录）。
     * 优先通过 {@link ProtectionDomain#getCodeSource()} 获取；
     * 若 CodeSource 不可用，则回退到解析该类 {@code .class} 资源的 URL。
     *
     * @param clazz the class whose container should be located / 需要定位容器的类
     * @return the containing jar file or directory, or {@code null} if it cannot be resolved
     *         （返回所在 jar 或目录；无法解析时返回 {@code null}）
     */
    public static File getContainingFile(Class<?> clazz) {
        URL location = null;

        // Preferred path: the code source recorded by the class loader.
        // 首选途径：类加载器记录的 CodeSource 位置。
        ProtectionDomain pd = clazz.getProtectionDomain();
        if (pd != null) {
            CodeSource cs = pd.getCodeSource();
            if (cs != null) {
                location = cs.getLocation();
            }
        }

        // Fallback: look up the ".class" resource and strip the entry part.
        // 回退途径：查找类自身的 ".class" 资源 URL，再剥离条目路径部分。
        if (location == null) {
            String resourcePath = clazz.getName().replace('.', '/') + ".class";
            ClassLoader cl = clazz.getClassLoader();
            location = (cl != null)
                    ? cl.getResource(resourcePath)
                    : ClassLoader.getSystemResource(resourcePath);
            if (location == null) {
                return null;
            }
        }

        return urlToFile(location);
    }

    /**
     * Same as {@link #getContainingFile(Class)} but only returns a value when the
     * container is an actual jar file; returns {@code null} for directories (dev env).
     * <p>
     * 与 {@link #getContainingFile(Class)} 相同，但仅当容器确实是 jar 文件时才返回；
     * 若为目录（开发环境）则返回 {@code null}。
     *
     * @param clazz the class whose jar should be located / 需要定位 jar 的类
     * @return the containing jar file, or {@code null} / 所在 jar 文件，或 {@code null}
     */
    public static File getContainingJar(Class<?> clazz) {
        File container = getContainingFile(clazz);
        return (container != null && container.isFile()) ? container : null;
    }

    /**
     * Checks whether the given class is running from a packaged jar
     * (as opposed to a classes directory in a development workspace).
     * <p>
     * 判断给定类是否运行于打包后的 jar 中（区别于开发环境下的 class 目录）。
     *
     * @param clazz the class to check / 待检查的类
     * @return {@code true} if loaded from a jar file / 若从 jar 文件加载则为 {@code true}
     */
    public static boolean isRunningFromJar(Class<?> clazz) {
        return getContainingJar(clazz) != null;
    }

    /**
     * Converts a {@code file:} or {@code jar:file:...!/...} URL into a local {@link File}.
     * Handles URL-encoded characters (e.g. spaces, CJK paths on Windows).
     * <p>
     * 将 {@code file:} 或 {@code jar:file:...!/...} 形式的 URL 转换为本地 {@link File}。
     * 会正确处理 URL 编码字符（如空格、Windows 下的中文路径）。
     *
     * @param url the URL to convert / 待转换的 URL
     * @return the local file, or {@code null} if the protocol is unsupported
     *         （本地文件；协议不受支持时返回 {@code null}）
     */
    public static File urlToFile(URL url) {
        if (url == null) {
            return null;
        }
        try {
            String protocol = url.getProtocol();
            if ("jar".equalsIgnoreCase(protocol)) {
                // "jar:file:/path/outer.jar!/entry" -> recurse on the inner "file:" part.
                // "jar:file:/path/outer.jar!/entry" —— 截取 "!/" 之前的内层 "file:" URL 递归处理。
                String spec = url.getPath();
                int separator = spec.indexOf("!/");
                if (separator >= 0) {
                    spec = spec.substring(0, separator);
                }
                return urlToFile(new URL(spec));
            }
            if ("file".equalsIgnoreCase(protocol)) {
                try {
                    return new File(url.toURI());
                } catch (URISyntaxException e) {
                    // Some loaders produce URLs with unescaped characters; decode manually.
                    // 某些类加载器生成的 URL 含未转义字符，此处手动解码兜底。
                    return new File(URLDecoder.decode(url.getPath(), "UTF-8"));
                }
            }
        } catch (Exception ignored) {
            // Fall through and report as unresolvable. / 解析失败统一返回 null。
        }
        return null;
    }
}
