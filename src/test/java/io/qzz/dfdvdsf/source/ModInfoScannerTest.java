package io.qzz.dfdvdsf.source;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ModInfoScanner}: recursive jar-in-jar scanning — the
 * outer jar and every nested jar (multi-level included) are identified as mod
 * candidates via {@link ModInfoGuesser#guessJar(File)}, with hash-based
 * deduplication and degenerate inputs covered.
 * <p>
 * {@link ModInfoScanner} 的单元测试：递归 jar-in-jar 扫描——外层 jar 与每个
 * 嵌套 jar（含多层嵌套）都会作为模组候选，通过
 * {@link ModInfoGuesser#guessJar(File)} 识别；同时覆盖哈希去重与退化输入。
 */
public class ModInfoScannerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // === === === multi-level nested jars / 多层嵌套 jar === === ===

    @Test
    public void testScansOuterAndNestedJars() throws IOException {
        // deep jar: metadata only / 深层 jar：仅有元数据。
        byte[] deep = buildJar(entriesOf(
                "mcmod.info", text("[{\"modid\": \"deepmod\", \"name\": \"Deep Mod\", \"version\": \"0.1\"}]")));
        // inner jar: metadata + one deeper nesting / 中层 jar：元数据 + 一层更深嵌套。
        byte[] inner = buildJar(entriesOf(
                "mcmod.info", text("[{\"modid\": \"innermod\", \"name\": \"Inner Mod\", \"version\": \"1.0\"}]"),
                "META-INF/jarjar/deep-0.1.jar", deep));
        // lib jar: no metadata at all, name-only fallback / 无元数据的库 jar：仅文件名兜底。
        byte[] lib = buildJar(new LinkedHashMap<String, byte[]>());
        // outer jar: metadata + two nested jars / 外层 jar：元数据 + 两个嵌套 jar。
        File outer = writeJar("MyMod-1.0.0.jar", entriesOf(
                "mcmod.info", text("[{\"modid\": \"outermod\", \"name\": \"Outer Mod\", \"version\": \"1.0.0\"}]"),
                "META-INF/jarjar/inner-mod-1.0.jar", inner,
                "META-INF/jarjar/libonly-2.0.jar", lib));

        List<ModInfoScanner.ScanResult> results = ModInfoScanner.scanJar(outer, "META-INF/jarjar/", tempFolder.newFolder("cache"));
        assertEquals("outer + 2 nested + 1 deep", 4, results.size());

        // Outer jar is always first. / 外层 jar 恒为第一条。
        ModInfoScanner.ScanResult outerResult = results.get(0);
        assertEquals("MyMod-1.0.0.jar", outerResult.jarName());
        assertFalse(outerResult.nested());
        assertEquals("outermod", outerResult.guess().modid());
        assertEquals("Outer Mod", outerResult.guess().name());
        assertEquals("1.0.0", outerResult.guess().version());

        // Each nested jar carries its full entry name. / 每个嵌套 jar 携带完整条目名。
        ModInfoScanner.ScanResult innerResult = find(results, "META-INF/jarjar/inner-mod-1.0.jar");
        assertNotNull(innerResult);
        assertTrue(innerResult.nested());
        assertEquals("innermod", innerResult.guess().modid());
        assertEquals("1.0", innerResult.guess().version());

        // Metadata-less nested jar falls back to its file name.
        // 无元数据的嵌套 jar 回退到文件名。
        ModInfoScanner.ScanResult libResult = find(results, "META-INF/jarjar/libonly-2.0.jar");
        assertNotNull(libResult);
        assertNull(libResult.guess().modid());
        assertEquals("libonly", libResult.guess().name());
        assertEquals("2.0", libResult.guess().version());

        // Deeply nested jar is discovered too. / 深层嵌套 jar 同样被发现。
        ModInfoScanner.ScanResult deepResult = find(results, "META-INF/jarjar/deep-0.1.jar");
        assertNotNull(deepResult);
        assertEquals("deepmod", deepResult.guess().modid());
        assertEquals("0.1", deepResult.guess().version());
    }

    // === === === deduplication / 去重 === === ===

    @Test
    public void testIdenticalNestedJarsScannedOnce() throws IOException {
        // The same nested entry (same name + same content) reached through two
        // paths extracts to the same hash-named cache file and is scanned once.
        // 同一嵌套条目（同名同内容）经两条路径到达时，提取为同名哈希缓存文件，
        // 只会被扫描一次。
        byte[] libX = buildJar(entriesOf(
                "mcmod.info", text("[{\"modid\": \"duplib\", \"version\": \"1.0\"}]")));
        // Wrapper jar that also ships the same x.jar entry. / 同样携带 x.jar 的包装 jar。
        byte[] wrapper = buildJar(entriesOf(
                "META-INF/jarjar/x.jar", libX));
        File outer = writeJar("DupMod.jar", entriesOf(
                "META-INF/jarjar/x.jar", libX,
                "META-INF/jarjar/y.jar", wrapper));

        List<ModInfoScanner.ScanResult> results = ModInfoScanner.scanJar(outer, "META-INF/jarjar/", tempFolder.newFolder("cache"));
        assertEquals("outer + x + wrapper y", 3, results.size());
        // x.jar is reached twice (directly and inside y) but scanned only once.
        // x.jar 被两条路径到达（直接及 y 内部），但只被扫描一次。
        int xCount = 0;
        for (ModInfoScanner.ScanResult r : results) {
            if ("META-INF/jarjar/x.jar".equals(r.jarName())) {
                xCount++;
                assertEquals("duplib", r.guess().modid());
            }
        }
        assertEquals(1, xCount);
    }

    @Test
    public void testSameContentDifferentNamesScannedSeparately() throws IOException {
        // Two entries with identical content but different names extract to
        // different cache files (the stem differs) and are scanned separately,
        // mirroring JarInJar's per-file deduplication.
        // 两个内容相同但名字不同的条目提取为不同缓存文件（stem 不同），
        // 会被分别扫描——与 JarInJar 按文件去重的语义一致。
        byte[] sameLib = buildJar(entriesOf(
                "mcmod.info", text("[{\"modid\": \"duplib\", \"version\": \"1.0\"}]")));
        File outer = writeJar("DupMod.jar", entriesOf(
                "META-INF/jarjar/a.jar", sameLib,
                "META-INF/jarjar/b.jar", sameLib));

        List<ModInfoScanner.ScanResult> results = ModInfoScanner.scanJar(outer, "META-INF/jarjar/", tempFolder.newFolder("cache"));
        assertEquals("outer + 2 same-content jars", 3, results.size());
        assertNotNull(find(results, "META-INF/jarjar/a.jar"));
        assertNotNull(find(results, "META-INF/jarjar/b.jar"));
    }

    // === === === degenerate inputs / 退化输入 === === ===

    @Test
    public void testMissingInputReturnsEmpty() throws IOException {
        assertTrue(ModInfoScanner.scanJar(null).isEmpty());
        assertTrue(ModInfoScanner.scanJar(tempFolder.newFile("not-a-jar.txt")).isEmpty());
    }

    @Test
    public void testJarWithoutNestedJarsReturnsOuterOnly() throws IOException {
        File outer = writeJar("SoloMod-3.0.jar", entriesOf(
                "mcmod.info", text("[{\"modid\": \"solomod\", \"name\": \"Solo Mod\", \"version\": \"3.0\"}]")));

        List<ModInfoScanner.ScanResult> results = ModInfoScanner.scanJar(outer, "META-INF/jarjar/", tempFolder.newFolder("cache"));
        assertEquals(1, results.size());
        assertEquals("SoloMod-3.0.jar", results.get(0).jarName());
        assertEquals("solomod", results.get(0).guess().modid());
    }

    // === === === helpers / 辅助 === === ===

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> entriesOf(Object... keyValues) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < keyValues.length; i += 2) {
            entries.put((String) keyValues[i], (byte[]) keyValues[i + 1]);
        }
        return entries;
    }

    private static byte[] buildJar(Map<String, byte[]> entries) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bos);
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
        return bos.toByteArray();
    }

    private File writeJar(String name, Map<String, byte[]> entries) throws IOException {
        File jar = new File(tempFolder.newFolder("outer"), name);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(jar));
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
        return jar;
    }

    private static ModInfoScanner.ScanResult find(List<ModInfoScanner.ScanResult> results, String jarName) {
        for (ModInfoScanner.ScanResult result : results) {
            if (jarName.equals(result.jarName())) {
                return result;
            }
        }
        return null;
    }
}
