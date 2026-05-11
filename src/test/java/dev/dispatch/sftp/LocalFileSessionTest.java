package dev.dispatch.sftp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalFileSessionTest {

  private Path tempDir;
  private LocalFileSession session;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("dispatch-sftp-test");
    session = new LocalFileSession();
  }

  @AfterEach
  void tearDown() throws IOException {
    Files.walk(tempDir)
        .sorted(Comparator.reverseOrder())
        .forEach(
            p -> {
              try {
                Files.deleteIfExists(p);
              } catch (IOException ignored) {
              }
            });
  }

  @Test
  void home_returnsJavaUserHomeProperty() {
    assertEquals(System.getProperty("user.home"), session.home());
  }

  @Test
  void displayName_returnsLocal() {
    assertEquals("Local", session.displayName());
  }

  @Test
  void list_firstEntryIsParentLink() throws IOException {
    Files.createFile(tempDir.resolve("a.txt"));
    List<FileEntry> entries = session.list(tempDir.toString());
    assertTrue(entries.get(0).isParentLink(), "First entry must be parent link");
    assertEquals("..", entries.get(0).getName());
  }

  @Test
  void list_sortsDirectoriesBeforeFiles() throws IOException {
    Files.createFile(tempDir.resolve("z_file.txt"));
    Files.createDirectory(tempDir.resolve("a_dir"));
    List<FileEntry> entries = session.list(tempDir.toString());
    assertTrue(entries.get(1).isDirectory(), "Directory must precede file");
    assertFalse(entries.get(2).isDirectory(), "File must follow directory");
  }

  @Test
  void list_sortsCaseInsensitively() throws IOException {
    Files.createFile(tempDir.resolve("Beta.txt"));
    Files.createFile(tempDir.resolve("alpha.txt"));
    Files.createFile(tempDir.resolve("Gamma.txt"));
    List<FileEntry> entries = session.list(tempDir.toString());
    List<String> names =
        entries.stream().filter(e -> !e.isParentLink()).map(FileEntry::getName).toList();
    assertEquals(List.of("alpha.txt", "Beta.txt", "Gamma.txt"), names);
  }

  @Test
  void list_throwsOnNonDirectory() throws IOException {
    Path file = Files.createFile(tempDir.resolve("notadir.txt"));
    assertThrows(SftpException.class, () -> session.list(file.toString()));
  }

  @Test
  void mkdir_createsDirectory() {
    String newDir = tempDir.resolve("newdir").toString();
    session.mkdir(newDir);
    assertTrue(Files.isDirectory(Path.of(newDir)));
  }

  @Test
  void delete_removesFile() throws IOException {
    Path file = Files.createFile(tempDir.resolve("del.txt"));
    session.delete(file.toString(), false);
    assertFalse(Files.exists(file));
  }

  @Test
  void delete_removesDirectoryRecursively() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("subdir"));
    Files.createFile(dir.resolve("child.txt"));
    session.delete(dir.toString(), true);
    assertFalse(Files.exists(dir));
  }

  @Test
  void rename_movesFile() throws IOException {
    Path src = Files.createFile(tempDir.resolve("old.txt"));
    Path dest = tempDir.resolve("new.txt");
    session.rename(src.toString(), dest.toString());
    assertFalse(Files.exists(src));
    assertTrue(Files.exists(dest));
  }

  @Test
  void downloadAndUpload_roundtrip() throws IOException {
    Path src = tempDir.resolve("source.txt");
    Files.writeString(src, "Hello, Dispatch!");

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    session.download(src.toString(), buf, TransferMonitor.noop());

    Path dest = tempDir.resolve("dest.txt");
    byte[] bytes = buf.toByteArray();
    session.upload(
        new ByteArrayInputStream(bytes), dest.toString(), bytes.length, TransferMonitor.noop());

    assertEquals("Hello, Dispatch!", Files.readString(dest, StandardCharsets.UTF_8));
  }

  @Test
  void isDirectory_distinguishesDirsFromFiles() throws IOException {
    Path dir = Files.createDirectory(tempDir.resolve("adir"));
    Path file = Files.createFile(tempDir.resolve("afile.txt"));
    assertTrue(session.isDirectory(dir.toString()));
    assertFalse(session.isDirectory(file.toString()));
  }

  @Test
  void list_fileEntriesHaveCorrectSize() throws IOException {
    byte[] content = "dispatch".getBytes(StandardCharsets.UTF_8);
    Path file = tempDir.resolve("sized.txt");
    Files.write(file, content);
    List<FileEntry> entries = session.list(tempDir.toString());
    FileEntry entry =
        entries.stream().filter(e -> e.getName().equals("sized.txt")).findFirst().orElseThrow();
    assertEquals(content.length, entry.getSize());
  }
}
