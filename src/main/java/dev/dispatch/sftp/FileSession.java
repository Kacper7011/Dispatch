package dev.dispatch.sftp;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Uniform interface for browsing and transferring files, regardless of whether the
 * underlying storage is the local filesystem ({@link LocalFileSession}) or a remote host
 * reachable via SFTP ({@code SftpFileSession}).
 *
 * <p>All blocking methods must be called from a virtual thread, never from the
 * FX Application Thread.
 */
public interface FileSession extends AutoCloseable {

  /** Returns the home/default directory for this session (e.g. {@code /home/user}). */
  String home();

  /**
   * Lists the contents of {@code path}. The first entry is always the synthetic ".."
   * parent-navigation entry (see {@link FileEntry#parentLink}). Entries are sorted:
   * directories before files, both groups case-insensitively alphabetical.
   *
   * @throws SftpException if the path does not exist or is not a directory
   */
  List<FileEntry> list(String path);

  /**
   * Reads the file at {@code srcPath} and writes all bytes to {@code out},
   * reporting progress through {@code monitor}.
   *
   * @throws SftpException on I/O failure
   */
  void download(String srcPath, OutputStream out, TransferMonitor monitor);

  /**
   * Reads all bytes from {@code in} and writes them to {@code destPath},
   * reporting progress through {@code monitor}. Creates or overwrites the destination file.
   *
   * @param size expected byte count (used to initialise the progress bar); pass {@code -1} if unknown
   * @throws SftpException on I/O failure
   */
  void upload(InputStream in, String destPath, long size, TransferMonitor monitor);

  /**
   * Creates a new directory at {@code path}.
   *
   * @throws SftpException if the directory already exists or the parent is not writable
   */
  void mkdir(String path);

  /**
   * Deletes the file or directory at {@code path}.
   *
   * @param recursive if {@code true}, deletes directories and all their contents
   * @throws SftpException on I/O failure or if {@code recursive} is false and the target is
   *     a non-empty directory
   */
  void delete(String path, boolean recursive);

  /**
   * Renames (moves) {@code from} to {@code to}. Overwrites {@code to} if it already exists.
   *
   * @throws SftpException on I/O failure
   */
  void rename(String from, String to);

  /** Returns {@code true} if {@code path} refers to an existing directory. */
  boolean isDirectory(String path);

  /**
   * Human-readable label for this session (e.g. {@code "Local"} or a hostname).
   * Displayed in the panel header.
   */
  String displayName();

  /**
   * Opaque identifier used to determine whether two sessions refer to the same host
   * (same id → move semantics; different id → copy semantics).
   * {@link LocalFileSession} returns {@code "local"}; SFTP sessions return the hostname.
   */
  String sessionId();

  /**
   * Resolves the canonical (real) absolute path — follows symlinks.
   * Used by {@link dev.dispatch.sftp.TransferTask} to detect symlink cycles during
   * directory copies. Implementations that cannot resolve paths may return {@code path} unchanged.
   */
  default String realpath(String path) {
    return path;
  }

  /** Releases all resources held by this session. Never throws. */
  @Override
  void close();
}
