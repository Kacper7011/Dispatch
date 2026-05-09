package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import java.util.List;

/**
 * Holds drag state for the duration of one drag-and-drop gesture inside the file manager.
 * A plain static holder is safe here because JavaFX drag gestures are single-threaded
 * and never overlap.
 */
final class FileDragContext {

  static List<FileEntry> entries;
  static FileSession sourceSession;
  static FilePanelController sourcePanel;

  static void start(List<FileEntry> e, FileSession s, FilePanelController p) {
    entries = List.copyOf(e);
    sourceSession = s;
    sourcePanel = p;
  }

  static void clear() {
    entries = null;
    sourceSession = null;
    sourcePanel = null;
  }

  static boolean isActive() {
    return entries != null && !entries.isEmpty();
  }

  /**
   * Returns {@code true} when the drop target is the same session instance as the drag source.
   * Same session → move; different sessions → copy.
   */
  static boolean isSameSession(FileSession target) {
    return sourceSession == target;
  }

  private FileDragContext() {}
}
