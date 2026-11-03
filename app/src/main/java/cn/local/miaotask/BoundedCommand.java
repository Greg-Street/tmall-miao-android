package cn.local.miaotask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class BoundedCommand {
    private final Set<Process> processes = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "shell-timeout"); thread.setDaemon(true); return thread;
    });
    private volatile boolean closed;

    byte[] run(long timeoutMillis, int maxBytes, String... command) throws IOException {
        AtomicBoolean timedOut = new AtomicBoolean();
        Process process;
        java.util.concurrent.ScheduledFuture<?> timeout;
        synchronized (this) {
            if (closed) throw new IOException("执行服务已结束");
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            processes.add(process);
            timeout = watchdog.schedule(() -> { timedOut.set(true); process.destroyForcibly(); }, timeoutMillis, TimeUnit.MILLISECONDS);
        }
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maxBytes) throw new IOException("系统命令输出超过大小限制");
                output.write(buffer, 0, count);
            }
            int code = process.waitFor();
            if (timedOut.get()) throw new IOException("系统命令超时");
            if (code != 0) {
                String reason = new String(output.toByteArray(), StandardCharsets.UTF_8).trim().replaceAll("[\\r\\n\\t]+", "；");
                if (reason.length() > 600) reason = reason.substring(0, 600) + "…";
                throw new IOException("系统命令失败（" + code + "）" + (reason.isEmpty() ? "" : "：" + reason));
            }
            return output.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("系统命令已取消", e);
        } finally {
            timeout.cancel(false); processes.remove(process); process.destroyForcibly();
        }
    }

    void cancel() { for (Process process : processes) process.destroyForcibly(); }
    synchronized void close() { closed = true; cancel(); watchdog.shutdownNow(); }
}
