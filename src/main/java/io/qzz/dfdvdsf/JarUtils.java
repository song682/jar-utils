package io.qzz.dfdvdsf;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.qzz.dfdvdsf.jarfile.JarUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Main mod class. On pre-initialization it loads {@link JarUtilsConfig} —
 * which decides which directories are scanned — and feeds {@link JarUtil} with
 * the mods directory and the configured local directories, so the resource
 * index is ready before any mod logic consumes it.
 * <p>
 * 模组主类。预初始化阶段加载 {@link JarUtilsConfig}——它决定扫描哪些目录——
 * 并将 mods 目录与配置的本地目录交给 {@link JarUtil}，使资源索引在任何
 * 模组逻辑消费之前就绪。
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

    /**
     * Loads the scan config and builds the resource index.
     * <p>
     * 加载扫描配置并构建资源索引。
     *
     * @param event the pre-initialization event / 预初始化事件
     */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        JarUtilsConfig config = new JarUtilsConfig(event.getSuggestedConfigurationFile());
        // The mods directory sits next to the config directory; this also holds in dev.
        // mods 目录位于配置目录的平级位置；该推导在开发环境下同样成立。
        File modsDir = config.scanModsJars
                ? new File(event.getModConfigurationDirectory().getParentFile(), "mods")
                : null;
        JarUtil.scan(modsDir, config.getScanDirectories(), config.recursiveScan);
        LOGGER.info("Indexed {} files, {} under data", JarUtil.getSet().size(), JarUtil.getDataSet().size());
    }
}
