package io.qzz.dfdvdsf.jarfile;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives a mod name and version guess from a jar file name, following the
 * de-facto convention {@code ModName-version.jar} (e.g.
 * {@code "MyMod-1.2.3.jar"} → name {@code "MyMod"}, version {@code "1.2.3"}).
 * <p>
 * How it works: the jar/zip extension is stripped first, then a version number
 * is matched from the <em>end</em> of the stem — a number starting with a
 * digit, 1–4 dot-separated segments (e.g. {@code 1.2.3}), an optional leading
 * {@code v} (e.g. {@code v2.0}) and optional pre-release/build suffixes (e.g.
 * {@code -beta}, {@code -SNAPSHOT}, {@code -rc1}). The version must be preceded
 * by a separator ({@code -}, {@code _} or a space) or the start of the name, so
 * digits inside the mod name are never swallowed (e.g. {@code "mod1-1.2.3.jar"}
 * yields {@code 1.2.3}, not {@code 1}). Everything before the version is the
 * name, with any trailing separator removed — since the <em>last</em> version
 * segment is the mod version, {@code "NEI-1.7.10-1.0.4.jar"} yields name
 * {@code "NEI-1.7.10"} and version {@code "1.0.4"}.
 * <p>
 * This class is a pure JDK utility with no Minecraft dependencies: it is
 * stateless, thread-safe and never touches the file system.
 * <p>
 * 依据事实约定 {@code ModName-version.jar}，从 jar 文件名推导 mod 名字与版本
 * （如 {@code "MyMod-1.2.3.jar"} → 名字 {@code "MyMod"}、版本 {@code "1.2.3"}）。
 * <p>
 * 做法：先剥掉 jar/zip 扩展名，再从主干末尾匹配版本号——以数字开头、由点号分隔
 * 的 1~4 段数字（如 {@code 1.2.3}），可选前导 {@code v}（如 {@code v2.0}），
 * 可选预发布/构建后缀（如 {@code -beta}、{@code -SNAPSHOT}、{@code -rc1}）。
 * 版本号之前必须是分隔符（{@code -}、{@code _} 或空格）或名称开头，因此名字中
 * 的数字永远不会被误吞（如 {@code "mod1-1.2.3.jar"} 提取 {@code 1.2.3} 而非
 * {@code 1}）。版本之前的部分即为名字，并去掉尾部多余分隔符——由于<em>最后</em>
 * 一个版本段才是 mod 版本，{@code "NEI-1.7.10-1.0.4.jar"} 会得到名字
 * {@code "NEI-1.7.10"} 与版本 {@code "1.0.4"}。
 * <p>
 * 本类为纯 JDK 工具，不依赖任何 Minecraft 类：无状态、线程安全，绝不触碰文件系统。
 */
public final class JarVersionGuesser {

    // Suffix segments must start with a letter (-beta, -SNAPSHOT, -rc1, -B9):
    // pure digit segments like ".4" belong to the version body, and "-1.0.4"
    // after a MC version forces the match onto the LAST version segment, so
    // "NEI-1.7.10-1.0.4.jar" yields version "1.0.4", not "1.7.10-1.0.4".
    // 后缀段必须以字母开头（-beta、-SNAPSHOT、-rc1、-B9）：纯数字段如 ".4"
    // 属于版本主体，而 MC 版本后的 "-1.0.4" 会把匹配推到最后一个版本段上，
    // 因此 "NEI-1.7.10-1.0.4.jar" 得到版本 "1.0.4" 而非 "1.7.10-1.0.4"。
    private static final Pattern VERSION_AT_END = Pattern.compile(
            "(?:[-_. ]|^)(?:v)?(\\d+(?:\\.\\d+){1,3}(?:[-_][A-Za-z][0-9A-Za-z]*)*)$");

    private JarVersionGuesser() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * The guessed mod name and version from a jar file name. Either field may be
     * {@code null}: the version is {@code null} when the name carries no trailing
     * version segment, and the name is {@code null} when the whole stem is a
     * version (e.g. {@code "1.2.3.jar"}).
     * <p>
     * 从 jar 文件名猜测得到的 mod 名字与版本。任一字段都可能为 {@code null}：
     * 名字末尾无版本段时版本为 {@code null}；主干整体就是版本时（如
     * {@code "1.2.3.jar"}）名字为 {@code null}。
     */
    public static final class Guess {

        private final String name;
        private final String version;

        Guess(String name, String version) {
            this.name = name;
            this.version = version;
        }

        /**
         * The guessed mod name, or {@code null}. / 猜测的 mod 名，可能为 {@code null}。
         */
        public String name() {
            return name;
        }

        /**
         * The guessed version, or {@code null} if none was found.
         * 猜测的版本号；未找到时为 {@code null}。
         */
        public String version() {
            return version;
        }

        /**
         * Whether a version was found. / 是否找到了版本号。
         */
        public boolean hasVersion() {
            return version != null;
        }

        @Override
        public String toString() {
            return "Guess{name='" + name + "', version='" + version + "'}";
        }
    }

    /**
     * Guesses the mod name and version from a jar file name.
     * <p>
     * 从 jar 文件名猜测 mod 名字与版本。
     *
     * @param fileName a jar/zip file name, e.g. {@code "MyMod-1.2.3.jar"}
     *                 （jar/zip 文件名，如 {@code "MyMod-1.2.3.jar"}）
     * @return the guess; never {@code null} / 猜测结果，恒非 {@code null}
     */
    public static Guess guess(String fileName) {
        if (fileName == null) {
            return new Guess(null, null);
        }
        String stem = JarNames.stripJarExtension(fileName);
        Matcher m = VERSION_AT_END.matcher(stem);
        if (!m.find()) {
            return new Guess(stem.isEmpty() ? null : stem, null);
        }
        String version = m.group(1);
        String name = stem.substring(0, m.start()).replaceAll("[-_. ]+$", "");
        return new Guess(name.isEmpty() ? null : name, version);
    }

    /**
     * File variant of {@link #guess(String)}. / {@link #guess(String)} 的 File 版本。
     *
     * @param file the jar file to inspect / 待检查的 jar 文件
     * @return the guess; never {@code null} / 猜测结果，恒非 {@code null}
     */
    public static Guess guess(File file) {
        return guess(JarNames.fileName(file));
    }
}
