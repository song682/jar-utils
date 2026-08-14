package io.qzz.dfdvdsf.jarfile;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a loaded mod's version against an expectation expressed as a version
 * spec with an optional comparison operator, e.g. {@code ">=1.2.3"},
 * {@code "<2.0"}, {@code "==1.0.0"} or a bare version {@code "1.0.0"} (which
 * means equality). The mod is looked up by modid in the FML mod list, and —
 * when a name is given — its display name must match as well.
 * <p>
 * This class is a pure JDK (Java 8) utility with no compile-time Minecraft or
 * FML dependency: the loaded-mod lookup goes to
 * {@code cpw.mods.fml.common.Loader} purely through reflection, and the
 * version ordering is implemented here in plain Java, mirroring the spirit of
 * Forge's own {@code ComparableVersion} — dot/dash/underscore-separated
 * segments compare numerically when both are numbers, known pre-release
 * qualifiers ({@code alpha < beta < rc < snapshot}) sort below release, and
 * unknown qualifiers sort above them. Call it only after mods are loaded;
 * outside a running FML environment (or before load completes) the lookup
 * finds nothing and the check returns {@code false}. For tests, the lookup
 * can be replaced via {@link #setLookup(Lookup)}.
 * <p>
 * 将一个已加载模组的版本与带有可选比较操作符的版本表达式进行对照，
 * 如 {@code ">=1.2.3"}、{@code "<2.0"}、{@code "==1.0.0"} 或裸版本
 * {@code "1.0.0"}（表示相等）。模组按 modid 在 FML 模组列表中查找；
 * 若同时给了名字，还要求其显示名一致。
 * <p>
 * 本类为纯 JDK（Java 8）工具，编译期不依赖任何 Minecraft 或 FML 类：
 * 已加载模组的查询完全通过反射访问
 * {@code cpw.mods.fml.common.Loader} 完成，版本排序规则也用纯 Java
 * 实现，对齐 Forge 自带 {@code ComparableVersion} 的语义——按点号/
 * 连字符/下划线分段，两侧均为数字时按数值比较，已知预发布限定词
 * （{@code alpha < beta < rc < snapshot}）排在正式版之前，未知限定词
 * 排在正式版之后。请在模组加载完成后调用；FML 环境之外（或加载完成前）
 * 查询不到任何模组，判断一律返回 {@code false}。测试场景下可经
 * {@link #setLookup(Lookup)} 替换查询来源。
 */
public final class ModVersions {

    // Optional comparison operator followed by the expected version, with
    // surrounding whitespace tolerated, e.g. ">= 1.2.3". The version must
    // start with a letter or digit, so a lone operator like ">=" stays
    // unparseable.
    // 可选的比较操作符后接期望版本，允许两端空白，如 ">= 1.2.3"。
    // 版本须以字母或数字开头，因此 ">=" 这类孤立操作符无法解析。
    private static final Pattern VERSION_SPEC =
            Pattern.compile("^\\s*(>=|<=|==|=|>|<)?\\s*([0-9A-Za-z][^\\s]*)\\s*$");

    // Version segments are split on dot/dash/underscore/space, e.g. "1.0.0-beta".
    // 版本分段以点号/连字符/下划线/空格为界，如 "1.0.0-beta"。
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[.\\-_ ]");

    // Pre-release qualifiers rank below release; smaller rank = older.
    // 预发布限定词的序号低于正式版；序号越小版本越旧。
    private static final int RANK_ALPHA = 0;
    private static final int RANK_BETA = 1;
    private static final int RANK_RC = 2;
    private static final int RANK_SNAPSHOT = 3;
    private static final int RANK_RELEASE = 4;
    private static final int RANK_UNKNOWN_QUALIFIER = 5;

    /**
     * Pluggable source of {@code modid → (name, version)} for loaded mods.
     * The default implementation reflects into FML's {@code Loader}; tests
     * may substitute a fixed table via {@link #setLookup(Lookup)}.
     * <p>
     * 已加载模组 {@code modid → (名字, 版本)} 的可插拔查询来源。
     * 默认实现经反射访问 FML 的 {@code Loader}；测试可经
     * {@link #setLookup(Lookup)} 换成固定表。
     */
    @FunctionalInterface
    public interface Lookup {

        /**
         * Returns {@code {displayName, version}} of the loaded mod with the
         * given modid, or {@code null} when no such mod is loaded.
         * <p>
         * 返回给定 modid 的已加载模组的 {@code {显示名, 版本}}；
         * 该模组未加载时返回 {@code null}。
         *
         * @param modId the modid to look up / 待查找的 modid
         * @return name and version pair, or {@code null}
         *         （名字与版本二元组，或 {@code null}）
         */
        @Nullable
        String[] lookup(String modId);
    }

    // volatile: the lookup can be swapped from any thread (tests).
    // volatile：查询来源可在任意线程被替换（测试）。
    private static volatile Lookup lookup = ModVersions::lookupViaFml;

    private ModVersions() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Checks whether the version of the loaded mod identified by
     * {@code modId} (and, when {@code modName} is non-null, also by its
     * display name, case-insensitively) satisfies the given version spec.
     * Supported operators: {@code >}, {@code <}, {@code ==} (or {@code =}),
     * {@code >=}, {@code <=}; a spec without operator means equality.
     * Returns {@code false} when the mod is not loaded, the name does not
     * match, or the spec cannot be parsed.
     * <p>
     * 判断以 {@code modId} 标识的已加载模组（当 {@code modName} 非空时，
     * 还要求其显示名一致，大小写不敏感）的版本是否满足给定版本表达式。
     * 支持的操作符：{@code >}、{@code <}、{@code ==}（或 {@code =}）、
     * {@code >=}、{@code <=}；不带操作符的表达式表示相等。
     * 模组未加载、名字不匹配或表达式无法解析时返回 {@code false}。
     *
     * @param modName     expected display name, or {@code null} to skip the
     *                    name check / 期望的显示名；{@code null} 表示不校验名字
     * @param modId       the modid to look up / 待查找的 modid
     * @param versionSpec the expected version with an optional operator, e.g.
     *                    {@code ">=1.2.3"} / 带可选操作符的期望版本，如 {@code ">=1.2.3"}
     * @return {@code true} if such a mod is loaded and its version satisfies
     *         the spec / 该模组已加载且版本满足表达式时为 {@code true}
     */
    public static boolean versionMatches(@Nullable String modName, String modId, String versionSpec) {
        if (modId == null || modId.isEmpty()) {
            return false;
        }
        String[] mod = lookup.lookup(modId);
        if (mod == null || mod.length < 2) {
            return false;
        }
        // Name check is case-insensitive: display names are for humans.
        // 名字校验大小写不敏感：显示名是给人看的。
        if (modName != null && !modName.isEmpty()
                && !modName.equalsIgnoreCase(mod[0])) {
            return false;
        }
        return matches(mod[1], versionSpec);
    }

    /**
     * Tests an actual version string against a version spec with an optional
     * comparison operator ({@code >}, {@code <}, {@code ==}/{@code =},
     * {@code >=}, {@code <=}); a bare version means equality. This is the
     * pure logic behind {@link #versionMatches(String, String, String)},
     * usable without any loaded-mod lookup.
     * <p>
     * 用实际版本串对照带可选比较操作符（{@code >}、{@code <}、
     * {@code ==}/{@code =}、{@code >=}、{@code <=}）的版本表达式；
     * 裸版本表示相等。这是 {@link #versionMatches(String, String, String)}
     * 背后的纯逻辑，无需任何已加载模组查询即可使用。
     *
     * @param actualVersion the version to test, e.g. a loaded mod's version
     *                      / 待测试的版本，如已加载模组的版本
     * @param versionSpec   the expectation, e.g. {@code ">=1.2.3"}
     *                      / 期望表达式，如 {@code ">=1.2.3"}
     * @return {@code true} if the version satisfies the spec; {@code false}
     *         for {@code null} inputs or an unparseable spec
     *         （版本满足表达式时为 {@code true}；输入为 {@code null}
     *         或表达式无法解析时为 {@code false}）
     */
    public static boolean matches(@Nullable String actualVersion, String versionSpec) {
        if (actualVersion == null || versionSpec == null) {
            return false;
        }
        Matcher m = VERSION_SPEC.matcher(versionSpec);
        if (!m.matches()) {
            return false;
        }
        String op = m.group(1) != null ? m.group(1) : "==";
        int cmp = compare(actualVersion, m.group(2));
        switch (op) {
            case ">=": return cmp >= 0;
            case "<=": return cmp <= 0;
            case ">":  return cmp > 0;
            case "<":  return cmp < 0;
            default:   return cmp == 0;
        }
    }

    /**
     * Compares two non-null version strings segment by segment. Segments are
     * the pieces between {@code .}, {@code -}, {@code _} and spaces; two
     * numeric segments compare numerically (so {@code 1.10} is newer than
     * {@code 1.9}), a numeric segment is always newer than a qualifier, and
     * qualifiers rank {@code alpha < beta < rc < snapshot < release}, with
     * unknown qualifiers above release. A leading {@code v}/{@code V} is
     * ignored, comparison is case-insensitive, and missing trailing segments
     * count as release quality (so {@code 1.0.0} equals {@code 1.0.0-release}
     * and zero padding such as {@code 1.0} vs {@code 1.0.0} is equal, but
     * {@code 1.0.0} is newer than {@code 1.0.0-beta}).
     * <p>
     * 逐段比较两个非空版本串。段是 {@code .}、{@code -}、{@code _}
     * 与空格之间的片段；两段均为数字时按数值比较（故 {@code 1.10} 新于
     * {@code 1.9}），数字段恒新于限定词，限定词排序为
     * {@code alpha < beta < rc < snapshot < release}，未知限定词排在
     * 正式版之后。忽略开头的 {@code v}/{@code V}，比较大小写不敏感，
     * 缺失的末尾段按正式版计（故 {@code 1.0.0} 等于 {@code 1.0.0-release}，
     * {@code 1.0} 与 {@code 1.0.0} 的零填充也相等，但 {@code 1.0.0}
     * 新于 {@code 1.0.0-beta}）。
     *
     * @param versionA the left-hand version, non-null / 左侧版本，非空
     * @param versionB the right-hand version, non-null / 右侧版本，非空
     * @return negative, zero or positive as {@code versionA} is less than,
     *         equal to or greater than {@code versionB}
     *         （{@code versionA} 小于、等于或大于 {@code versionB}
     *         时返回负数、零或正数）
     */
    public static int compare(String versionA, String versionB) {
        String[] a = segments(versionA);
        String[] b = segments(versionB);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            // Missing trailing segments behave like "release".
            // 缺失的末尾段按 "release" 处理。
            String sa = i < a.length ? a[i] : null;
            String sb = i < b.length ? b[i] : null;
            int cmp = compareSegment(sa, sb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Replaces the loaded-mod lookup, mainly for tests. Pass {@code null} to
     * restore the reflective FML lookup.
     * <p>
     * 替换已加载模组的查询来源，主要供测试使用。传 {@code null}
     * 恢复默认的反射 FML 查询。
     *
     * @param custom the new lookup, or {@code null} for the default
     *               / 新的查询来源；{@code null} 表示恢复默认
     */
    public static void setLookup(@Nullable Lookup custom) {
        lookup = custom != null ? custom : ModVersions::lookupViaFml;
    }

    // === === === Internals / 内部实现 === === ===

    /**
     * Default lookup: reads FML's
     * {@code Loader.instance().getIndexedModList()} reflectively, so this
     * class stays compilable without any FML jar. Returns {@code null} when
     * FML is absent, not yet initialized, or the modid is unknown.
     * <p>
     * 默认查询：经反射读取 FML 的
     * {@code Loader.instance().getIndexedModList()}，使本类在无 FML jar
     * 时也能编译。FML 不存在、尚未初始化或 modid 未知时返回 {@code null}。
     */
    @Nullable
    private static String[] lookupViaFml(String modId) {
        try {
            Class<?> loaderClass = Class.forName("cpw.mods.fml.common.Loader");
            Object loader = loaderClass.getMethod("instance").invoke(null);
            Map<?, ?> mods = (Map<?, ?>) loaderClass.getMethod("getIndexedModList").invoke(loader);
            if (mods == null) {
                return null;
            }
            Object mod = mods.get(modId);
            if (mod == null) {
                return null;
            }
            String name = String.valueOf(mod.getClass().getMethod("getName").invoke(mod));
            String version = String.valueOf(mod.getClass().getMethod("getVersion").invoke(mod));
            return new String[]{name, version};
        } catch (ReflectiveOperationException | RuntimeException e) {
            // No FML, loader not ready, or an unexpected shape: treat as unloaded.
            // FML 不存在、加载器未就绪或结构不符：一律按未加载处理。
            return null;
        }
    }

    /**
     * Normalizes a version string and splits it into comparable segments:
     * trims whitespace, strips a leading {@code v}/{@code V} and lowers the
     * case. / 归一化版本串并切成可比较的段：去两端空白、剥掉开头的
     * {@code v}/{@code V}、统一小写。
     */
    private static String[] segments(String version) {
        String v = version.trim();
        if (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')
                && Character.isDigit(v.charAt(1))) {
            v = v.substring(1);
        }
        return SEGMENT_SPLIT.split(v.toLowerCase(Locale.ROOT));
    }

    /**
     * Compares a single segment pair; a {@code null} segment stands in for a
     * missing trailing segment, which ranks as release quality.
     * <p>
     * 比较单个段对；{@code null} 代表缺失的末尾段，按正式版排序。
     */
    private static int compareSegment(@Nullable String sa, @Nullable String sb) {
        boolean na = sa != null && isNumeric(sa);
        boolean nb = sb != null && isNumeric(sb);
        if (na && nb) {
            // Compare numerically without overflow worries of parsing.
            // 按数值比较，避免直接解析带来的溢出顾虑。
            return compareNumeric(sa, sb);
        }
        if (na) {
            // A number beats any qualifier; a missing segment counts as zero
            // padding, so "1.0" == "1" but "1.0.1" > "1".
            // 数字段胜过任何限定词；缺失段按零填充计，
            // 故 "1.0" == "1"，但 "1.0.1" > "1"。
            return sb == null ? (isAllZeros(sa) ? 0 : 1) : 1;
        }
        if (nb) {
            return sa == null ? (isAllZeros(sb) ? 0 : -1) : -1;
        }
        int cmp = Integer.compare(rankOf(sa), rankOf(sb));
        if (cmp != 0) {
            return cmp;
        }
        // Same qualifier family (e.g. rc1 vs rc2): the numeric suffix decides;
        // a missing suffix counts as zero.
        // 同一限定词族（如 rc1 与 rc2）：由数字后缀决定；缺失后缀按零计。
        return compareNumeric(trailingDigits(sa), trailingDigits(sb));
    }

    /**
     * Ranks a qualifier segment; {@code null} (missing) counts as release,
     * and a trailing number (as in {@code rc1}, {@code beta2}) is ignored
     * here — it is compared separately by {@link #compareSegment}.
     * <p>
     * 给限定词段定序；{@code null}（缺失）按正式版计，末尾数字
     * （如 {@code rc1}、{@code beta2}）在此忽略——由
     * {@link #compareSegment} 另行比较。
     */
    private static int rankOf(@Nullable String segment) {
        if (segment == null || segment.isEmpty()) {
            return RANK_RELEASE;
        }
        String key = segment.replaceAll("\\d+$", "");
        switch (key) {
            case "alpha": case "a": return RANK_ALPHA;
            case "beta": case "b":  return RANK_BETA;
            case "rc": case "pre":  return RANK_RC;
            case "snapshot":        return RANK_SNAPSHOT;
            case "release": case "final": case "stable": return RANK_RELEASE;
            default:                return RANK_UNKNOWN_QUALIFIER;
        }
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /**
     * Whether a digit-only segment is zero padding ({@code "0"}, {@code "00"}).
     * 纯数字段是否为零填充（{@code "0"}、{@code "00"}）。
     */
    private static boolean isAllZeros(@Nullable String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }

    /**
     * The trailing digits of a segment ({@code "rc12"} → {@code "12"}),
     * {@code "0"} when it has none or is {@code null}.
     * <p>
     * 段的末尾数字部分（{@code "rc12"} → {@code "12"}）；
     * 没有数字或段为 {@code null} 时返回 {@code "0"}。
     */
    private static String trailingDigits(@Nullable String s) {
        if (s == null) {
            return "0";
        }
        int i = s.length();
        while (i > 0 && Character.isDigit(s.charAt(i - 1))) {
            i--;
        }
        return i == s.length() ? "0" : s.substring(i);
    }

    /**
     * Numeric comparison of two digit-only strings: first by significant
     * length (leading zeros stripped), then lexicographically — no long
     * parsing needed, so arbitrarily long segments are safe.
     * <p>
     * 对两个纯数字串做数值比较：先比去掉前导零后的有效长度，再按字典序
     * 比——无需 long 解析，任意长度的段都安全。
     */
    private static int compareNumeric(String sa, String sb) {
        String a = stripLeadingZeros(sa);
        String b = stripLeadingZeros(sb);
        int cmp = Integer.compare(a.length(), b.length());
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static String stripLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
