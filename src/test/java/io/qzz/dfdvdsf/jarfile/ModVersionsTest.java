package io.qzz.dfdvdsf.jarfile;

import org.junit.After;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link ModVersions}, a pure JDK (Java 8) re-implementation
 * of Forge-style mod version checks: version comparison
 * ({@link ModVersions#compare(String, String)}), spec matching
 * ({@link ModVersions#matches(String, String)}) and the loaded-mod entry
 * point {@link ModVersions#versionMatches(String, String, String)}, whose
 * FML-backed lookup is swapped for a fixed table via
 * {@link ModVersions#setLookup(ModVersions.Lookup)} so no Minecraft runtime
 * is needed.
 * <p>
 * {@link ModVersions} 的单元测试。该类以纯 JDK（Java 8）实现了 Forge
 * 风格的模组版本检查：版本比较（{@link ModVersions#compare(String, String)}）、
 * 表达式匹配（{@link ModVersions#matches(String, String)}）以及已加载模组
 * 入口 {@link ModVersions#versionMatches(String, String, String)}——后者的
 * FML 查询经 {@link ModVersions#setLookup(ModVersions.Lookup)} 替换为
 * 固定表，因此无需 Minecraft 运行时。
 */
public class ModVersionsTest {

    @After
    public void restoreDefaultLookup() {
        ModVersions.setLookup(null);
    }

    // === === === compare / 版本比较 === === ===

    @Test
    public void testCompareOrdersNumericSegments() {
        assertTrue(ModVersions.compare("1.2.3", "1.10.0") < 0);
        assertTrue(ModVersions.compare("1.10.0", "1.9.9") > 0);
        assertEquals(0, ModVersions.compare("1.2.3", "1.2.3"));
    }

    @Test
    public void testCompareIgnoresLeadingV() {
        assertEquals(0, ModVersions.compare("v2.0", "2.0"));
        assertEquals(0, ModVersions.compare("V2.0", "2.0"));
        assertTrue(ModVersions.compare("v2.1", "2.0") > 0);
    }

    @Test
    public void testCompareTrimsWhitespaceAndCase() {
        assertEquals(0, ModVersions.compare(" 1.0.0-BETA ", "1.0.0-beta"));
    }

    @Test
    public void testComparePrereleaseIsOlder() {
        assertTrue(ModVersions.compare("1.0.0-beta", "1.0.0") < 0);
        assertTrue(ModVersions.compare("1.0.0", "1.0.0-beta") > 0);
        assertEquals(0, ModVersions.compare("1.0.0", "1.0.0-release"));
    }

    @Test
    public void testCompareQualifierRanking() {
        // alpha < beta < rc < snapshot < release, mirroring Forge's ordering.
        // alpha < beta < rc < snapshot < release，对齐 Forge 的排序。
        assertTrue(ModVersions.compare("1.0-alpha", "1.0-beta") < 0);
        assertTrue(ModVersions.compare("1.0-beta", "1.0-rc1") < 0);
        assertTrue(ModVersions.compare("1.0-rc1", "1.0-snapshot") < 0);
        assertTrue(ModVersions.compare("1.0-snapshot", "1.0") < 0);
    }

    @Test
    public void testCompareQualifierNumericSuffix() {
        // Same qualifier family: the trailing number decides (rc1 < rc2).
        // 同一限定词族：由末尾数字决定（rc1 < rc2）。
        assertTrue(ModVersions.compare("1.0-rc1", "1.0-rc2") < 0);
        assertTrue(ModVersions.compare("1.0-beta2", "1.0-beta10") < 0);
        assertTrue(ModVersions.compare("1.0-beta", "1.0-beta1") < 0);
    }

    @Test
    public void testCompareTrailingZeroPaddingIsEqual() {
        // "1.0" and "1.0.0" are the same release, Forge-style.
        // 按 Forge 语义，"1.0" 与 "1.0.0" 是同一版本。
        assertEquals(0, ModVersions.compare("1.0", "1.0.0"));
        assertTrue(ModVersions.compare("1.0.1", "1.0") > 0);
    }

    @Test
    public void testCompareVeryLongNumericSegment() {
        // No long parsing involved, so huge segments stay safe.
        // 不依赖 long 解析，超长数字段依然安全。
        assertTrue(ModVersions.compare("1.999999999999999999999", "1.2") > 0);
    }

    // === === === matches / 表达式匹配 === === ===

    @Test
    public void testBareVersionMeansEquality() {
        assertTrue(ModVersions.matches("1.2.3", "1.2.3"));
        assertFalse(ModVersions.matches("1.2.3", "1.2.4"));
    }

    @Test
    public void testEqualsOperators() {
        assertTrue(ModVersions.matches("1.2.3", "==1.2.3"));
        assertTrue(ModVersions.matches("1.2.3", "=1.2.3"));
        assertFalse(ModVersions.matches("1.2.3", "==1.2.4"));
    }

    @Test
    public void testGreaterOperators() {
        assertTrue(ModVersions.matches("1.3.0", ">1.2.3"));
        assertFalse(ModVersions.matches("1.2.3", ">1.2.3"));
        assertTrue(ModVersions.matches("1.3.0", ">=1.2.3"));
        assertTrue(ModVersions.matches("1.2.3", ">=1.2.3"));
        assertFalse(ModVersions.matches("1.2.2", ">=1.2.3"));
    }

    @Test
    public void testLessOperators() {
        assertTrue(ModVersions.matches("1.2.2", "<1.2.3"));
        assertFalse(ModVersions.matches("1.2.3", "<1.2.3"));
        assertTrue(ModVersions.matches("1.2.2", "<=1.2.3"));
        assertTrue(ModVersions.matches("1.2.3", "<=1.2.3"));
        assertFalse(ModVersions.matches("1.2.4", "<=1.2.3"));
    }

    @Test
    public void testSpecToleratesWhitespace() {
        assertTrue(ModVersions.matches("1.2.3", " >= 1.2.3 "));
    }

    @Test
    public void testUnparseableSpecYieldsFalse() {
        assertFalse(ModVersions.matches("1.2.3", ""));
        assertFalse(ModVersions.matches("1.2.3", ">="));
    }

    @Test
    public void testNullInputsYieldFalse() {
        assertFalse(ModVersions.matches(null, ">=1.0"));
        assertFalse(ModVersions.matches("1.0", null));
    }

    @Test
    public void testMultiDigitSegmentsCompareNumerically() {
        // "1.10" must be newer than "1.9", not lexicographically smaller.
        // "1.10" 必须比 "1.9" 新，而不是按字典序更小。
        assertTrue(ModVersions.matches("1.10.0", ">=1.9.0"));
    }

    // === === === versionMatches / 已加载模组入口 === === ===

    private static void installFixedMods(String[][] mods) {
        Map<String, String[]> table = new HashMap<>();
        for (String[] m : mods) {
            table.put(m[0], new String[]{m[1], m[2]});
        }
        ModVersions.setLookup(table::get);
    }

    @Test
    public void testVersionMatchesByIdOnly() {
        installFixedMods(new String[][]{{"neimod", "NotEnoughItems", "1.0.5"}});
        assertTrue(ModVersions.versionMatches(null, "neimod", ">=1.0.4"));
        assertFalse(ModVersions.versionMatches(null, "neimod", ">1.0.5"));
        assertTrue(ModVersions.versionMatches(null, "neimod", "==1.0.5"));
    }

    @Test
    public void testVersionMatchesChecksNameCaseInsensitively() {
        installFixedMods(new String[][]{{"neimod", "NotEnoughItems", "1.0.5"}});
        assertTrue(ModVersions.versionMatches("notenoughitems", "neimod", ">=1.0"));
        assertFalse(ModVersions.versionMatches("WrongName", "neimod", ">=1.0"));
    }

    @Test
    public void testVersionMatchesUnknownModYieldsFalse() {
        installFixedMods(new String[][]{{"neimod", "NotEnoughItems", "1.0.5"}});
        assertFalse(ModVersions.versionMatches(null, "nosuchmod", ">=0.0.1"));
        assertFalse(ModVersions.versionMatches(null, null, ">=0.0.1"));
        assertFalse(ModVersions.versionMatches(null, "", ">=0.0.1"));
    }

    @Test
    public void testDefaultLookupFindsNothingWithoutFml() {
        // Outside a running FML environment the reflective lookup yields no
        // mods, so every check is false instead of throwing.
        // FML 运行环境之外，反射查询不到任何模组，
        // 一切判断返回 false 而不是抛异常。
        ModVersions.setLookup(null);
        assertFalse(ModVersions.versionMatches(null, "neimod", ">=1.0"));
    }
}
