package io.qzz.dfdvdsf.source;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Unit tests for {@link ModInfoGuesser}: guessing modid / name / version from
 * decompiled source trees — {@code @Mod} annotations (direct values, constant
 * references and the {@code value()} shorthand), {@code mcmod.info} /
 * {@code mcpmod.info} fallbacks, {@code mixins.*.json} file names, plus a real
 * ForgeGradle {@code recompSrc} tree as an end-to-end scenario.
 * <p>
 * {@link ModInfoGuesser} 的单元测试：从反编译源码树猜测 modid / 名字 / 版本——
 * 覆盖 {@code @Mod} 注解（直接值、常量引用与 {@code value()} 简写）、
 * {@code mcmod.info} / {@code mcpmod.info} 回退、{@code mixins.*.json} 文件名，
 * 并以真实的 ForgeGradle {@code recompSrc} 树作为端到端场景。
 */
public class ModInfoGuesserTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    // === === === @Mod annotation: direct values / 注解直接值 === === ===

    @Test
    public void testModAnnotationDirectValues() throws IOException {
        File root = tempFolder.newFolder("direct");
        write(root, "com/example/MyMod.java",
                "package com.example;\n"
                        + "import cpw.mods.fml.common.Mod;\n"
                        + "@Mod(modid = \"mymod\", name = \"My Mod\", version = \"1.2.3\")\n"
                        + "public class MyMod {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("mymod", guess.modid());
        assertEquals("My Mod", guess.name());
        assertEquals("1.2.3", guess.version());
        assertTrue(guess.source(), guess.source().contains("@Mod in com/example/MyMod.java"));
    }

    // === === === @Mod annotation: multi-line + constant references / 多行与常量引用 === === ===

    @Test
    public void testModAnnotationReferencesTagsConstants() throws IOException {
        File root = tempFolder.newFolder("refs");
        write(root, "io/qzz/dfdvdsf/Tags.java",
                "package io.qzz.dfdvdsf;\n"
                        + "public class Tags {\n"
                        + "    public static final String MODID = \"jarutils\";\n"
                        + "    public static final String NAME = \"JarUtils\";\n"
                        + "    public static final String VERSION = \"0.0.1\";\n"
                        + "}\n");
        write(root, "io/qzz/dfdvdsf/JarUtils.java",
                "package io.qzz.dfdvdsf;\n"
                        + "import cpw.mods.fml.common.Mod;\n"
                        + "@Mod(\n"
                        + "        modid = Tags.MODID,\n"
                        + "        name = Tags.NAME,\n"
                        + "        version = Tags.VERSION,\n"
                        + "        useMetadata = true\n"
                        + ")\n"
                        + "public class JarUtils {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("jarutils", guess.modid());
        assertEquals("JarUtils", guess.name());
        assertEquals("0.0.1", guess.version());
    }

    // === === === @Mod annotation: value() shorthand / value() 简写 === === ===

    @Test
    public void testModAnnotationValueShorthand() throws IOException {
        File root = tempFolder.newFolder("shorthand");
        write(root, "ExampleMod.java",
                "@Mod(\"shorthandmod\")\npublic class ExampleMod {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("shorthandmod", guess.modid());
        assertNull(guess.name());
        assertNull(guess.version());
    }

    // === === === @Mod mention in comments must be ignored / 注释中的提及应忽略 === === ===

    @Test
    public void testModAnnotationInsideCommentIgnored() throws IOException {
        File root = tempFolder.newFolder("comment");
        write(root, "NotAMod.java",
                "/**\n"
                        + " * Documentation may mention @Mod(modid = \"fake\") here.\n"
                        + " */\n"
                        + "public class NotAMod {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertNull(guess.modid());
        assertFalse(guess.hasAny());
    }

    // === === === mcmod.info fallback / mcmod.info 回退 === === ===

    @Test
    public void testMcmodInfoFallback() throws IOException {
        File root = tempFolder.newFolder("meta");
        write(root, "META-INF/mcmod.info",
                "[\n"
                        + "  {\n"
                        + "    \"modid\": \"mcp\",\n"
                        + "    \"name\": \"Minecraft Coder Pack\",\n"
                        + "    \"version\": \"9.05\",\n"
                        + "    \"mcversion\": \"1.7.10\"\n"
                        + "  }\n"
                        + "]\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("mcp", guess.modid());
        assertEquals("Minecraft Coder Pack", guess.name());
        assertEquals("9.05", guess.version());
        assertTrue(guess.source().contains("mcmod.info"));
    }

    // === === === unexpanded template variables must not be treated as values / 未展开的模板变量忽略 === === ===

    @Test
    public void testMcmodInfoTemplateVariablesSkipped() throws IOException {
        File root = tempFolder.newFolder("template");
        write(root, "mcmod.info",
                "{\"modid\": \"${modid}\", \"name\": \"${modname}\", \"version\": \"${modversion}\"}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertFalse(guess.hasAny());
        assertNull(guess.modid());
    }

    // === === === mcpmod.info of a plain Forge tree / 纯 Forge 树的 mcpmod.info === === ===

    @Test
    public void testMcpmodInfoRecognized() throws IOException {
        File root = tempFolder.newFolder("mcp");
        write(root, "mcpmod.info",
                "[{\"modid\": \"mcp\", \"name\": \"Minecraft Coder Pack\", \"version\": \"9.05\"}]\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("mcp", guess.modid());
        assertEquals("9.05", guess.version());
    }

    // === === === mixins.*.json file names / mixins 文件名 === === ===

    @Test
    public void testMixinsFileNameProvidesModid() throws IOException {
        File root = tempFolder.newFolder("mixins");
        write(root, "mixins.mymod.json", "{}\n");
        write(root, "mixins.mymod.refmap.json", "{}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("mymod", guess.modid());
        assertNull(guess.name());
        assertNull(guess.version());
    }

    // === === === priority: @Mod beats mcmod.info / 优先级：@Mod 优先于 mcmod.info === === ===

    @Test
    public void testModAnnotationBeatsMcmodInfo() throws IOException {
        File root = tempFolder.newFolder("priority");
        write(root, "com/example/MyMod.java",
                "package com.example;\n"
                        + "@Mod(modid = \"annotationmod\", name = \"Annotation Mod\", version = \"2.0\")\n"
                        + "public class MyMod {}\n");
        write(root, "mcmod.info",
                "[{\"modid\": \"metadatamod\", \"name\": \"Metadata Mod\", \"version\": \"1.0\"}]\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("annotationmod", guess.modid());
        assertEquals("Annotation Mod", guess.name());
        assertEquals("2.0", guess.version());
    }

    // === === === missing fields are filled by the next source / 缺失字段由下一来源补齐 === === ===

    @Test
    public void testMixedSourcesFillMissingFields() throws IOException {
        File root = tempFolder.newFolder("mixed");
        write(root, "MyMod.java",
                "@Mod(modid = \"mixedmod\", version = \"3.1.4\")\npublic class MyMod {}\n");
        write(root, "mcmod.info",
                "[{\"modid\": \"other\", \"name\": \"Mixed Mod Name\", \"version\": \"0\"}]\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root);
        assertEquals("mixedmod", guess.modid());
        assertEquals("Mixed Mod Name", guess.name());
        assertEquals("3.1.4", guess.version());
    }

    // === === === degenerate inputs / 退化输入 === === ===

    @Test
    public void testNullInputReturnsEmptyGuess() {
        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(null);
        assertFalse(guess.hasAny());
    }

    @Test
    public void testMissingDirectoryReturnsEmptyGuess() throws IOException {
        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(tempFolder.newFile("not-a-dir.txt"));
        assertFalse(guess.hasAny());
    }

    // === === === jar file name as a low-trust fallback / jar 文件名低可信度兜底 === === ===

    @Test
    public void testJarFileNameFillsVersionAndName() throws IOException {
        File root = tempFolder.newFolder("no-meta");
        write(root, "Foo.java", "public class Foo {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root, "MyMod-1.2.3.jar");
        assertNull(guess.modid());
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3", guess.version());
        assertTrue(guess.source(), guess.source().contains("MyMod-1.2.3.jar"));
    }

    @Test
    public void testJarFileNameDoesNotOverrideMetadata() throws IOException {
        File root = tempFolder.newFolder("keep-meta");
        write(root, "MyMod.java",
                "@Mod(modid = \"annotationmod\", name = \"Annotation Mod\", version = \"2.0\")\n"
                        + "public class MyMod {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root, "OtherMod-9.9.9.jar");
        assertEquals("annotationmod", guess.modid());
        assertEquals("Annotation Mod", guess.name());
        assertEquals("2.0", guess.version());
        assertFalse(guess.source(), guess.source().contains("OtherMod-9.9.9.jar"));
    }

    @Test
    public void testJarFileNameWithoutVersionLeavesNameOnly() throws IOException {
        File root = tempFolder.newFolder("no-version");
        write(root, "Foo.java", "public class Foo {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root, "MyMod.jar");
        assertEquals("MyMod", guess.name());
        assertNull(guess.version());
    }

    @Test
    public void testJarFileNameIgnoredWhenUnused() throws IOException {
        File root = tempFolder.newFolder("unused");
        write(root, "Foo.java", "public class Foo {}\n");

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(root, null);
        assertFalse(guess.hasAny());
    }

    // === === === real ForgeGradle recompSrc tree / 真实 ForgeGradle recompSrc 树 === === ===

    @Test
    public void testRealRecompSrcTree() {
        File recompSrc = new File("build/tmp/recompSrc");
        assumeTrue("recompSrc tree missing; skipping", recompSrc.isDirectory());

        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(recompSrc);
        // The plain Minecraft/Forge tree carries the MCP toolchain metadata.
        // 纯 Minecraft/Forge 树携带的是 MCP 工具链元数据。
        assertEquals("mcp", guess.modid());
        assertEquals("Minecraft Coder Pack", guess.name());
        assertEquals("9.05", guess.version());
        assertTrue(guess.source(), guess.source().contains("mcpmod.info"));
    }

    @Test
    public void testRealRecompSrcTreeWithJarNameKeepsToolchainMetadata() {
        File recompSrc = new File("build/tmp/recompSrc");
        assumeTrue("recompSrc tree missing; skipping", recompSrc.isDirectory());

        // mcpmod.info must win over the jar file name. / mcpmod.info 优先于 jar 文件名。
        ModInfoGuesser.Guess guess = ModInfoGuesser.guess(recompSrc, "Forge-1.7.10-10.13.4.1614.jar");
        assertEquals("mcp", guess.modid());
        assertEquals("Minecraft Coder Pack", guess.name());
        assertEquals("9.05", guess.version());
        assertFalse(guess.source(), guess.source().contains("Forge-1.7.10-10.13.4.1614.jar"));
    }

    // === === === guessJar: direct jar identification / guessJar：直接识别 jar === === ===

    @Test
    public void testGuessJarFromMcmodInfo() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("mcmod.info", text("[{\"modid\": \"mcp\", \"name\": \"Minecraft Coder Pack\", \"version\": \"9.05\"}]"));
        File jar = writeJar(tempFolder.newFolder("jar-meta"), "MyMod-9.9.9.jar", entries);

        ModInfoGuesser.Guess guess = ModInfoGuesser.guessJar(jar);
        assertEquals("mcp", guess.modid());
        assertEquals("Minecraft Coder Pack", guess.name());
        assertEquals("9.05", guess.version());
        assertTrue(guess.source(), guess.source().contains("mcmod.info"));
        assertFalse(guess.source(), guess.source().contains("MyMod-9.9.9.jar"));
    }

    @Test
    public void testGuessJarMixinsFallback() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("mixins.mymod.json", text("{}"));
        File jar = writeJar(tempFolder.newFolder("jar-mixins"), "MyMod.jar", entries);

        ModInfoGuesser.Guess guess = ModInfoGuesser.guessJar(jar);
        assertEquals("mymod", guess.modid());
        assertNull(guess.version());
    }

    @Test
    public void testGuessJarFileNameFallback() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        File jar = writeJar(tempFolder.newFolder("jar-filename"), "mymod-1.2.3.jar", entries);

        ModInfoGuesser.Guess guess = ModInfoGuesser.guessJar(jar);
        assertNull(guess.modid());
        assertEquals("mymod", guess.name());
        assertEquals("1.2.3", guess.version());
        assertTrue(guess.source(), guess.source().contains("mymod-1.2.3.jar"));
    }

    @Test
    public void testGuessJarNonJarInput() throws IOException {
        assertFalse(ModInfoGuesser.guessJar(null).hasAny());
        assertFalse(ModInfoGuesser.guessJar(tempFolder.newFile("not-a-jar.txt")).hasAny());
    }

    // === === === helpers / 辅助 === === ===

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static File writeJar(File root, String name, Map<String, byte[]> entries) throws IOException {
        File jar = new File(root, name);
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

    private static void write(File root, String relPath, String content) throws IOException {
        File file = new File(root, relPath);
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
