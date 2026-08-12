package io.qzz.dfdvdsf;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.qzz.dfdvdsf.jarfile.JarUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.util.List;

/**
 * Main mod class. Pre-initialization loads {@link JarUtilsConfig} — which
 * decides which directories are scanned — and post-initialization feeds
 * {@link JarUtil} with the mods directory and the configured local directories.
 * The scan deliberately runs late (post-init) so directories that other mods'
 * developers register from code via
 * {@code JarUtil#addScanDirectories(File...)} are all in place first.
 * <p>
 * 模组主类。预初始化阶段加载 {@link JarUtilsConfig}——它决定扫描哪些目录——
 * 初始化后阶段再将 mods 目录与配置的本地目录交给 {@link JarUtil}。扫描刻意
 * 延后到 post-init 执行，确保其它模组开发者通过
 * {@code JarUtil#addScanDirectories(File...)} 从代码注册的目录均已就绪。
 */
@Mod(
        modid = Tags.MODID,
        name = Tags.NAME,
        version = Tags.VERSION,
        useMetadata = true,
        acceptedMinecraftVersions = "[1.7.10]"
)
public class JarUtils {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);

    private File modsDir;
    private List<File> scanDirs;
    private boolean recursiveScan;

    /**
     * Loads the scan config and resolves the directories to scan.
     * <p>
     * 加载扫描配置并解析待扫描目录。
     *
     * @param event the pre-initialization event / 预初始化事件
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        JarUtilsConfig config = new JarUtilsConfig(event.getSuggestedConfigurationFile());
        // The mods directory sits next to the config directory; this also holds in dev.
        // mods 目录位于配置目录的平级位置；该推导在开发环境下同样成立。
        this.modsDir = config.scanModsJars
                ? new File(event.getModConfigurationDirectory().getParentFile(), "mods")
                : null;
        this.scanDirs = config.getScanDirectories();
        this.recursiveScan = config.recursiveScan;
    }

    /**
     * Builds the resource index. Runs at post-initialization so directories
     * registered by other mods' developers via
     * {@code JarUtil#addScanDirectories(File...)} are all in place before the
     * scan. / 构建资源索引。在初始化后阶段执行，确保其它模组开发者经
     * {@code JarUtil#addScanDirectories(File...)} 注册的目录都已就绪后再扫描。
     *
     * @param event the post-initialization event / 初始化后事件
     */
    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        JarUtil.scan(modsDir, scanDirs, recursiveScan);
        LOGGER.info("Indexed {} files, {} under data", JarUtil.getSet().size(), JarUtil.getDataSet().size());
    }
}
