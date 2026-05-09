package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import java.time.format.DateTimeFormatter;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** Observable row model for a {@link TableView} entry in the file panel. */
public final class FileEntryRow {

  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final FileEntry entry;
  private final StringProperty name;
  private final StringProperty size;
  private final StringProperty date;

  public FileEntryRow(FileEntry entry) {
    this.entry = entry;
    this.name = new SimpleStringProperty(formatName(entry));
    this.size = new SimpleStringProperty(formatSize(entry));
    this.date = new SimpleStringProperty(formatDate(entry));
  }

  /** The underlying domain model. */
  public FileEntry getEntry() {
    return entry;
  }

  public StringProperty nameProperty() {
    return name;
  }

  public StringProperty sizeProperty() {
    return size;
  }

  public StringProperty dateProperty() {
    return date;
  }

  private String formatName(FileEntry e) {
    if (e.isParentLink()) return "..";
    return e.isDirectory() ? e.getName() + "/" : e.getName();
  }

  private String formatSize(FileEntry e) {
    if (e.isDirectory() || e.isParentLink()) return "<DIR>";
    long s = e.getSize();
    if (s < 1_024) return s + " B";
    if (s < 1_048_576) return String.format("%.1f KB", s / 1_024.0);
    if (s < 1_073_741_824) return String.format("%.1f MB", s / 1_048_576.0);
    return String.format("%.1f GB", s / 1_073_741_824.0);
  }

  private String formatDate(FileEntry e) {
    return e.getModifiedAt() != null ? DATE_FMT.format(e.getModifiedAt()) : "";
  }
}
