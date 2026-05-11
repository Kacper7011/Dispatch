package dev.dispatch.sftp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileSession} implementation backed by the local machine's filesystem via {@code
 * java.nio.file}. Stateless and reusable — {@link #close()} is a no-op.
 */
public final class LocalFileSession implements FileSession {

  private static final Logger log = LoggerFactory.getLogger(LocalFileSession.class);
  private static final int BUFFER_SIZE = 65_536;

  @Override
  public String home() {
    return System.getProperty("user.home");
  }

  @Override
  public List<FileEntry> list(String path) {
    Path dir = Paths.get(path);
    if (!Files.isDirectory(dir)) {
      throw new SftpException("Not a directory: " + path);
    }
    List<FileEntry> result = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path child : stream) {
        FileEntry entry = toEntry(child);
        if (entry != null) {
          result.add(entry);
        }
      }
    } catch (IOException e) {
      throw new SftpException("Failed to list directory: " + path, e);
    }
    result.sort(
        Comparator.comparingInt((FileEntry e) -> e.isDirectory() ? 0 : 1)
            .thenComparing(e -> e.getName().toLowerCase()));
    Path parent = dir.getParent();
    result.add(0, FileEntry.parentLink(parent != null ? parent.toString() : path));
    return result;
  }

  private FileEntry toEntry(Path child) {
    try {
      BasicFileAttributes attrs =
          Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      String name = child.getFileName().toString();
      String fullPath = child.toString();
      LocalDateTime modified =
          LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
      if (attrs.isSymbolicLink()) {
        return FileEntry.symlink(name, fullPath, attrs.size(), modified);
      }
      if (attrs.isDirectory()) {
        return FileEntry.directory(name, fullPath, modified);
      }
      return FileEntry.file(name, fullPath, attrs.size(), modified);
    } catch (IOException e) {
      log.warn("Skipping unreadable entry: {}", child, e);
      return null;
    }
  }

  @Override
  public void download(String srcPath, OutputStream out, TransferMonitor monitor) {
    Path src = Paths.get(srcPath);
    long size = sizeQuietly(src);
    monitor.onStart(src.getFileName().toString(), size);
    try (InputStream in = Files.newInputStream(src)) {
      pump(in, out, monitor);
    } catch (IOException e) {
      throw new SftpException("Failed to read file: " + srcPath, e);
    }
    monitor.onComplete();
  }

  @Override
  public void upload(InputStream in, String destPath, long size, TransferMonitor monitor) {
    Path dest = Paths.get(destPath);
    monitor.onStart(dest.getFileName().toString(), size);
    try (OutputStream out =
        Files.newOutputStream(
            dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
      pump(in, out, monitor);
    } catch (IOException e) {
      throw new SftpException("Failed to write file: " + destPath, e);
    }
    monitor.onComplete();
  }

  private void pump(InputStream in, OutputStream out, TransferMonitor monitor) throws IOException {
    byte[] buf = new byte[BUFFER_SIZE];
    long transferred = 0;
    int read;
    while (!monitor.isCancelled() && (read = in.read(buf)) != -1) {
      out.write(buf, 0, read);
      transferred += read;
      monitor.onProgress(transferred);
    }
  }

  @Override
  public void mkdir(String path) {
    try {
      Files.createDirectory(Paths.get(path));
    } catch (IOException e) {
      throw new SftpException("Failed to create directory: " + path, e);
    }
  }

  @Override
  public void delete(String path, boolean recursive) {
    Path target = Paths.get(path);
    try {
      if (recursive && Files.isDirectory(target)) {
        Files.walk(target)
            .sorted(Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.delete(p);
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      } else {
        Files.delete(target);
      }
    } catch (IOException | UncheckedIOException e) {
      throw new SftpException("Failed to delete: " + path, e);
    }
  }

  @Override
  public void rename(String from, String to) {
    try {
      Files.move(Paths.get(from), Paths.get(to), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new SftpException("Failed to rename '" + from + "' → '" + to + "'", e);
    }
  }

  @Override
  public boolean isDirectory(String path) {
    return Files.isDirectory(Paths.get(path));
  }

  @Override
  public String displayName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "Local";
    }
  }

  @Override
  public String sessionId() {
    return "local";
  }

  @Override
  public String realpath(String path) {
    try {
      return Paths.get(path).toRealPath().toString();
    } catch (IOException e) {
      return path;
    }
  }

  @Override
  public void close() {}

  private long sizeQuietly(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return -1;
    }
  }
}
