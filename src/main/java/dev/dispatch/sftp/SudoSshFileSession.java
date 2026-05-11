package dev.dispatch.sftp;

import dev.dispatch.ssh.SshSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
/**
 * {@link FileSession} implementation that runs all operations via {@code sudo -S} over an
 * existing SSH connection. Used when the regular SFTP session is denied access to a path.
 *
 * <p>The {@code sudo} password is supplied once at construction and injected into each
 * command's stdin. All blocking operations must be called from a virtual thread.
 *
 * <p>This session does not own the underlying {@link SshSession} and therefore does not
 * close it in {@link #close()}.
 */
public final class SudoSshFileSession implements FileSession {

  private static final String SUDO = "sudo -S -p '' ";

  private final SshSession ssh;
  private final byte[] passwordBytes;
  private final String displayName;

  /**
   * Creates a sudo-elevated session backed by {@code ssh}.
   *
   * @param ssh      an already-connected {@link SshSession}
   * @param password the sudo password for the remote user
   */
  public SudoSshFileSession(SshSession ssh, String password) {
    this.ssh = ssh;
    this.passwordBytes = (password + "\n").getBytes(StandardCharsets.UTF_8);
    this.displayName = ssh.getHost().getName() + " [root]";
  }

  @Override
  public String home() {
    return "/root";
  }

  @Override
  public List<FileEntry> list(String path) {
    String cmd = SUDO + "ls -la --time-style=+%Y-%m-%dT%H:%M:%S " + shellEscape(path);
    dev.dispatch.ssh.ExecResult result = ssh.execWithStdin(cmd, passwordBytes);
    if (!result.isSuccess()) {
      String msg = result.getStderr().trim();
      throw new SftpException("Cannot list " + path + ": " + msg);
    }
    return parseLsOutput(path, result.getStdout());
  }

  @Override
  public void download(String srcPath, OutputStream out, TransferMonitor monitor) {
    String name = baseName(srcPath);
    monitor.onStart(name, 0);
    InputStream stdinStream = new ByteArrayInputStream(passwordBytes);
    int exitCode = ssh.execStreaming(SUDO + "cat " + shellEscape(srcPath), stdinStream, out);
    if (exitCode != 0) {
      throw new SftpException("sudo download failed for " + srcPath);
    }
    monitor.onComplete();
  }

  @Override
  public void upload(InputStream in, String destPath, long size, TransferMonitor monitor) {
    String name = baseName(destPath);
    monitor.onStart(name, size);
    InputStream stdinStream = new SequenceInputStream(
        new ByteArrayInputStream(passwordBytes), in);
    // tee copies stdin to both the file and stdout; we discard stdout in Java.
    int exitCode = ssh.execStreaming(
        SUDO + "tee " + shellEscape(destPath), stdinStream, OutputStream.nullOutputStream());
    if (exitCode != 0) {
      throw new SftpException("sudo upload failed for " + destPath);
    }
    monitor.onComplete();
  }

  @Override
  public void mkdir(String path) {
    exec("mkdir " + shellEscape(path), "sudo mkdir failed: " + path);
  }

  @Override
  public void delete(String path, boolean recursive) {
    String flag = recursive ? "-rf " : "-f ";
    exec("rm " + flag + shellEscape(path), "sudo delete failed: " + path);
  }

  @Override
  public void rename(String from, String to) {
    exec("mv " + shellEscape(from) + " " + shellEscape(to),
        "sudo rename failed: " + from + " → " + to);
  }

  @Override
  public boolean isDirectory(String path) {
    dev.dispatch.ssh.ExecResult r = ssh.execWithStdin(
        SUDO + "test -d " + shellEscape(path), passwordBytes);
    return r.getExitCode() == 0;
  }

  @Override
  public String displayName() {
    return displayName;
  }

  @Override
  public String sessionId() {
    return ssh.getHost().getName();
  }

  @Override
  public String realpath(String path) {
    dev.dispatch.ssh.ExecResult r = ssh.execWithStdin(
        SUDO + "realpath " + shellEscape(path), passwordBytes);
    return r.isSuccess() ? r.getStdout().trim() : path;
  }

  @Override
  public void close() {
    // Does not own the SshSession — nothing to release.
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private void exec(String sudoArgs, String errorContext) {
    dev.dispatch.ssh.ExecResult r = ssh.execWithStdin(SUDO + sudoArgs, passwordBytes);
    if (!r.isSuccess()) {
      throw new SftpException(errorContext + ": " + r.getStderr().trim());
    }
  }

  private List<FileEntry> parseLsOutput(String dir, String stdout) {
    List<FileEntry> result = new ArrayList<>();
    for (String line : stdout.split("\n")) {
      String trimmed = line.strip();
      if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue;
      // Format: perms links owner group size datetime name[ -> target]
      String[] parts = trimmed.split("\\s+", 7);
      if (parts.length < 7) continue;
      String perms = parts[0];
      long size;
      try { size = Long.parseLong(parts[4]); } catch (NumberFormatException e) { continue; }
      LocalDateTime modified;
      try { modified = LocalDateTime.parse(parts[5]); }
      catch (Exception e) { modified = LocalDateTime.now(); }
      String rest = parts[6];
      boolean isSymlink = perms.charAt(0) == 'l';
      String name = (isSymlink && rest.contains(" -> "))
          ? rest.substring(0, rest.indexOf(" -> "))
          : rest;
      if (name.equals(".") || name.equals("..")) continue;
      String fullPath = dir.equals("/") ? "/" + name : dir + "/" + name;
      FileEntry entry;
      if (perms.charAt(0) == 'd') {
        entry = FileEntry.directory(name, fullPath, modified);
      } else if (isSymlink) {
        entry = FileEntry.symlink(name, fullPath, size, modified);
      } else {
        entry = FileEntry.file(name, fullPath, size, modified);
      }
      result.add(entry);
    }
    result.sort(
        Comparator.comparingInt((FileEntry e) -> e.isDirectory() ? 0 : 1)
            .thenComparing(e -> e.getName().toLowerCase()));
    result.add(0, FileEntry.parentLink(parentOf(dir)));
    return result;
  }

  private static String parentOf(String path) {
    if (path.equals("/")) return "/";
    int slash = path.lastIndexOf('/');
    return slash <= 0 ? "/" : path.substring(0, slash);
  }

  private static String baseName(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }

  /**
   * Wraps {@code s} in single quotes and escapes embedded single quotes so the result is
   * safe to embed in a shell command string (e.g. paths with spaces or apostrophes).
   */
  private static String shellEscape(String s) {
    return "'" + s.replace("'", "'\\''") + "'";
  }
}
