package io.qzz.dfdvdsf.jarfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JarNames}, plus integration-style scenarios that
 * mirror how a mod would use the library as a backup util: scanning a mods
 * directory, deriving display names from jar file names, and handling real
 * jar files without any Minecraft runtime dependency.
 * <p>
 * {@link JarNames} 的单元测试，外加集成式场景用例：模拟 mod 将该库用作后备
 * 工具的方式——扫描 mods 目录、从 jar 文件名推导显示名，以及在无任何
 * Minecraft 运行时依赖的情况下处理真实 jar 文件。
 */
public class JarNamesTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // === === === fileName(File) / 磁盘文件名 === === ===

    @Test
    public void testFileNameReturnsPlainName() {
        assertEquals("MyMod-1.0.0.jar", JarNames.fileName(new File("mods/MyMod-1.0.0.jar")));
    }

    @Test
    public void testFileNameNullInput() {
        assertNull(JarNames.fileName(null));
    }

    // === === === fileNameWithoutExtension / 去扩展名 === === ===

    @Test
    public void testStemKeepsInnerDots() {
        assertEquals("MyMod-1.0.0", JarNames.fileNameWithoutExtension(new File("MyMod-1.0.0.jar")));
    }

    @Test
    public void testStemWithDottedName() {
        assertEquals("my.mod", JarNames.fileNameWithoutExtension(new File("my.mod.jar")));
    }

    @Test
    public void testStemUpperCaseSuffix() {
        assertEquals("MyMod", JarNames.fileNameWithoutExtension(new File("MyMod.JAR")));
    }

    @Test
    public void testStemZipSuffix() {
        assertEquals("lib", JarNames.fileNameWithoutExtension(new File("lib.zip")));
    }

    @Test
    public void testStemNoExtensionReturnsAsIs() {
        assertEquals("lib", JarNames.fileNameWithoutExtension(new File("lib")));
    }

    @Test
    public void testStemNonJarExtensionReturnsAsIs() {
        assertEquals("notes.txt", JarNames.fileNameWithoutExtension(new File("notes.txt")));
    }

    @Test
    public void testStemNullInput() {
        assertNull(JarNames.fileNameWithoutExtension(null));
    }

    // === === === stripJarExtension(String) / 字符串版本 === === ===

    @Test
    public void testStripEntryNameKeepsPath() {
        assertEquals("META-INF/jarjar/lib", JarNames.stripJarExtension("META-INF/jarjar/lib.jar"));
    }

    @Test
    public void testStripMixedCase() {
        assertEquals("lib", JarNames.stripJarExtension("lib.JaR"));
    }

    @Test
    public void testStripNullInput() {
        assertNull(JarNames.stripJarExtension(null));
    }

    // === === === isJarLike / isJarFile / 后缀判断 === === ===

    @Test
    public void testIsJarLikePositive() {
        assertTrue(JarNames.isJarLike("a.jar"));
        assertTrue(JarNames.isJarLike("a.zip"));
        assertTrue(JarNames.isJarLike("a.JAR"));
        assertTrue(JarNames.isJarLike("a.Zip"));
    }

    @Test
    public void testIsJarLikeNegative() {
        assertFalse(JarNames.isJarLike("a.jarr"));
        assertFalse(JarNames.isJarLike("a.txt"));
        assertFalse(JarNames.isJarLike("a"));
        assertFalse(JarNames.isJarLike(null));
    }

    @Test
    public void testIsJarFileRequiresExistingFile() throws IOException {
        File jar = tempFolder.newFile("Real-1.0.jar");
        File ghost = new File(tempFolder.getRoot(), "Ghost-1.0.jar");
        File txt = tempFolder.newFile("notes.txt");
        assertTrue(JarNames.isJarFile(jar));
        assertFalse(JarNames.isJarFile(ghost));
        assertFalse(JarNames.isJarFile(txt));
    }

    // === === === 集成场景：mod 后备工具库的典型用法 === === ===

    /**
     * Mirrors a typical mod-side usage: scan a mods directory, collect the jar
     * files, and derive display names by stripping the extension. This is the
     * core "backup util library" purpose JarNames is built for.
     * <p>
     * 模拟 mod 侧的典型用法：扫描 mods 目录、收集 jar 文件、去掉扩展名得到显示名。
     * 这正是 JarNames 作为后备工具库的核心用途。
     */
    @Test
    public void testModsDirectoryScanScenario() throws IOException {
        File modsDir = tempFolder.newFolder("mods");
        createJar(modsDir, "AlphaMod-1.0.0.jar");
        createJar(modsDir, "Beta-Mod-2.1.jar");
        createFile(modsDir, "readme.txt");

        // Scan the directory and collect jar files (any mod can plug the library
        // in exactly this way).
        // 扫描目录并收集 jar 文件（任意 mod 都可以这样接入本库）。
        List<File> modJars = new ArrayList<File>();
        File[] files = modsDir.listFiles();
        for (File f : files) {
            if (JarNames.isJarFile(f)) {
                modJars.add(f);
            }
        }

        assertEquals(2, modJars.size());

        // Derive display names from the jar file names.
        // 从 jar 文件名推导显示名。
        List<String> displayNames = new ArrayList<String>();
        for (File jar : modJars) {
            displayNames.add(JarNames.fileNameWithoutExtension(jar));
        }
        assertTrue(displayNames.contains("AlphaMod-1.0.0"));
        assertTrue(displayNames.contains("Beta-Mod-2.1"));
        assertFalse(displayNames.contains("readme"));
    }

    /**
     * Verifies that JarNames works on a real, structurally valid jar file
     * (built via ZipOutputStream), i.e. the utility does not rely on any
     * Minecraft runtime types and stays usable as a standalone library.
     * <p>
     * 验证 JarNames 对结构合法的真实 jar 文件依然可用（用 ZipOutputStream 构造），
     * 即该工具不依赖任何 Minecraft 运行时类型，可独立作为库使用。
     */
    @Test
    public void testRealJarFileNames() throws IOException {
        File jar = new File(tempFolder.getRoot(), "Real-1.0.jar");
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar));
        try {
            out.putNextEntry(new ZipEntry("META-INF/mcmod.info"));
            out.write("{\"modid\":\"real\"}".getBytes("UTF-8"));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("assets/real/lang/en_US.lang"));
            out.write("key=value".getBytes("UTF-8"));
            out.closeEntry();
        } finally {
            out.close();
        }

        assertTrue(JarNames.isJarFile(jar));
        assertEquals("Real-1.0", JarNames.fileNameWithoutExtension(jar));

        // The file must be openable as a valid jar by the standard library.
        // 文件必须能被标准库以合法 jar 打开。
        java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar);
        try {
            assertEquals(2, jarFile.size());
        } finally {
            jarFile.close();
        }
    }

    // === === === helpers / 辅助 === === ===

    private void createJar(File dir, String name) throws IOException {
        File file = new File(dir, name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
        try {
            out.putNextEntry(new ZipEntry("dummy.txt"));
            out.write("dummy".getBytes("UTF-8"));
            out.closeEntry();
        } finally {
            out.close();
        }
    }

    private void createFile(File dir, String name) throws IOException {
        new File(dir, name).createNewFile();
    }
}
