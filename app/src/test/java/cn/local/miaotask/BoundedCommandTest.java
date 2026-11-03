package cn.local.miaotask;

import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class BoundedCommandTest {
    public static class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "text": System.out.print("hello"); break;
                case "fail": System.out.println("Starting: MAIN"); System.err.println("Error: unable to resolve Intent"); System.exit(7); break;
                case "longfail": System.err.print("x".repeat(4000)); System.exit(8); break;
                case "large": System.out.write(new byte[64 * 1024]); break;
                case "sleep": Thread.sleep(10000); break;
                case "ready": Files.write(Path.of(args[1]), new byte[]{1}); Thread.sleep(10000); break;
                default: throw new IllegalArgumentException();
            }
        }
    }
    private String[] child(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(), Child.class.getName()));
        command.addAll(List.of(args)); return command.toArray(new String[0]);
    }
    @Test public void commandOutputReturnsNormallyWithinItsBudget() throws Exception {
        BoundedCommand command = new BoundedCommand();
        try { assertEquals("hello", new String(command.run(4000, 128, child("text")), StandardCharsets.UTF_8)); }
        finally { command.close(); }
    }
    @Test public void aFailedCommandCannotLookLikeSuccessfulEmptyOutput() throws Exception {
        BoundedCommand command = new BoundedCommand(); String[] args = child("fail");
        try { assertTrue(assertThrows(IOException.class, () -> command.run(4000, 128, args)).getMessage().contains("7")); }
        finally { command.close(); }
    }
    @Test public void aFailedLaunchIncludesBothSystemOutputAndTheErrorReason() throws Exception {
        BoundedCommand command = new BoundedCommand(); String[] args = child("fail");
        try {
            String message = assertThrows(IOException.class, () -> command.run(4000, 4096, args)).getMessage();
            assertTrue(message.contains("7")); assertTrue(message.contains("Starting: MAIN"));
            assertTrue(message.contains("unable to resolve Intent"));
        } finally { command.close(); }
    }
    @Test public void longErrorsRemainReadableWithoutFillingTheRunLog() throws Exception {
        BoundedCommand command = new BoundedCommand(); String[] args = child("longfail");
        try {
            String message = assertThrows(IOException.class, () -> command.run(4000, 8192, args)).getMessage();
            assertTrue(message.contains("8")); assertTrue(message.length() < 700);
            assertTrue(message.contains("xxx"));
        } finally { command.close(); }
    }
    @Test public void oversizedOutputIsTerminatedInsteadOfGrowingWithoutLimit() throws Exception {
        BoundedCommand command = new BoundedCommand(); String[] args = child("large");
        try { assertTrue(assertThrows(IOException.class, () -> command.run(4000, 1024, args)).getMessage().contains("大小限制")); }
        finally { command.close(); }
    }
    @Test(timeout = 8000) public void aHangingCommandIsKilledByTheWatchdog() throws Exception {
        BoundedCommand command = new BoundedCommand(); String[] args = child("sleep");
        long started = System.nanoTime();
        try { assertThrows(IOException.class, () -> command.run(250, 128, args)); }
        finally { command.close(); }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4000);
    }
    @Test(timeout = 10000) public void pauseCancellationStopsAnAlreadyRunningCommand() throws Exception {
        BoundedCommand command = new BoundedCommand();
        Path directory = Files.createTempDirectory("miao-command-test"); Path ready = directory.resolve("ready");
        String[] args = child("ready", ready.toString()); var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> command.run(8000, 128, args));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue("Child started before cancellation", Files.exists(ready));
            command.cancel();
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        } finally { command.close(); executor.shutdownNow(); Files.deleteIfExists(ready); Files.deleteIfExists(directory); }
    }
    @Test public void aDestroyedServiceCannotStartMoreCommands() throws Exception {
        BoundedCommand command = new BoundedCommand(); command.close(); String[] args = child("text");
        assertThrows(IOException.class, () -> command.run(1000, 128, args));
    }
}
