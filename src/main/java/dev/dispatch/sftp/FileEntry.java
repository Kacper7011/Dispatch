package dev.dispatch.sftp;

import java.time.LocalDateTime;

/**
 * Immutable snapshot of a single file-system entry (file, directory, or symlink). Used by both
 * {@link LocalFileSession} and {@code SftpFileSession} so the UI layer stays agnostic of the
 * underlying transport.
 */
public final class FileEntry {

  private final String name;
  private final String path;
  private final long size;
  private final boolean directory;
  private final boolean symlink;
  private final boolean parentLink;
  private final LocalDateTime modifiedAt;

  private FileEntry(
      String name,
      String path,
      long size,
      boolean directory,
      boolean symlink,
      boolean parentLink,
      LocalDateTime modifiedAt) {
    this.name = name;
    this.path = path;
    this.size = size;
    this.directory = directory;
    this.symlink = symlink;
    this.parentLink = parentLink;
    this.modifiedAt = modifiedAt;
  }

  /** Creates a regular file entry. */
  public static FileEntry file(String name, String path, long size, LocalDateTime modifiedAt) {
    return new FileEntry(name, path, size, false, false, false, modifiedAt);
  }

  /** Creates a directory entry. */
  public static FileEntry directory(String name, String path, LocalDateTime modifiedAt) {
    return new FileEntry(name, path, 0, true, false, false, modifiedAt);
  }

  /** Creates a symbolic link entry (treated as a file in the UI). */
  public static FileEntry symlink(String name, String path, long size, LocalDateTime modifiedAt) {
    return new FileEntry(name, path, size, false, true, false, modifiedAt);
  }

  /**
   * Creates the synthetic ".." parent-navigation entry. {@code parentPath} is the path the panel
   * should navigate to when this entry is activated.
   */
  public static FileEntry parentLink(String parentPath) {
    return new FileEntry("..", parentPath, 0, true, false, true, null);
  }

  /** Display name (file/dir name, or ".." for parent link). */
  public String getName() {
    return name;
  }

  /** Full path usable by the underlying {@link FileSession}. */
  public String getPath() {
    return path;
  }

  /** Size in bytes; 0 for directories. */
  public long getSize() {
    return size;
  }

  /** {@code true} if this entry represents a directory. */
  public boolean isDirectory() {
    return directory;
  }

  /** {@code true} if this entry is a symbolic link. */
  public boolean isSymlink() {
    return symlink;
  }

  /** {@code true} if this is the synthetic ".." parent-navigation entry. */
  public boolean isParentLink() {
    return parentLink;
  }

  /** Last-modified timestamp; may be {@code null} for the parent-link entry. */
  public LocalDateTime getModifiedAt() {
    return modifiedAt;
  }

  @Override
  public String toString() {
    return "FileEntry{name='" + name + "', path='" + path + "', dir=" + directory + '}';
  }
}
