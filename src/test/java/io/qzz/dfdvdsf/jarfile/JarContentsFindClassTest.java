package io.qzz.dfdvdsf.jarfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JarContents#findClassEntries(File, String)}: exact
 * lookup, bare-name scanning, suffix-tolerant input, dev-workspace directory
 * containers, and a mod-style scenario of locating a class across several mod
 * jars. Tests build real jar files via {@link ZipOutputStream} and require no
 * Minecraft runtime.
 * <p>
 * {@link JarContents#findClassEntries(File, String)} 的单元测试：精确查找、
 * 裸名扫描、后缀容错输入、开发环境目录容器，以及跨多个 mod jar 定位类的
 * mod 风格场景。测试通过 {@link ZipOutputStream} 构造真实 jar 文件，
 * 不依赖 Minecraft 运行时。
 */
public class JarContentsFindClassTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // === === === 精确查找 === === ===

    /**
     * A fully qualified name resolves to its exact bytecode entry.
     * <p>
     * 完全限定名解析到其精确的字节码条目。
     */
    @Test
    public void testExactMatchFullyQualifiedName() throws IOException {
        File jar = tempFolder.newFile("exact.jar");
        writeJar(jar, entries(
                "com/example/Foo.class", bytes("fake bytecode"),
                "com/example/Foo.java", bytes("source"),
                "assets/mod/lang/en_US.lang", bytes("key=value")));

        List<String> found = JarContents.findClassEntries(jar, "com.example.Foo");
        assertEquals(Arrays.asList("com/example/Foo.class"), found);
    }

    /**
     * A trailing {@code .class} suffix on the input is accepted and ignored.
     * <p>
     * 输入末尾的 {@code .class} 后缀被接受并忽略。
     */
    @Test
    public void testExactMatchAcceptsClassSuffix() throws IOException {
        File jar = tempFolder.newFile("suffix.jar");
        writeJar(jar, entries("com/example/Foo.class", bytes("fake bytecode")));

        assertEquals(Arrays.asList("com/example/Foo.class"),
                JarContents.findClassEntries(jar, "com.example.Foo.class"));
    }

    /**
     * When the bytecode entry is absent, the source entry is the fallback.
     * <p>
     * 字节码条目缺失时，回退到源码条目。
     */
    @Test
    public void testExactMatchFallsBackToSource() throws IOException {
        File jar = tempFolder.newFile("source.jar");
        writeJar(jar, entries("com/example/Foo.java", bytes("source")));

        List<String> found = JarContents.findClassEntries(jar, "com.example.Foo");
        assertEquals(Arrays.asList("com/example/Foo.java"), found);
    }

    /**
     * Inner classes ($ in the binary name) are addressed like any other entry.
     * <p>
     * 内部类（二进制名含 $）与普通条目同样处理。
     */
    @Test
    public void testExactMatchInnerClass() throws IOException {
        File jar = tempFolder.newFile("inner.jar");
        writeJar(jar, entries("com/example/Foo$Inner.class", bytes("fake bytecode")));

        assertEquals(Arrays.asList("com/example/Foo$Inner.class"),
                JarContents.findClassEntries(jar, "com.example.Foo$Inner"));
    }

    // === === === 裸名模糊扫描 === === ===

    /**
     * A bare class name (no package part) scans the whole jar for every entry
     * ending with {@code Name.class} / {@code Name.java}, in entry order.
     * <p>
     * 裸类名（不含包名）扫描整个 jar，返回所有以 {@code Name.class} /
     * {@code Name.java} 结尾的条目，顺序与条目顺序一致。
     */
    @Test
    public void testBareNameScansAllEntries() throws IOException {
        File jar = tempFolder.newFile("scan.jar");
        writeJar(jar, entries(
                "com/example/Foo.class", bytes("fake bytecode"),
                "org/other/Foo.class", bytes("fake bytecode"),
                "com/example/Foo.java", bytes("source"),
                "org/other/Bar.class", bytes("fake bytecode")));

        List<String> found = JarContents.findClassEntries(jar, "Foo");
        assertEquals(Arrays.asList(
                "com/example/Foo.class",
                "org/other/Foo.class",
                "com/example/Foo.java"), found);
    }

    /**
     * The exact match wins over the scan: a root-level {@code Foo.class} entry
     * is returned alone even when other {@code Foo.class} entries exist.
     * <p>
     * 精确匹配优先于扫描：根目录存在 {@code Foo.class} 条目时单独返回它，
     * 即使 jar 中还存在其它 {@code Foo.class} 条目。
     */
    @Test
    public void testBareNameRootEntryExactWin() throws IOException {
        File jar = tempFolder.newFile("root.jar");
        writeJar(jar, entries(
                "Foo.class", bytes("fake bytecode"),
                "com/example/Foo.class", bytes("fake bytecode")));

        assertEquals(Arrays.asList("Foo.class"), JarContents.findClassEntries(jar, "Foo"));
    }

    // === === === 无匹配与边界输入 === === ===

    @Test
    public void testNoMatchReturnsEmpty() throws IOException {
        File jar = tempFolder.newFile("none.jar");
        writeJar(jar, entries(
                "com/example/Bar.class", bytes("fake bytecode"),
                "assets/mod/lang/en_US.lang", bytes("key=value")));

        assertTrue(JarContents.findClassEntries(jar, "com.example.Foo").isEmpty());
        assertTrue(JarContents.findClassEntries(jar, "Foo").isEmpty());
    }

    @Test
    public void testNullInputs() throws IOException {
        File jar = tempFolder.newFile("null.jar");
        writeJar(jar, entries("com/example/Foo.class", bytes("fake bytecode")));

        assertTrue(JarContents.findClassEntries(null, "com.example.Foo").isEmpty());
        assertTrue(JarContents.findClassEntries(jar, null).isEmpty());
        assertTrue(JarContents.findClassEntries(null, null).isEmpty());
    }

    @Test
    public void testBlankInput() throws IOException {
        File jar = tempFolder.newFile("blank.jar");
        writeJar(jar, entries("com/example/Foo.class", bytes("fake bytecode")));

        assertTrue(JarContents.findClassEntries(jar, "").isEmpty());
        assertTrue(JarContents.findClassEntries(jar, "   ").isEmpty());
        assertTrue(JarContents.findClassEntries(jar, ".class").isEmpty());
    }

    // === === === 开发环境目录容器 === === ===

    /**
     * Dev-workspace container: a classes directory holds the class as a plain
     * file, addressed by the same '/'-separated relative name.
     * <p>
     * 开发环境容器：class 输出目录中的类以普通文件存放，使用相同的 '/' 分隔
     * 相对名寻址。
     */
    @Test
    public void testDevDirectoryContainer() throws IOException {
        File classesDir = tempFolder.newFolder("classes");
        File pkgDir = new File(classesDir, "com/example");
        assertTrue(pkgDir.mkdirs());
        writeFile(new File(pkgDir, "Foo.class"), bytes("fake bytecode"));

        assertEquals(Arrays.asList("com/example/Foo.class"),
                JarContents.findClassEntries(classesDir, "com.example.Foo"));
    }

    // === === === 集成场景：跨 mod jar 定位类 === === ===

    /**
     * Mirrors a typical mod-side usage: scan the mods directory, then ask each
     * mod jar which one provides a given class — the "which jar has this class"
     * problem the jarfinder tool was originally built for.
     * <p>
     * 模拟 mod 侧的典型用法：扫描 mods 目录，然后逐个询问每个 mod jar 是否
     * 提供指定类 —— 即 jarfinder 工具最初要解决的"哪个 jar 里有这个类"问题。
     */
    @Test
    public void testModsDirectoryScenario() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        writeJar(new File(modsDir, "AlphaMod-1.0.0.jar"), entries(
                "com/example/api/ModApi.class", bytes("fake bytecode"),
                "META-INF/mcmod.info", bytes("{\"modid\":\"alpha\"}")));
        writeJar(new File(modsDir, "BetaMod-2.0.0.jar"), entries(
                "com/other/util/Helper.class", bytes("fake bytecode"),
                "META-INF/mcmod.info", bytes("{\"modid\":\"beta\"}")));
        writeJar(new File(modsDir, "GammaLib-0.3.jar"), entries(
                "com/example/api/ModApi.class", bytes("fake bytecode"),
                "META-INF/mcmod.info", bytes("{\"modid\":\"gamma\"}")));

        List<File> providers = new java.util.ArrayList<File>();
        File[] jars = modsDir.listFiles();
        for (File jar : jars) {
            if (!JarNames.isJarFile(jar)) {
                continue;
            }
            if (!JarContents.findClassEntries(jar, "com.example.api.ModApi").isEmpty()) {
                providers.add(jar);
            }
        }

        assertEquals(2, providers.size());
        assertEquals("AlphaMod-1.0.0.jar", providers.get(0).getName());
        assertEquals("GammaLib-0.3.jar", providers.get(1).getName());
    }

    // === === === helpers / 辅助 === === ===

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entries(String name1, byte[] content1) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        map.put(name1, content1);
        return map;
    }

    private static Map<String, byte[]> entries(String name1, byte[] content1,
            String name2, byte[] content2) {
        Map<String, byte[]> map = new HashMap<String, byte[]>();
        map.put(name1, content1);
        map.put(name2, content2);
        return map;
    }

    private static Map<String, byte[]> entries(String name1, byte[] content1,
            String name2, byte[] content2, String name3, byte[] content3) {
        Map<String, byte[]> map = entries(name1, content1, name2, content2);
        map.put(name3, content3);
        return map;
    }

    private static Map<String, byte[]> entries(String name1, byte[] content1,
            String name2, byte[] content2, String name3, byte[] content3,
            String name4, byte[] content4) {
        Map<String, byte[]> map = entries(name1, content1, name2, content2);
        map.put(name3, content3);
        map.put(name4, content4);
        return map;
    }

    private static void writeJar(File file, Map<String, byte[]> entries) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    private static void writeFile(File file, byte[] content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
    }
}
