package io.qzz.dfdvdsf.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Small shared-thread-pool helper used by the jar-processing utilities to run
 * independent IO-bound tasks (jar scanning, extraction, hashing) in parallel.
 * <p>
 * The pool is a process-wide singleton of daemon threads: it is created lazily,
 * reused across calls, and never blocks JVM shutdown. The thread count defaults
 * to the number of available processors and can be reconfigured at any time via
 * {@link #setThreadCount(int)}.
 * <p>
 * 供 jar 处理工具使用的小型共享线程池辅助类，用于并行执行相互独立的 IO 密集任务
 * （jar 扫描、提取、哈希等）。
 * <p>
 * 线程池是进程级单例，使用 daemon 线程：懒创建、跨调用复用、不阻止 JVM 退出。
 * 线程数默认取可用处理器数量，可随时通过 {@link #setThreadCount(int)} 重新配置。
 */
public final class Parallel {

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    private static volatile int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());

    private static volatile ExecutorService executor = newPool();

    private Parallel() {
        // Static utility, no instances. / 纯静态工具类，禁止实例化。
    }

    /**
     * Runs {@code fn} over every input concurrently and returns the results in the same
     * order as the inputs. {@code null} inputs are passed through as-is. Any task failure
     * aborts the whole call with the underlying cause unwrapped.
     * <p>
     * 对每个输入并发执行 {@code fn}，返回结果且顺序与输入一致。
     * 输入为 {@code null} 时原样透传。任一任务失败即中止整个调用，并解包底层原因抛出。
     *
     * @param inputs inputs to process / 待处理的输入
     * @param fn     the per-input mapping function / 单输入映射函数
     * @param <T>    input type / 输入类型
     * @param <R>    result type / 结果类型
     * @return results in input order, {@code null} entries preserved
     *         （按输入顺序返回的结果列表，允许包含 {@code null}）
     */
    public static <T, R> List<R> map(Collection<T> inputs, Function<T, R> fn) {
        List<R> results = new ArrayList<R>();
        if (inputs == null || inputs.isEmpty()) {
            return results;
        }
        // Capture the pool once: a concurrent setThreadCount() swap must not mix submissions.
        // 一次性捕获线程池引用：并发调用 setThreadCount() 换池时不允许提交到旧池。
        ExecutorService pool = executor;
        List<Future<R>> futures = new ArrayList<Future<R>>(inputs.size());
        for (final T input : inputs) {
            futures.add(pool.submit(new Callable<R>() {
                @Override
                public R call() {
                    return fn.apply(input);
                }
            }));
        }
        for (Future<R> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for parallel task", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                throw (cause instanceof RuntimeException)
                        ? (RuntimeException) cause
                        : new RuntimeException("Parallel task failed", cause);
            }
        }
        return results;
    }

    /**
     * Returns the current shared pool size. / 返回当前共享线程池大小。
     *
     * @return current thread count / 当前线程数
     */
    public static int getThreadCount() {
        return threadCount;
    }

    /**
     * Reconfigures the shared pool size. The old pool is shut down gracefully after its
     * queued tasks complete; do not call this while a batch operation is running.
     * <p>
     * 重新配置共享线程池大小。旧线程池在排队任务执行完毕后优雅关闭；
     * 不要在批量操作进行中调用本方法。
     *
     * @param threads new pool size, must be positive / 新线程数，必须为正
     */
    public static synchronized void setThreadCount(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("Thread count must be positive: " + threads);
        }
        if (threads == threadCount) {
            return;
        }
        threadCount = threads;
        executor.shutdown();
        executor = newPool();
    }

    private static ExecutorService newPool() {
        return Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jarutils-parallel-" + THREAD_SEQ.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }
}
