package io.qzz.dfdvdsf;

import net.minecraftforge.common.config.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Forge config file of the mod, answering the classic question of the
 * original {@code JarUtil}: "a config that can mark which dir will scan, and
 * scan all." It controls whether the jars of the mods directory are indexed
 * at all, which local directories are scanned additionally, and whether the
 * local scan descends into subdirectories.
 * <p>
 * 本模组的 Forge 配置文件，解答了原始 {@code JarUtil} 中的经典疑问：
 * “一个可以标记扫描哪些目录的配置，并扫描全部”。它控制是否索引 mods 目录下
 * 的 jar、额外扫描哪些本地目录，以及本地扫描是否递归子目录。
 */
public class JarUtilsConfig {

    public static final String CATEGORY_SCAN = "scan";

    /**
     * Whether to index entries from the jars of the mods directory.
     * <p>
     * 是否索引 mods 目录下 jar 中的条目。
     */
    public boolean scanModsJars = true;

    /**
     * Local directories to scan; relative paths are resolved against the
     * working directory. / 需要扫描的本地目录；相对路径基于工作目录解析。
     */
    public String[] scanDirectories = new String[]{"data"};

    /**
     * Whether to descend into subdirectories while scanning local directories.
     * <p>
     * 扫描本地目录时是否递归子目录。
     */
    public boolean recursiveScan = true;

    private final Configuration configuration;

    /**
     * Loads (or creates) the config file and reads the scan settings.
     * <p>
     * 加载（或创建）配置文件并读取扫描设置。
     *
     * @param file the config file suggested by FML / FML 建议的配置文件
     */
    public JarUtilsConfig(File file) {
        this.configuration = new Configuration(file);
        reload();
    }

    /**
     * Re-reads every setting from the config file, persisting the file when it
     * was just created or changed. / 从配置文件重新读取全部设置，
     * 文件刚创建或有变更时将其落盘。
     */
    public void reload() {
        scanModsJars = configuration.getBoolean(
                "scanModsJars", CATEGORY_SCAN, scanModsJars,
                "Whether to index entries from the jars of the mods directory. "
                        + "Set to false to skip jar scanning entirely. "
                        + "/ 是否索引 mods 目录下 jar 中的条目。设为 false 可完全跳过 jar 扫描。");
        scanDirectories = configuration.getStringList(
                "scanDirectories", CATEGORY_SCAN, scanDirectories,
                "Local directories to scan additionally, relative paths are resolved "
                        + "against the working directory, e.g. data, textures. "
                        + "/ 额外扫描的本地目录，相对路径基于工作目录解析，如 data、textures。");
        recursiveScan = configuration.getBoolean(
                "recursiveScan", CATEGORY_SCAN, recursiveScan,
                "Whether to descend into subdirectories while scanning local directories. "
                        + "/ 扫描本地目录时是否递归子目录。");
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * Resolves the configured directories against the working directory into
     * absolute {@link File}s. / 将配置的目录基于工作目录解析为绝对 {@link File}。
     *
     * @return the scan directories, never {@code null} / 扫描目录，恒非 {@code null}
     */
    public List<File> getScanDirectories() {
        List<File> dirs = new ArrayList<File>();
        if (scanDirectories != null) {
            for (String dir : scanDirectories) {
                if (dir == null || dir.trim().isEmpty()) {
                    continue;
                }
                File file = new File(dir);
                if (!file.isAbsolute()) {
                    file = new File(System.getProperty("user.dir"), dir);
                }
                dirs.add(file);
            }
        }
        return dirs;
    }
}
