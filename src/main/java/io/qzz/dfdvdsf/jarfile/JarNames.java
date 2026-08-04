package io.qzz.dfdvdsf.jarfile;

import java.io.File;
import java.util.Locale;

/**
 * File-name helpers for jars: plain disk file names, names without the jar/zip
 * extension, and jar-suffix checks.
 * <p>
 * This class is a pure JDK utility with no Minecraft dependencies: it is
 * stateless, thread-safe, and can be used by any mod as a drop-in backup
 * utility library, e.g. for scanning mod directories or deriving display names
 * from jar file names.
 * <p>
 * 面向 jar 文件名的辅助工具：磁盘文件名、去掉 jar/zip 扩展名后的名称、jar 后缀判断。
 * <p>
 * 本类为纯 JDK 工具，不依赖任何 Minecraft 类：无状态、线程安全，
 * 可作为任意 mod 直接调用的后备工具库，例如用于扫描 mod 目录、
 * 或从 jar 文件名推导显示名。
 */
public final class JarNames {

    private static final String JAR_SUFFIX = ".jar";
    private static final String ZIP_SUFFIX = ".zip";

    private JarNames() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Returns the plain disk file name (without the parent path) of the given
     * file, e.g. {@code "MyMod-1.0.0.jar"}. {@code null} input yields
     * {@code null}.
     * <p>
     * 返回给定文件的磁盘文件名（不含父路径），如 {@code "MyMod-1.0.0.jar"}。
     * 输入为 {@code null} 时返回 {@code null}。
     *
     * @param file the file to inspect / 待检查的文件
     * @return the file name, or {@code null} / 文件名，或 {@code null}
     */
    public static String fileName(File file) {
        return file != null ? file.getName() : null;
    }

    /**
     * Returns the file name without the trailing {@code .jar}/{@code .zip}
     * extension (case-insensitive), e.g. {@code "MyMod-1.0.0.jar"} →
     * {@code "MyMod-1.0.0"}. Names that do not end with a jar/zip suffix are
     * returned unchanged, so dots elsewhere in the name are preserved.
     * <p>
     * 返回去掉末尾 {@code .jar}/{@code .zip} 扩展名（大小写不敏感）后的文件名，
     * 如 {@code "MyMod-1.0.0.jar"} → {@code "MyMod-1.0.0"}。
     * 非 jar/zip 后缀的名称原样返回，名称中其它位置的点号会被保留。
     *
     * @param file the file to inspect / 待检查的文件
     * @return the name without the jar/zip extension, or {@code null}
     *         （去掉 jar/zip 扩展名后的名称，或 {@code null}）
     */
    public static String fileNameWithoutExtension(File file) {
        return stripJarExtension(fileName(file));
    }

    /**
     * String variant of {@link #fileNameWithoutExtension(File)} for names that
     * come from jar entries or other non-File sources, e.g.
     * {@code "META-INF/jarjar/lib.jar"} → {@code "META-INF/jarjar/lib"}.
     * <p>
     * {@link #fileNameWithoutExtension(File)} 的字符串版本，用于来自 jar 条目
     * 等非 File 来源的名称，如 {@code "META-INF/jarjar/lib.jar"} →
     * {@code "META-INF/jarjar/lib"}。
     *
     * @param name the file or entry name to strip / 待处理的文件或条目名
     * @return the name without the jar/zip extension, or {@code null}
     *         （去掉 jar/zip 扩展名后的名称，或 {@code null}）
     */
    public static String stripJarExtension(String name) {
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(JAR_SUFFIX) || lower.endsWith(ZIP_SUFFIX)) {
            return name.substring(0, name.length() - JAR_SUFFIX.length());
        }
        return name;
    }

    /**
     * Checks whether the given name ends with a jar or zip suffix,
     * case-insensitively. / 判断给定名称是否以 jar 或 zip 后缀结尾（大小写不敏感）。
     *
     * @param name the name to check / 待检查的名称
     * @return {@code true} if the name is jar/zip-like / 形如 jar/zip 则为 {@code true}
     */
    public static boolean isJarLike(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(JAR_SUFFIX) || lower.endsWith(ZIP_SUFFIX);
    }

    /**
     * Checks whether the given file actually exists on disk and its name is
     * jar/zip-like. Convenient for scanning directories for mod jars.
     * <p>
     * 判断给定文件是否真实存在于磁盘且名称形如 jar/zip。
     * 便于扫描目录中的 mod jar。
     *
     * @param file the file to check / 待检查的文件
     * @return {@code true} if an existing jar-like file / 存在且形如 jar/zip 则为
     *         {@code true}
     */
    public static boolean isJarFile(File file) {
        return file != null && file.isFile() && isJarLike(file.getName());
    }
}
