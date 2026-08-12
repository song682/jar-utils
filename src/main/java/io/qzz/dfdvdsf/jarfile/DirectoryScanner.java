package io.qzz.dfdvdsf.jarfile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Walks local directories and indexes every file into {@link ResourceIndex},
 * regardless of suffix. Besides the directories supplied per scan, it tracks
 * directories registered from code via {@link #addScanDirectories(File...)} —
 * registered targets are scanned on top of the per-call ones, so mod developers
 * can ship scan targets without asking players to edit any config. All state
 * access is {@code synchronized}.
 * <p>
 * 遍历本地目录，将每个文件（不论后缀）索引进 {@link ResourceIndex}。除每次
 * 扫描传入的目录外，还跟踪经 {@link #addScanDirectories(File...)} 从代码注册
 * 的目录——注册目标在每次调用传入的目录之上叠加扫描，使模组开发者无需玩家
 * 修改配置即可内置扫描目标。所有状态访问均加 {@code synchronized}。
 */
public final class DirectoryScanner {

    private static final Logger LOGGER = LogManager.getLogger("JarUtils|DirectoryScanner");

    /**
     * Local directories registered from code via {@link #addScanDirectories(File...)};
     * scanned on top of whatever the per-call scan supplies.
     * <p>
     * 通过 {@link #addScanDirectories(File...)} 从代码注册的本地目录；
     * 在每次扫描传入的目录之上叠加扫描。
     */
    private static final Set<File> REGISTERED_DIRS = new HashSet<File>();

    private DirectoryScanner() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Registers local directories to scan from code, e.g. a mod developer
     * calling this from its own pre-initialization handler. Registered
     * directories are scanned on top of whatever the per-call scan supplies, so
     * developers can ship scan targets without asking players to edit the
     * config. Relative paths are resolved against the working directory;
     * re-registering the same directory is harmless — the set deduplicates.
     * <p>
     * 从代码注册需要扫描的本地目录，例如模组开发者在自身的预初始化处理器中
     * 调用。注册的目录会在每次扫描传入的目录之上叠加扫描，使开发者无需玩家
     * 修改配置即可内置扫描目标。相对路径基于工作目录解析；重复注册同一目录
     * 无副作用——集合按路径去重。
     *
     * @param dirs the directories to scan / 需要扫描的目录
     */
    public static synchronized void addScanDirectories(File... dirs) {
        if (dirs == null) {
            return;
        }
        for (File dir : dirs) {
            if (dir != null) {
                REGISTERED_DIRS.add(dir.getAbsoluteFile());
            }
        }
    }

    /**
     * Clears the registered directories. Package-private: exposed for tests to
     * isolate themselves and for a future re-scan flow after a config reload.
     * <p>
     * 清空已注册目录。包私有：供测试隔离用例，以及未来配置重载后的重新扫描流程。
     */
    static synchronized void clear() {
        REGISTERED_DIRS.clear();
    }

    /**
     * Walks every local directory — the given ones plus the ones registered via
     * {@link #addScanDirectories(File...)} — indexing every file into
     * {@link ResourceIndex}. Relative paths are resolved against the working
     * directory; indexing the same file twice is harmless — the index
     * deduplicates by path.
     * <p>
     * 遍历全部本地目录——给定的目录加上经 {@link #addScanDirectories(File...)}
     * 注册的目录——将每个文件索引进 {@link ResourceIndex}。相对路径基于工作目录
     * 解析；同一文件被重复索引无副作用——索引集按路径去重。
     *
     * @param scanDirs  local directories to scan, or {@code null} / 需要扫描的本地目录，
     *                  可为 {@code null}
     * @param recursive whether to descend into subdirectories when scanning
     *                  local directories / 扫描本地目录时是否递归子目录
     */
    public static synchronized void scanDirectories(@Nullable Collection<File> scanDirs, boolean recursive) {
        Set<File> dirs = new HashSet<File>(REGISTERED_DIRS);
        if (scanDirs != null) {
            for (File dir : scanDirs) {
                if (dir != null) {
                    dirs.add(dir.getAbsoluteFile());
                }
            }
        }
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                scanDirectory(dir, recursive);
            } else {
                LOGGER.warn("Scan directory does not exist or is not a directory: {}", dir);
            }
        }
    }

    /**
     * Recursively walks a local directory and indexes every file into
     * {@link ResourceIndex}. / 递归遍历本地目录，将每个文件索引到
     * {@link ResourceIndex}。
     *
     * @param dir       the directory to walk / 待遍历的目录
     * @param recursive whether to descend into subdirectories / 是否递归子目录
     */
    private static void scanDirectory(File dir, boolean recursive) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (recursive) {
                    scanDirectory(child, recursive);
                }
            } else if (child.isFile()) {
                ResourceIndex.addLocalFile(new UrlBuffered(child));
            }
        }
    }
}
