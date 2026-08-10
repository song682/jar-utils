package io.qzz.dfdvdsf.source;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Guesses the mod metadata (modid / name / version) of a decompiled source
 * tree, e.g. the {@code build/tmp/recompSrc} output of ForgeGradle or of any
 * other jar decompiler. Sources are scanned in order of trust:
 * <ol>
 *   <li>{@code @Mod} annotations on Java sources — direct string values as well
 *       as references to constants such as {@code Tags.MODID};</li>
 *   <li>{@code mcmod.info} / {@code mcpmod.info} metadata files;</li>
 *   <li>{@code mixins.*.json} file names, which by convention embed the modid.</li>
 * </ol>
 * Fields missing from a higher-priority source are filled in by the next
 * lower-priority one, so a mixed result (e.g. modid from {@code @Mod},
 * version from {@code mcmod.info}) is possible. A decompiled Minecraft/Forge
 * tree that contains no mod at all typically still resolves to the metadata
 * shipped by the toolchain itself (modid {@code mcp} from {@code mcpmod.info}).
 * <p>
 * This class is a pure JDK utility with no Minecraft dependencies: it is
 * stateless, thread-safe, and reads only the given directory tree, never the
 * classpath.
 * <p>
 * 猜测反编译源码树（例如 ForgeGradle 的 {@code build/tmp/recompSrc} 输出或其它
 * jar 反编译产物）对应的模组元数据（modid / 名字 / 版本）。按可信度依次扫描：
 * <ol>
 *   <li>Java 源码上的 {@code @Mod} 注解——支持直接字符串值，也支持对常量的引用，
 *       如 {@code Tags.MODID}；</li>
 *   <li>{@code mcmod.info} / {@code mcpmod.info} 元数据文件；</li>
 *   <li>{@code mixins.*.json} 文件名——按惯例其中嵌有 modid。</li>
 * </ol>
 * 高优先级来源缺失的字段由下一优先级补齐，因此可能出现混合结果
 * （例如 modid 来自 {@code @Mod}、version 来自 {@code mcmod.info}）。
 * 不含任何模组的反编译 Minecraft/Forge 树通常仍能解析出工具链自带的元数据
 * （{@code mcpmod.info} 中的 modid {@code mcp}）。
 * <p>
 * 本类为纯 JDK 工具，不依赖任何 Minecraft 类：无状态、线程安全，
 * 只读取给定目录树，绝不触碰类路径。
 */
public final class ModInfoGuesser {

    private static final String JAVA_SUFFIX = ".java";
    private static final String MCMOD_INFO = "mcmod.info";
    private static final String MCPMOD_INFO = "mcpmod.info";
    private static final String JSON_SUFFIX = ".json";
    private static final String REFMAP_MARKER = "refmap";
    private static final String TEMPLATE_PREFIX = "${";

    private static final Pattern ANNOTATION_ATTR = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*=\\s*(\"([^\"]*)\"|([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*))");
    private static final Pattern VALUE_SHORTHAND = Pattern.compile(
            "\\s*\"([^\"]*)\"");
    private static final Pattern FIELD_ASSIGN = Pattern.compile(
            "([A-Za-z_$][\\w$]*)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern METADATA_FIELD = Pattern.compile(
            "\"(modid|name|version)\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern MIXINS_FILE = Pattern.compile(
            "mixins\\.([A-Za-z0-9_]+)\\.json");

