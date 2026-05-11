package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recursively computes the total on-disk byte count for a batch of entries.
 * All methods block — call from a virtual thread, never from the FX thread.
 */
final class TransferSizeScanner {

  private static final Logger log = LoggerFactory.getLogger(TransferSizeScanner.class);

  private TransferSizeScanner() {}

  /**
   * Returns the total size in bytes of all entries, recursing into directories.
   * List errors are swallowed — the affected subtree counts as zero bytes.
   */
  static long scan(FileSession session, List<FileEntry> entries) {
    long total = 0;
    for (FileEntry e : entries) {
      if (e.isParentLink() || e.isSymlink()) continue;
      total += e.isDirectory() ? scanDir(session, e.getPath()) : e.getSize();
    }
    return total;
  }

  private static long scanDir(FileSession session, String path) {
    long total = 0;
    try {
      for (FileEntry e : session.list(path)) {
        if (e.isParentLink() || e.isSymlink()) continue;
        total += e.isDirectory() ? scanDir(session, e.getPath()) : e.getSize();
      }
    } catch (Exception e) {
      log.debug("Size scan failed for {}: {}", path, e.getMessage());
    }
    return total;
  }
}
