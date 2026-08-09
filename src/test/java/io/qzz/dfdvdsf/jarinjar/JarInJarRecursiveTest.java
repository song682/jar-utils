package io.qzz.dfdvdsf.jarinjar;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the recursive jar-in-jar discovery and extraction of
 * {@link JarInJar}: multi-level nesting, deduplication of shared nested jars,
 * cycle safety, dev-workspace directory containers and cache reuse. The tests
 * only exercise the extraction API, so no Minecraft runtime is required.
 * <p>
 * {@link JarInJar} 递归发现与提取的单元测试：多层嵌套、共享嵌套 jar 的去重、
 * 环安全、开发环境目录容器以及缓存复用。测试仅使用提取 API，不依赖
 * Minecraft 运行时。
 */
public class JarInJarRecursiveTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private static final String NESTED = JarInJar.DEFAULT_NESTED_DIR;

    // === === === 多层嵌套递归提取 === === ===

    /**
     * outer(mid(inner)): extraction must descend through every level and return
     * the extracted nested jars in discovery order (outer's nested mid first,
     * then inner). The container itself is not part of the result.
     * <p>
     * outer(mid(inner)) 三层嵌套：提取必须逐层下探，并按发现顺序返回提取出的嵌套
     * jar（先 outer 内嵌的 mid，再 inner）。容器本身不包含在结果中。
     */
    @Test
    public void testRecursiveExtractionThreeLevels() throws IOException {
        byte[] inner = jarBytes("leaf.txt", bytes("leaf"));
        byte[] mid = jarBytes(NESTED + "inner.jar", inner, "mid.txt", bytes("mid"));
        File outer = tempFolder.newFile("outer.jar");
        writeJar(outer, jarBytes(NESTED + "mid.jar", mid, "outer.txt", bytes("outer")));

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(outer, NESTED, cache);

        assertEquals(2, extracted.size());
        assertTrue("first should be mid", extracted.get(0).getName().startsWith("mid-"));
        assertTrue("second should be inner", extracted.get(1).getName().startsWith("inner-"));
        for (File f : extracted) {
            assertTrue("extracted file must exist: " + f, f.isFile());
            assertTrue("extracted file must be a jar: " + f, f.getName().endsWith(".jar"));
        }
        // The extracted mid must itself still contain the inner jar entry.
        // 提取出的 mid 内部必须仍含有 inner jar 条目。
        assertContainsEntry(extracted.get(0), NESTED + "inner.jar");
    }

    /**
     * A single-level container must behave exactly like the non-recursive API.
     * <p>
     * 单层容器必须与非递归 API 行为一致。
     */
    @Test
    public void testRecursiveExtractionSingleLevel() throws IOException {
        File outer = tempFolder.newFile("outer.jar");
        writeJar(outer, jarBytes(NESTED + "lib.jar", bytes("lib"), "outer.txt", bytes("outer")));

        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(
                outer, NESTED, new File(tempFolder.getRoot(), "cache"));

        assertEquals(1, extracted.size());
        assertTrue(extracted.get(0).getName().startsWith("lib-"));
    }

    /**
     * A container without nested jars yields an empty result.
     * <p>
     * 无嵌套 jar 的容器返回空结果。
     */
    @Test
    public void testRecursiveExtractionNoNested() throws IOException {
        File plain = tempFolder.newFile("plain.jar");
        writeJar(plain, jarBytes("plain.txt", bytes("plain")));

        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(
                plain, NESTED, new File(tempFolder.getRoot(), "cache"));

        assertTrue(extracted.isEmpty());
    }

    // === === === 共享嵌套 jar 去重 === === ===

    /**
     * Two containers embedding byte-identical nested jars: extraction must return
     * the shared jar only once (content-hash cache), and the second level must not
     * rescan the same cache file.
     * <p>
     * 两个容器内嵌字节完全相同的嵌套 jar：提取结果必须只出现一次（内容哈希缓存），
     * 且第二层不会重复扫描同一缓存文件。
     */
    @Test
    public void testRecursiveDedupSharedNestedJar() throws IOException {
        byte[] inner = jarBytes("leaf.txt", bytes("leaf"));
        byte[] midA = jarBytes(NESTED + "inner.jar", inner, "mid.txt", bytes("mid"));
        byte[] midB = jarBytes(NESTED + "inner.jar", inner, "mid.txt", bytes("mid"));
        File containerA = tempFolder.newFile("containerA.jar");
        File containerB = tempFolder.newFile("containerB.jar");
        writeJar(containerA, jarBytes(NESTED + "mid.jar", midA, "a.txt", bytes("a")));
        writeJar(containerB, jarBytes(NESTED + "mid.jar", midB, "b.txt", bytes("b")));

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(
                Arrays.asList(containerA, containerB), NESTED, cache);

        // mid (shared) and inner (reached through the single shared mid).
        // mid（共享）与 inner（经唯一一份 mid 抵达）。
        assertEquals(2, extracted.size());
        assertEquals("mid-", prefix(extracted.get(0)));
        assertEquals("inner-", prefix(extracted.get(1)));
    }

    /**
     * The same container listed twice must be processed only once.
     * <p>
     * 同一容器在列表中出现两次时只处理一次。
     */
    @Test
    public void testRecursiveDedupDuplicateContainer() throws IOException {
        File outer = tempFolder.newFile("outer.jar");
        writeJar(outer, jarBytes(NESTED + "lib.jar", bytes("lib"), "outer.txt", bytes("outer")));

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(
                Arrays.asList(outer, outer), NESTED, cache);

        assertEquals(1, extracted.size());
    }

    // === === === 环安全 === === ===

    /**
     * Cycle safety: container B embeds a jar whose content is identical to
     * container A; after extracting A's nested jar the cached file (same hash)
     * must not be scanned a second time, so the traversal terminates.
     * <p>
     * 环安全：容器 B 内嵌一个与容器 A 内容完全相同的 jar；提取 A 的嵌套 jar 后，
     * 缓存文件（哈希相同）不得被再次扫描，遍历因此必然终止。
     */
    @Test
    public void testRecursiveCycleSafety() throws IOException {
        // A and B share the same nested content: a jar that embeds leaf.
        // A 与 B 共享相同的嵌套内容：一个内嵌 leaf 的 jar。
        byte[] leaf = jarBytes("leaf.txt", bytes("leaf"));
        byte[] shared = jarBytes(NESTED + "inner.jar", leaf, "shared.txt", bytes("shared"));
        File containerA = tempFolder.newFile("containerA.jar");
        File containerB = tempFolder.newFile("containerB.jar");
        writeJar(containerA, jarBytes(NESTED + "shared.jar", shared, "a.txt", bytes("a")));
        writeJar(containerB, jarBytes(NESTED + "shared.jar", shared, "b.txt", bytes("b")));

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(
                Arrays.asList(containerA, containerB), NESTED, cache);

        // shared.jar (once) + inner.jar: no duplicate, traversal terminates.
        // shared.jar（一份）+ inner.jar：无重复，遍历终止。
        assertEquals(2, extracted.size());
    }

    // === === === 开发环境目录容器 === === ===

    /**
     * Dev-workspace container: a directory whose META-INF/jarjar sub-directory
     * holds a jar that itself embeds another jar. The directory-level jar is
     * returned as-is (no extraction in dev mode); the jar it embeds is extracted.
     * <p>
     * 开发环境容器：目录的 META-INF/jarjar 子目录下放着一个内嵌了另一个 jar 的
     * jar。目录层级的 jar 原样返回（开发模式不提取）；其内嵌的 jar 被提取到缓存。
     */
    @Test
    public void testRecursiveDevDirectoryContainer() throws IOException {
        byte[] inner = jarBytes("leaf.txt", bytes("leaf"));
        byte[] mid = jarBytes(NESTED + "inner.jar", inner, "mid.txt", bytes("mid"));
        File classesDir = tempFolder.newFolder("classes");
        File nestedDir = new File(classesDir, NESTED);
        assertTrue(nestedDir.mkdirs());
        File midFile = new File(nestedDir, "mid.jar");
        writeJar(midFile, mid);

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> extracted = JarInJar.extractAllNestedJarsRecursive(classesDir, NESTED, cache);

        assertEquals(2, extracted.size());
        // Dev-mode nested jar: the original disk file, not a cache copy.
        // 开发模式嵌套 jar：原始磁盘文件，而非缓存副本。
        assertEquals("mid.jar", extracted.get(0).getName());
        assertTrue(extracted.get(0).getCanonicalPath().startsWith(classesDir.getCanonicalPath()));
        // The jar embedded inside mid.jar goes through the regular extraction path.
        // mid.jar 内嵌的 jar 走常规提取路径。
        assertTrue(extracted.get(1).getName().startsWith("inner-"));
    }

    // === === === 缓存复用 === === ===

    /**
     * Calling the recursive extraction twice on the same container must reuse the
     * cache and return the same file names.
     * <p>
     * 对同一容器两次调用递归提取必须复用缓存，并返回相同的文件名。
     */
    @Test
    public void testRecursiveCacheReuse() throws IOException {
        byte[] inner = jarBytes("leaf.txt", bytes("leaf"));
        byte[] mid = jarBytes(NESTED + "inner.jar", inner, "mid.txt", bytes("mid"));
        File outer = tempFolder.newFile("outer.jar");
        writeJar(outer, jarBytes(NESTED + "mid.jar", mid, "outer.txt", bytes("outer")));

        File cache = new File(tempFolder.getRoot(), "cache");
        List<File> first = JarInJar.extractAllNestedJarsRecursive(outer, NESTED, cache);
        List<File> second = JarInJar.extractAllNestedJarsRecursive(outer, NESTED, cache);

        assertEquals(first, second);
        assertEquals(2, second.size());
    }

    // === === === helpers / 辅助 === === ===

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Builds an in-memory jar with a single entry. / 构造含单条目的内存 jar。
     */
    private static byte[] jarBytes(String entryName, byte[] content) throws IOException {
        Map<String, byte[]> entries = new HashMap<String, byte[]>();
        entries.put(entryName, content);
        return buildJar(entries);
    }

    /**
     * Builds an in-memory jar with two entries. / 构造含两条目的内存 jar。
     */
    private static byte[] jarBytes(String name1, byte[] content1, String name2, byte[] content2) throws IOException {
        Map<String, byte[]> entries = new HashMap<String, byte[]>();
        entries.put(name1, content1);
        entries.put(name2, content2);
        return buildJar(entries);
    }

    private static byte[] buildJar(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream out = new ZipOutputStream(bos);
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return bos.toByteArray();
    }

    private static void writeJar(File file, byte[] content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content);
        } finally {
            out.close();
        }
    }

    /**
     * Asserts that the jar contains the given entry. / 断言 jar 含有指定条目。
     */
    private static void assertContainsEntry(File jar, String entryName) throws IOException {
        JarFile jarFile = new JarFile(jar);
        try {
            assertNotNull("entry " + entryName + " missing in " + jar, jarFile.getJarEntry(entryName));
        } finally {
            jarFile.close();
        }
    }

    private static String prefix(File file) {
        String name = file.getName();
        int dash = name.indexOf('-');
        return (dash > 0) ? name.substring(0, dash + 1) : name;
    }
}
