package io.qzz.dfdvdsf.jarfile;

/**
 * Thrown when a file indexed by {@link JarUtil} cannot be opened any more —
 * e.g. the jar that held the entry was removed, or the local file was deleted
 * after the scan. Extends {@link NullPointerException} so callers may either
 * catch it explicitly or let it flow through existing null-handling code.
 * <p>
 * 当 {@link JarUtil} 已索引的文件无法再被打开时抛出——例如承载该条目的 jar
 * 已被移除，或本地文件在扫描后被删除。继承 {@link NullPointerException}，
 * 调用方既可以显式捕获，也可以交由既有的空值处理逻辑兜底。
 */
public class ObjectNotFindException extends NullPointerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception that marks the missing file path.
     * <p>
     * 创建一条标记缺失文件路径的异常。
     *
     * @param fileName the file path that cannot be found / 无法找到的文件路径
     */
    public ObjectNotFindException(String fileName) {
        super("Cannot find a file in jar:" + fileName);
    }
}
