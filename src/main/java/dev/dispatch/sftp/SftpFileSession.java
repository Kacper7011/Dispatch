package dev.dispatch.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import dev.dispatch.ssh.SshSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FileSession} implementation backed by JSch SFTP. One instance wraps one {@link
 * ChannelSftp} opened from an active {@link SshSession}.
 *
 * <p>Must be created and used from a virtual thread — all operations block.
 */
public final class SftpFileSession implements dev.dispatch.sftp.FileSession {

  private static final Logger log = LoggerFactory.getLogger(SftpFileSession.class);

  private final ChannelSftp sftp;
  private final String displayName;
  private final SshSession sshSession;

  /**
   * Opens an SFTP channel on the given connected SSH session.
   *
   * @throws dev.dispatch.sftp.SftpException if the channel cannot be opened or connected
   */
  public SftpFileSession(SshSession session) {
    this.displayName = session.getHost().getName();
    this.sshSession = session;
    try {
      ChannelSftp channel = session.openSftpChannel();
      channel.connect();
      this.sftp = channel;
      log.debug("SFTP channel open on {}", displayName);
    } catch (JSchException e) {
      throw new dev.dispatch.sftp.SftpException("Failed to open SFTP channel on " + displayName, e);
    }
  }

  @Override
  public String home() {
    try {
      return sftp.getHome();
    } catch (SftpException e) {
      throw wrap("Failed to get home directory", e);
    }
  }

  @Override
  public List<FileEntry> list(String path) {
    try {
      Vector<LsEntry> raw = sftp.ls(path);
      List<FileEntry> result = new ArrayList<>(raw.size());
      for (LsEntry entry : raw) {
        String name = entry.getFilename();
        if (name.equals(".") || name.equals("..")) continue;
        result.add(toEntry(path, entry));
      }
      result.sort(
          Comparator.comparingInt((FileEntry e) -> e.isDirectory() ? 0 : 1)
              .thenComparing(e -> e.getName().toLowerCase()));
      String parent = parentOf(path);
      result.add(0, FileEntry.parentLink(parent));
      return result;
    } catch (SftpException e) {
      throw wrap("Failed to list directory: " + path, e);
    }
  }

  private FileEntry toEntry(String dir, LsEntry entry) {
    SftpATTRS attrs = entry.getAttrs();
    String name = entry.getFilename();
    String fullPath = dir.endsWith("/") ? dir + name : dir + "/" + name;
    LocalDateTime modified =
        LocalDateTime.ofInstant(Instant.ofEpochSecond(attrs.getMTime()), ZoneId.systemDefault());
    if (attrs.isLink()) {
      try {
        SftpATTRS target = sftp.stat(fullPath);
        if (target.isDir()) return FileEntry.directory(name, fullPath, modified);
      } catch (SftpException ignored) {
        // broken symlink — keep as symlink entry
      }
      return FileEntry.symlink(name, fullPath, attrs.getSize(), modified);
    }
    if (attrs.isDir()) {
      return FileEntry.directory(name, fullPath, modified);
    }
    return FileEntry.file(name, fullPath, attrs.getSize(), modified);
  }

  @Override
  public void download(String srcPath, OutputStream out, TransferMonitor monitor) {
    try {
      sftp.get(srcPath, out, toSftpMonitor(monitor));
    } catch (SftpException e) {
      throw wrap("Failed to download: " + srcPath, e);
    }
  }

  @Override
  public void upload(InputStream in, String destPath, long size, TransferMonitor monitor) {
    try {
      sftp.put(in, destPath, toSftpMonitor(monitor), ChannelSftp.OVERWRITE);
    } catch (SftpException e) {
      throw wrap("Failed to upload to: " + destPath, e);
    }
  }

  @Override
  public void mkdir(String path) {
    try {
      sftp.mkdir(path);
    } catch (SftpException e) {
      throw wrap("Failed to create directory: " + path, e);
    }
  }

  @Override
  public void delete(String path, boolean recursive) {
    try {
      SftpATTRS attrs = sftp.stat(path);
      if (attrs.isDir()) {
        if (recursive) deleteDirectoryRecursive(path);
        else sftp.rmdir(path);
      } else {
        sftp.rm(path);
      }
    } catch (SftpException e) {
      throw wrap("Failed to delete: " + path, e);
    }
  }

  private void deleteDirectoryRecursive(String path) throws SftpException {
    Vector<LsEntry> entries = sftp.ls(path);
    for (LsEntry entry : entries) {
      String name = entry.getFilename();
      if (name.equals(".") || name.equals("..")) continue;
      String child = path + "/" + name;
      if (entry.getAttrs().isDir()) deleteDirectoryRecursive(child);
      else sftp.rm(child);
    }
    sftp.rmdir(path);
  }

  @Override
  public void rename(String from, String to) {
    try {
      sftp.rename(from, to);
    } catch (SftpException e) {
      throw wrap("Failed to rename '" + from + "' → '" + to + "'", e);
    }
  }

  @Override
  public boolean isDirectory(String path) {
    try {
      return sftp.stat(path).isDir();
    } catch (SftpException e) {
      return false;
    }
  }

  @Override
  public String displayName() {
    return displayName;
  }

  @Override
  public String sessionId() {
    return displayName;
  }

  @Override
  public String realpath(String path) {
    try {
      return sftp.realpath(path);
    } catch (SftpException e) {
      return path;
    }
  }

  /** Returns the underlying {@link SshSession} so an elevated sudo session can reuse it. */
  public dev.dispatch.ssh.SshSession getUnderlyingSession() {
    return sshSession;
  }

  @Override
  public void close() {
    if (sftp.isConnected()) {
      sftp.disconnect();
      log.debug("SFTP channel closed on {}", displayName);
    }
  }

  private String parentOf(String path) {
    if (path.equals("/")) return "/";
    int slash = path.lastIndexOf('/');
    if (slash <= 0) return "/";
    return path.substring(0, slash);
  }

  private SftpProgressMonitor toSftpMonitor(TransferMonitor monitor) {
    return new SftpProgressMonitor() {
      private String filename;
      private long cumulative;

      @Override
      public void init(int op, String src, String dest, long max) {
        filename = src.contains("/") ? src.substring(src.lastIndexOf('/') + 1) : src;
        cumulative = 0;
        monitor.onStart(filename, max);
      }

      @Override
      public boolean count(long chunkBytes) {
        // JSch passes per-chunk bytes; TransferMonitor expects cumulative.
        monitor.onProgress(cumulative += chunkBytes);
        return !monitor.isCancelled();
      }

      @Override
      public void end() {
        monitor.onComplete();
      }
    };
  }

  private dev.dispatch.sftp.SftpException wrap(String message, SftpException cause) {
    return new dev.dispatch.sftp.SftpException(message, cause);
  }
}
