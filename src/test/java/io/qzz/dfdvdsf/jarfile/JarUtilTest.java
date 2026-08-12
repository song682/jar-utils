package io.qzz.dfdvdsf.jarfile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JarUtil}: jar-entry indexing (with
 * {@code .class}/{@code .png} filtering and the {@code data/} split), local
 * directory scanning (recursive and flat), reading indexed content back, and
 * the deduplication of re-scans. Tests build real jar files via
 * {@link ZipOutputStream} and require no Minecraft runtime.
 * <p>
 * {@link JarUtil} 的单元测试：jar 条目索引（含 {@code .class}/{@code .png}
 * 过滤与 {@code data/} 分流）、本地目录扫描（递归与非递归）、按索引回读内容，
 * 以及重复扫描的去重。测试通过 {@link ZipOutputStream} 构造真实 jar 文件，
 * 不依赖 Minecraft 运行时。
 */
public class JarUtilTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Before
    public void resetIndex() {
        JarUtil.reset();
    }

    // === === === jar 条目索引 === === ===

    /**
     * Jar entries are indexed, skipping directories and
     * {@code .class}/{@code .png} entries.
     * <p>
     * jar 条目被索引，跳过目录及 {@code .class}/{@code .png} 条目。
     */
    @Test
    public void testScanJarIndexesEntries() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        File jar = new File(modsDir, "sample.jar");
        writeJar(jar, entries(
                "assets/mod/lang/en_US.lang", "key=value",
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0",
                "com/example/Foo.class", "fake bytecode",
                "assets/mod/textures/icon.png", "fake png"));

        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);

        Set<UrlBuffered> all = JarUtil.getSet();
        assertTrue(hasUrl(all, "assets/mod/lang/en_US.lang"));
        assertTrue(hasUrl(all, "META-INF/MANIFEST.MF"));
        assertFalse("class entries must be skipped", hasUrl(all, "com/example/Foo.class"));
        assertFalse("png entries must be skipped", hasUrl(all, "assets/mod/textures/icon.png"));
    }

    /**
     * Entries under the {@code data/} prefix land in the data set as well.
     * <p>
     * {@code data/} 前缀下的条目同时进入 data 集。
     */
    @Test
    public void testScanJarDataEntries() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        File jar = new File(modsDir, "data.jar");
        writeJar(jar, entries(
                "data/recipes.json", "{\"type\":\"crafting\"}",
                "data/nested/blockstates.json", "{}",
                "assets/mod/lang/en_US.lang", "key=value"));

        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);

        Set<UrlBuffered> data = JarUtil.getDataSet();
        assertTrue(hasUrl(data, "data/recipes.json"));
        assertTrue(hasUrl(data, "data/nested/blockstates.json"));
        assertFalse("non-data entries must not land in the data set",
                hasUrl(data, "assets/mod/lang/en_US.lang"));
    }

    /**
     * A null mods directory skips jar scanning entirely.
     * <p>
     * mods 目录为 {@code null} 时完全跳过 jar 扫描。
     */
    @Test
    public void testNullModsDirSkipsJars() {
        JarUtil.scan(null, Collections.<File>emptyList(), true);
        assertTrue(JarUtil.getSet().isEmpty());
        assertTrue(JarUtil.getDataSet().isEmpty());
    }

    // === === === 本地目录扫描 === === ===

    /**
     * Local files land in both index sets, descending into subdirectories.
     * <p>
     * 本地文件同时进入两个索引集，并递归子目录。
     */
    @Test
    public void testScanLocalDirectory() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        writeFile(new File(dataDir, "recipes.json"), "{}");
        File nested = new File(dataDir, "nested");
        assertTrue(nested.mkdirs());
        writeFile(new File(nested, "blockstates.json"), "{}");

        JarUtil.scan(null, Arrays.asList(dataDir), true);

        Set<UrlBuffered> all = JarUtil.getSet();
        Set<UrlBuffered> data = JarUtil.getDataSet();
        assertTrue(hasPath(all, new File(dataDir, "recipes.json").getAbsolutePath()));
        assertTrue(hasPath(all, new File(nested, "blockstates.json").getAbsolutePath()));
        assertTrue("local files must land in the data set too",
                hasPath(data, new File(dataDir, "recipes.json").getAbsolutePath()));
    }

    /**
     * With recursion disabled, files in subdirectories are not indexed.
     * <p>
     * 关闭递归后，子目录中的文件不会被索引。
     */
    @Test
    public void testNonRecursiveScanSkipsSubdirectories() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        writeFile(new File(dataDir, "recipes.json"), "{}");
        File nested = new File(dataDir, "nested");
        assertTrue(nested.mkdirs());
        writeFile(new File(nested, "blockstates.json"), "{}");

        JarUtil.scan(null, Arrays.asList(dataDir), false);

        assertTrue(hasPath(JarUtil.getSet(), new File(dataDir, "recipes.json").getAbsolutePath()));
        assertFalse("subdirectory files must be skipped when recursion is off",
                hasPath(JarUtil.getSet(), new File(nested, "blockstates.json").getAbsolutePath()));
    }

    // === === === 按索引读取 === === ===

    /**
     * A jar entry is read back straight from its owning jar.
     * <p>
     * jar 条目直接从其所属 jar 读回。
     */
    @Test
    public void testReadFileFromJarUrl() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        File jar = new File(modsDir, "content.jar");
        writeJar(jar, entries("data/recipes.json", "{\"type\":\"crafting\"}"));

        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);

        UrlBuffered url = findUrl(JarUtil.getSet(), "data/recipes.json");
        assertNotNull(url);
        assertTrue(url.isJar());
        assertEquals(jar, url.getSource());
        assertEquals("{\"type\":\"crafting\"}", JarUtil.readFileFromUrl(url));
    }

    /**
     * A local file is read back as UTF-8 text, CJK included.
     * <p>
     * 本地文件以 UTF-8 文本读回，含中文内容。
     */
    @Test
    public void testReadFromLocalUrl() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        File file = new File(dataDir, "tips.json");
        writeFile(file, "{\"tip\":\"你好，世界\"}");

        JarUtil.scan(null, Arrays.asList(dataDir), true);

        UrlBuffered url = findPath(JarUtil.getSet(), file.getAbsolutePath());
        assertNotNull(url);
        assertFalse(url.isJar());
        assertEquals("{\"tip\":\"你好，世界\"}", JarUtil.readFileFromUrl(url));
        byte[] bytes = JarUtil.readBytesFromUrl(url);
        assertNotNull(bytes);
        assertEquals("{\"tip\":\"你好，世界\"}", new String(bytes, "UTF-8"));
    }

    /**
     * A stream over an indexed local file opens and reads correctly.
     * <p>
     * 已索引本地文件的输入流可正确打开并读取。
     */
    @Test
    public void testGetInputStreamFromUrl() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        File file = new File(dataDir, "a.txt");
        writeFile(file, "hello");

        JarUtil.scan(null, Arrays.asList(dataDir), true);

        UrlBuffered url = findPath(JarUtil.getSet(), file.getAbsolutePath());
        assertNotNull(url);
        InputStream in = JarUtil.getInputStreamFromUrl(url);
        assertNotNull(in);
        byte[] buf = new byte[32];
        int n = in.read(buf);
        in.close();
        assertEquals(5, n);
        assertEquals("hello", new String(buf, 0, n, "UTF-8"));
    }

    /**
     * A deleted local file yields {@code null} from the stream accessor.
     * <p>
     * 已被删除的本地文件从流访问器得到 {@code null}。
     */
    @Test
    public void testDeletedFileReturnsNull() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        File file = new File(dataDir, "gone.txt");
        writeFile(file, "hello");

        JarUtil.scan(null, Arrays.asList(dataDir), true);

        UrlBuffered url = findPath(JarUtil.getSet(), file.getAbsolutePath());
        assertNotNull(url);
        assertTrue(file.delete());
        assertNull(JarUtil.getInputStreamFromUrl(url));
        assertNull(JarUtil.readFileFromUrl(url));
    }

    // === === === 去重与索引行为 === === ===

    /**
     * Scanning twice does not duplicate entries — deduplication is path-based.
     * <p>
     * 重复扫描不会产生重复条目——去重基于路径。
     */
    @Test
    public void testRescanDeduplicates() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        File jar = new File(modsDir, "dup.jar");
        writeJar(jar, entries("data/recipes.json", "{}"));

        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);
        int size = JarUtil.getSet().size();
        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);

        assertEquals(size, JarUtil.getSet().size());
    }

    /**
     * Equal {@link UrlBuffered} entries hash identically, so the index
     * sets deduplicate by path. / 相等的 {@link UrlBuffered} 哈希一致，
     * 索引集按路径去重。
     */
    @Test
    public void testUrlBufferedEqualsAndHashCode() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        File jar = new File(modsDir, "eq.jar");
        writeJar(jar, entries("data/recipes.json", "{}"));

        JarUtil.scan(modsDir, Collections.<File>emptyList(), true);

        Set<UrlBuffered> copy = new HashSet<UrlBuffered>(JarUtil.getSet());
        assertEquals(JarUtil.getSet(), copy);
    }

    // === === === 开发者代码注册 === === ===

    /**
     * Directories registered via {@link JarUtil#addScanDirectories(File...)}
     * are scanned on top of the config-supplied ones.
     * <p>
     * 经 {@link JarUtil#addScanDirectories(File...)} 注册的目录会在配置目录
     * 之上叠加扫描。
     */
    @Test
    public void testRegisteredScanDirectories() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        writeFile(new File(dataDir, "recipes.json"), "{}");
        File extraDir = tempFolder.newFolder("extra");
        writeFile(new File(extraDir, "notes.txt"), "hi");

        JarUtil.addScanDirectories(extraDir);
        JarUtil.scan(null, Arrays.asList(dataDir), true);

        assertTrue(hasPath(JarUtil.getSet(), new File(dataDir, "recipes.json").getAbsolutePath()));
        assertTrue("registered directories must be scanned too",
                hasPath(JarUtil.getSet(), new File(extraDir, "notes.txt").getAbsolutePath()));
    }

    /**
     * Re-registering the same directory does not duplicate entries.
     * <p>
     * 重复注册同一目录不会产生重复条目。
     */
    @Test
    public void testRegisteredDirectoriesDeduplicate() throws IOException {
        File dataDir = tempFolder.newFolder("data");
        writeFile(new File(dataDir, "recipes.json"), "{}");

        JarUtil.addScanDirectories(dataDir);
        JarUtil.addScanDirectories(dataDir, dataDir);
        JarUtil.scan(null, Collections.<File>emptyList(), true);

        assertEquals(1, JarUtil.getSet().size());
    }

    // === === === 测试辅助 === === ===

    private static boolean hasUrl(Set<UrlBuffered> set, String url) {
        return findUrl(set, url) != null;
    }

    private static UrlBuffered findUrl(Set<UrlBuffered> set, String url) {
        for (UrlBuffered u : set) {
            if (u.isJar() && u.getFileUrl().equals(url)) {
                return u;
            }
        }
        return null;
    }

    private static boolean hasPath(Set<UrlBuffered> set, String path) {
        return findPath(set, path) != null;
    }

    private static UrlBuffered findPath(Set<UrlBuffered> set, String path) {
        for (UrlBuffered u : set) {
            if (!u.isJar() && u.getFileUrl().equals(path)) {
                return u;
            }
        }
        return null;
    }

    private static void writeFile(File file, String content) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static void writeJar(File jar, String[][] entries) throws IOException {
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar));
        try {
            for (String[] entry : entries) {
                out.putNextEntry(new ZipEntry(entry[0]));
                out.write(entry[1].getBytes("UTF-8"));
                out.closeEntry();
            }
        } finally {
            out.close();
        }
    }

    private static String[][] entries(String... pairs) {
        String[][] result = new String[pairs.length / 2][2];
        for (int i = 0; i < pairs.length; i += 2) {
            result[i / 2][0] = pairs[i];
            result[i / 2][1] = pairs[i + 1];
        }
        return result;
    }
}
