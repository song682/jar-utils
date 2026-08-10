package io.qzz.dfdvdsf.jarfile;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link JarVersionGuesser}: deriving mod name and version from
 * jar file names across common naming conventions — plain versions, v-prefixes,
 * pre-release/build suffixes, underscore separators, MC-version prefixes, and
 * degenerate inputs.
 * <p>
 * {@link JarVersionGuesser} 的单元测试：按常见命名约定从 jar 文件名推导 mod
 * 名字与版本——普通版本、v 前缀、预发布/构建后缀、下划线分隔、MC 版本前缀，
 * 以及退化输入。
 */
public class JarVersionGuesserTest {

    // === === === plain convention ModName-version.jar / 普通约定 === === ===

    @Test
    public void testPlainNameVersion() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.2.3.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3", guess.version());
        assertTrue(guess.hasVersion());
    }

    @Test
    public void testTwoSegmentVersion() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.0.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.0", guess.version());
    }

    @Test
    public void testFourSegmentVersion() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.0.0.1.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.0.0.1", guess.version());
    }

    @Test
    public void testZipSuffix() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-2.1.zip");
        assertEquals("MyMod", guess.name());
        assertEquals("2.1", guess.version());
    }

    // === === === separators: underscore / space / 下划线与空格分隔 === === ===

    @Test
    public void testUnderscoreSeparator() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("my_mod_1.2.jar");
        assertEquals("my_mod", guess.name());
        assertEquals("1.2", guess.version());
    }

    @Test
    public void testSpaceSeparator() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("My Mod 3.0.jar");
        assertEquals("My Mod", guess.name());
        assertEquals("3.0", guess.version());
    }

    // === === === v-prefix / v 前缀 === === ===

    @Test
    public void testVPrefixStripped() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-v2.0.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("2.0", guess.version());
    }

    // === === === pre-release / build suffixes / 预发布与构建后缀 === === ===

    @Test
    public void testBetaSuffix() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.2.3-beta.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3-beta", guess.version());
    }

    @Test
    public void testSnapshotSuffix() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.2.3-SNAPSHOT.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3-SNAPSHOT", guess.version());
    }

    @Test
    public void testRcSuffix() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.2.3-rc1.jar");
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3-rc1", guess.version());
    }

    // === === === MC-version prefix: last segment is the mod version / MC 版本前缀 === === ===

    @Test
    public void testMcVersionPrefix() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("NEI-1.7.10-1.0.4.jar");
        assertEquals("NEI-1.7.10", guess.name());
        assertEquals("1.0.4", guess.version());
    }

    // === === === digits inside the mod name must survive / 名字中的数字不被误吞 === === ===

    @Test
    public void testDigitInsideName() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("mod1-1.2.3.jar");
        assertEquals("mod1", guess.name());
        assertEquals("1.2.3", guess.version());
    }

    @Test
    public void testDigitOnlyNameWithoutVersion() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("mod1.jar");
        assertEquals("mod1", guess.name());
        assertFalse(guess.hasVersion());
        assertNull(guess.version());
    }

    // === === === degenerate inputs / 退化输入 === === ===

    @Test
    public void testNoVersionReturnsNameOnly() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod.jar");
        assertEquals("MyMod", guess.name());
        assertNull(guess.version());
    }

    @Test
    public void testNameWithoutExtension() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("MyMod-1.2.3");
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3", guess.version());
    }

    @Test
    public void testWholeStemIsVersion() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess("1.2.3.jar");
        assertNull(guess.name());
        assertEquals("1.2.3", guess.version());
    }

    @Test
    public void testNullInput() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess((String) null);
        assertNull(guess.name());
        assertNull(guess.version());
    }

    @Test
    public void testNullFileInput() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess((File) null);
        assertNull(guess.name());
        assertNull(guess.version());
    }

    @Test
    public void testFileInput() {
        JarVersionGuesser.Guess guess = JarVersionGuesser.guess(new File("mods/MyMod-1.2.3.jar"));
        assertEquals("MyMod", guess.name());
        assertEquals("1.2.3", guess.version());
    }
}
