package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import java.util.function.Predicate;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Per-panel filter state for the file browser. Encapsulates hide-dotfiles, dirs-only, and
 * name-substring predicates. The parent-link entry ("..") is always visible regardless of filters.
 */
public final class FilePanelFilter {

  private final BooleanProperty hideHidden = new SimpleBooleanProperty(false);
  private final BooleanProperty dirsOnly = new SimpleBooleanProperty(false);
  private final StringProperty namePattern = new SimpleStringProperty("");

  /**
   * Returns {@code true} when at least one filter is non-default, i.e. the visible set may differ
   * from the full directory listing.
   */
  public boolean isActive() {
    return hideHidden.get() || dirsOnly.get() || !namePattern.get().isBlank();
  }

  /**
   * Builds a combined predicate from the current filter state. Snapshot semantics: the predicate is
   * immutable once created; call again after any change.
   */
  public Predicate<FileEntryRow> asPredicate() {
    boolean hidden = hideHidden.get();
    boolean dirsOnlyVal = dirsOnly.get();
    String pattern = namePattern.get().strip().toLowerCase();

    return row -> {
      FileEntry e = row.getEntry();
      if (e.isParentLink()) return true;
      if (hidden && e.getName().startsWith(".")) return false;
      if (dirsOnlyVal && !e.isDirectory()) return false;
      if (!pattern.isEmpty() && !e.getName().toLowerCase().contains(pattern)) return false;
      return true;
    };
  }

  /** Resets all filters to their defaults (no filtering). */
  public void reset() {
    hideHidden.set(false);
    dirsOnly.set(false);
    namePattern.set("");
  }

  public BooleanProperty hideHiddenProperty() {
    return hideHidden;
  }

  public BooleanProperty dirsOnlyProperty() {
    return dirsOnly;
  }

  public StringProperty namePatternProperty() {
    return namePattern;
  }
}