    private ModInfoGuesser() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * The guessed mod metadata. Any field may be {@code null} when no source
     * provided it; {@link #source()} describes where the values came from.
     * <p>
     * 猜测得到的模组元数据。任何字段都可能为 {@code null}（无来源提供该值）；
     * {@link #source()} 描述各值的来源。
     */
    public static final class Guess {

        private final String modid;
        private final String name;
        private final String version;
        private final String source;

        Guess(String modid, String name, String version, String source) {
            this.modid = modid;
            this.name = name;
            this.version = version;
            this.source = source;
        }

        /**
         * The guessed mod id, or {@code null}. / 猜测的 modid，可能为 {@code null}。
         */
        public String modid() {
            return modid;
        }

        /**
         * The guessed display name, or {@code null}. / 猜测的模组名，可能为 {@code null}。
         */
        public String name() {
            return name;
        }

        /**
         * The guessed version, or {@code null}. / 猜测的版本号，可能为 {@code null}。
         */
        public String version() {
            return version;
        }

        /**
         * Human-readable description of where the values were found, e.g.
         * {@code "@Mod in com/example/MyMod.java"} or {@code "mcpmod.info"}.
         * <p>
         * 各值来源的可读描述，例如 {@code "@Mod in com/example/MyMod.java"}
         * 或 {@code "mcpmod.info"}。
         */
        public String source() {
            return source;
        }

        /**
         * Whether at least one field was guessed. / 是否至少猜出一个字段。
         */
        public boolean hasAny() {
            return modid != null || name != null || version != null;
        }

        @Override
        public String toString() {
            return "Guess{modid='" + modid + "', name='" + name + "', version='" + version
                    + "', source='" + source + "'}";
        }
    }

    /**
     * Guesses the mod metadata of the given decompiled source tree.
     * <p>
     * 猜测给定反编译源码树的模组元数据。
     *
     * @param sourceRoot a directory with decompiled sources / 存放反编译源码的目录
     * @return the guess; never {@code null} / 猜测结果，恒非 {@code null}
     */
    public static Guess guess(File sourceRoot) {
        if (sourceRoot == null || !sourceRoot.isDirectory()) {
            return new Guess(null, null, null, "not a directory");
        }
        List<JavaSource> sources = collectJavaSources(sourceRoot);
        List<String> origins = new ArrayList<String>();

        // Phase 1: @Mod annotations, the most trustworthy source.
        // 阶段一：@Mod 注解，最可信的来源。
        String modid = null;
        String name = null;
        String version = null;
        List<String> pendingRefs = new ArrayList<String>();
        for (JavaSource src : sources) {
            String body = src.findModAnnotationBody();
            if (body == null) {
                continue;
            }
            Matcher m = ANNOTATION_ATTR.matcher(body);
            while (m.find()) {
                String key = m.group(1);
                String value = m.group(3);
                String ref = m.group(4);
                if ("modid".equals(key)) {
                    modid = pick(modid, value, ref, pendingRefs);
                } else if ("name".equals(key)) {
                    name = pick(name, value, ref, pendingRefs);
                } else if ("version".equals(key)) {
                    version = pick(version, value, ref, pendingRefs);
                }
            }
            // FML's @Mod has a value() alias for the modid, e.g. @Mod("mymod");
            // the body then starts with the quoted string itself.
            // FML 的 @Mod 为 modid 提供 value() 别名，如 @Mod("mymod")；
            // 此时实参体直接以带引号的字符串开头。
            if (modid == null) {
                Matcher shorthand = VALUE_SHORTHAND.matcher(body);
                if (shorthand.lookingAt()) {
                    modid = shorthand.group(1);
                }
            }
            if (modid != null || name != null || version != null || !pendingRefs.isEmpty()) {
                origins.add("@Mod in " + src.path);
            }
            if (modid != null && name != null && version != null) {
                break;
            }
        }
        // Resolve constant references such as Tags.MODID against all sources.
        // 在所有源码中解析常量引用，如 Tags.MODID。
        for (String ref : pendingRefs) {
            String resolved = resolveConstant(sources, ref);
            if (resolved == null) {
                continue;
            }
            if (modid == null && ref.endsWith("MODID")) {
                modid = resolved;
            } else if (name == null && ref.endsWith("NAME")) {
                name = resolved;
            } else if (version == null && ref.endsWith("VERSION")) {
                version = resolved;
            }
        }

        // Phase 2: mcmod.info / mcpmod.info metadata files fill the gaps.
        // 阶段二：mcmod.info / mcpmod.info 元数据文件补齐缺失字段。
        for (File meta : findFiles(sourceRoot, MCMOD_INFO, MCPMOD_INFO)) {
            String text = readText(meta);
            if (text == null) {
                continue;
            }
            Matcher m = METADATA_FIELD.matcher(text);
            while (m.find()) {
                String value = m.group(2);
                if (isTemplate(value)) {
                    continue;
                }
                if ("modid".equals(m.group(1)) && modid == null) {
                    modid = value;
                } else if ("name".equals(m.group(1)) && name == null) {
                    name = value;
                } else if ("version".equals(m.group(1)) && version == null) {
                    version = value;
                }
            }
            origins.add(meta.getName());
            if (modid != null && name != null && version != null) {
                break;
            }
        }

        // Phase 3: mixins.*.json file names conventionally embed the modid.
        // 阶段三：mixins.*.json 文件名按惯例内嵌 modid。
        if (modid == null) {
            for (File json : findFiles(sourceRoot, JSON_SUFFIX)) {
                Matcher m = MIXINS_FILE.matcher(json.getName());
                if (m.matches() && !json.getName().contains(REFMAP_MARKER)) {
                    modid = m.group(1);
                    origins.add(json.getName());
                    break;
                }
            }
        }

        return new Guess(modid, name, version, joinOrigins(origins));
    }

    /**
     * Records either a direct string value or a constant reference, keeping the
     * first non-null winner per field. / 记录直接字符串值或常量引用，每字段保留首个非空值。
     */
    private static String pick(String current, String direct, String ref, List<String> pendingRefs) {
        if (current != null) {
            return current;
        }
        if (direct != null) {
            return direct;
        }
        if (ref != null && !pendingRefs.contains(ref)) {
            pendingRefs.add(ref);
        }
        return current;
    }

    /**
     * Resolves a constant reference like {@code Tags.MODID} by locating the
     * declaring class and reading the static final string. / 通过定位声明类并读取
     * 静态常量字符串来解析诸如 {@code Tags.MODID} 的常量引用。
     */
    private static String resolveConstant(List<JavaSource> sources, String ref) {
        int dot = ref.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String field = ref.substring(dot + 1);
        String className = ref.substring(0, dot);
        for (JavaSource src : sources) {
            if (src.matchesClass(className)) {
                String value = src.extractField(field);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Recursively collects the .java files of the tree as JavaSource wrappers.
     * 递归收集目录树中的 .java 文件并包装为 JavaSource。
     */
    private static List<JavaSource> collectJavaSources(File root) {
        List<JavaSource> out = new ArrayList<JavaSource>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(root.toPath())) {
            stream.forEach(p -> {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(JAVA_SUFFIX)) {
                    return;
                }
                String text = readText(p.toFile());
                if (text != null) {
                    out.add(new JavaSource(relativePath(root, p.toFile()), text));
                }
            });
        } catch (IOException e) {
            // Unreadable trees yield whatever was readable. / 读取失败的树返回已读到的部分。
        }
        return out;
    }

    /**
     * Finds all files under the root whose name matches one of the given names,
     * or — when a single {@code suffix} is passed — whose name ends with it.
     * 查找根目录下文件名等于给定名称之一的全部文件；若传入的是后缀，则匹配该后缀。
     */
    private static List<File> findFiles(File root, String... namesOrSuffixes) {
        List<File> out = new ArrayList<File>();
        try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(root.toPath())) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String fileName = p.getFileName().toString();
                for (String candidate : namesOrSuffixes) {
                    boolean match = candidate.startsWith(".")
                            ? fileName.endsWith(candidate)
                            : fileName.equals(candidate);
                    if (match) {
                        out.add(p.toFile());
                        return;
                    }
                }
            });
        } catch (IOException e) {
            // Best-effort scan. / 尽力扫描，忽略异常。
        }
        return out;
    }

    private static String relativePath(File root, File file) {
        String path = root.toPath().relativize(file.toPath()).toString();
        return path.replace('\\', '/');
    }

    private static String readText(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isTemplate(String value) {
        return value != null && value.startsWith(TEMPLATE_PREFIX);
    }

    private static String joinOrigins(List<String> origins) {
        if (origins.isEmpty()) {
            return "no mod metadata found";
        }
        StringBuilder sb = new StringBuilder();
        for (String origin : origins) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(origin);
        }
        return sb.toString();
    }

    /**
     * A decompiled .java file plus the helpers to inspect its annotations and
     * constant fields. / 反编译的 .java 文件及其注解、常量字段的检查辅助。
     */
    private static final class JavaSource {

        private final String path;
        private final String text;

        JavaSource(String path, String text) {
            this.path = path;
            this.text = text;
        }

        /**
         * Returns the argument text of the first {@code @Mod(...)} annotation
         * outside comments, or {@code null} if the class has none.
         * 返回首个位于注释之外的 {@code @Mod(...)} 注解的实参文本；无注解返回 {@code null}。
         */
        String findModAnnotationBody() {
            int idx = 0;
            while (true) {
                int start = text.indexOf("@Mod", idx);
                if (start < 0) {
                    return null;
                }
                int open = start + 4;
                while (open < text.length() && Character.isWhitespace(text.charAt(open))) {
                    open++;
                }
                if (open >= text.length() || text.charAt(open) != '(') {
                    // @Mod.Marker or plain mention: skip. / @Mod.Marker 或普通提及：跳过。
                    idx = start + 4;
                    continue;
                }
                if (insideComment(text, start)) {
                    idx = open + 1;
                    continue;
                }
                int depth = 0;
                for (int i = open; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')') {
                        depth--;
                        if (depth == 0) {
                            return text.substring(open + 1, i);
                        }
                    }
                }
                return null; // Unbalanced annotation. / 括号不配对。
            }
        }

        /**
         * Extracts the string value of a constant field such as {@code MODID}.
         * 提取诸如 {@code MODID} 的常量字段的字符串值。
         */
        String extractField(String field) {
            Matcher m = FIELD_ASSIGN.matcher(text);
            while (m.find()) {
                if (field.equals(m.group(1))) {
                    return m.group(2);
                }
            }
            return null;
        }

        /**
         * Whether this source is the class named {@code dotted} — matched either
         * by full package path or by simple file name. / 判断本源码是否为名为
         * {@code dotted} 的类——按完整包路径或简单文件名匹配。
         */
        boolean matchesClass(String dotted) {
            if (dotted.indexOf('.') >= 0) {
                return path.equals(dotted.replace('.', '/') + JAVA_SUFFIX);
            }
            return path.endsWith("/" + dotted + JAVA_SUFFIX) || path.equals(dotted + JAVA_SUFFIX);
        }
    }

    /**
     * Whether the given offset lies inside a line or block comment.
     * 判断给定偏移量是否位于行注释或块注释之内。
     */
    private static boolean insideComment(String text, int pos) {
        int lineStart = text.lastIndexOf('\n', pos);
        int lineComment = text.lastIndexOf("//", pos);
        if (lineComment > lineStart) {
            return true;
        }
        int blockOpen = text.lastIndexOf("/*", pos);
        int blockClose = text.lastIndexOf("*/", pos);
        return blockOpen > blockClose;
    }
}
