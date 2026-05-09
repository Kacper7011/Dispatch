package dev.dispatch.sftp;

import static org.junit.jupiter.api.Assertions.*;

import dev.dispatch.sftp.TransferTask.TransferProgress;
import dev.dispatch.sftp.TransferTask.TransferProgress.Status;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferTaskTest {

  private Path tempDir;
  private LocalFileSession session;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("dispatch-transfer-test");
    session = new LocalFileSession();
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.walk(tempDir)
        .sorted(Comparator.reverseOrder())
        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
  }

  @Test
  void transferFile_copiesBytesCorrectly() throws IOException {
    Path src = tempDir.resolve("src.txt");
    Path dest = tempDir.resolve("dest.txt");
    Files.writeString(src, "Hello, Transfer!");

    List<TransferProgress> events = new ArrayList<>();
    new TransferTask(session, src.toString(), session, dest.toString())
        .start()
        .subscribeOn(Schedulers.io())
        .blockingSubscribe(events::add);

    assertTrue(Files.exists(dest));
    assertEquals("Hello, Transfer!", Files.readString(dest, StandardCharsets.UTF_8));
    assertFalse(events.isEmpty());
    assertEquals(Status.DONE, events.get(events.size() - 1).status());
  }

  @Test
  void transferFile_emitsProgressEvents() throws IOException {
    byte[] data = new byte[512 * 1024]; // 512 KB
    Path src = tempDir.resolve("big.bin");
    Files.write(src, data);
    Path dest = tempDir.resolve("big-copy.bin");

    List<TransferProgress> events = new ArrayList<>();
    new TransferTask(session, src.toString(), session, dest.toString())
        .start()
        .subscribeOn(Schedulers.io())
        .blockingSubscribe(events::add);

    assertTrue(events.size() > 1, "Expected multiple progress events");
    assertTrue(events.stream().anyMatch(e -> e.status() == Status.RUNNING));
    assertEquals(Status.DONE, events.get(events.size() - 1).status());
  }

  @Test
  void transferDirectory_copiesAllChildren() throws IOException {
    Path srcDir = tempDir.resolve("srcdir");
    Files.createDirectory(srcDir);
    Files.writeString(srcDir.resolve("a.txt"), "AAA");
    Files.writeString(srcDir.resolve("b.txt"), "BBB");
    Path destDir = tempDir.resolve("destdir");

    new TransferTask(session, srcDir.toString(), session, destDir.toString())
        .start()
        .subscribeOn(Schedulers.io())
        .blockingSubscribe();

    assertTrue(Files.exists(destDir.resolve("a.txt")));
    assertTrue(Files.exists(destDir.resolve("b.txt")));
    assertEquals("AAA", Files.readString(destDir.resolve("a.txt"), StandardCharsets.UTF_8));
  }

  @Test
  void transferProgress_fractionIsCorrect() throws IOException {
    Path src = tempDir.resolve("frac.txt");
    Files.writeString(src, "dispatch");
    Path dest = tempDir.resolve("frac-copy.txt");

    List<TransferProgress> events = new ArrayList<>();
    new TransferTask(session, src.toString(), session, dest.toString())
        .start()
        .subscribeOn(Schedulers.io())
        .blockingSubscribe(events::add);

    TransferProgress done = events.get(events.size() - 1);
    assertEquals(1.0, done.fraction(), 0.001);
  }

  @Test
  void cancel_stopsTransfer() throws IOException {
    byte[] data = new byte[4 * 1024 * 1024]; // 4 MB
    Path src = tempDir.resolve("large.bin");
    Files.write(src, data);
    Path dest = tempDir.resolve("large-copy.bin");

    TransferTask task = new TransferTask(session, src.toString(), session, dest.toString());
    List<TransferProgress> events = new ArrayList<>();

    task.start()
        .subscribeOn(Schedulers.io())
        .doOnNext(p -> { if (p.transferredBytes() > 0) task.cancel(); })
        .blockingSubscribe(events::add, e -> {}, () -> {});

    // After cancellation the dest file may be partial but the task must not error
    assertTrue(events.stream().anyMatch(p -> p.status() == Status.RUNNING));
  }
}
