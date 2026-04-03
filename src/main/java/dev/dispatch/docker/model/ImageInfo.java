package dev.dispatch.docker.model;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a local Docker image, as returned by {@code docker images}.
 *
 * <p>Immutable — construct directly via the public constructor.
 */
public class ImageInfo {

  private final String id;
  private final List<String> tags;
  private final long sizeBytes;
  private final Instant createdAt;

  public ImageInfo(String id, List<String> tags, long sizeBytes, Instant createdAt) {
    this.id = id;
    this.tags = tags != null ? List.copyOf(tags) : List.of();
    this.sizeBytes = sizeBytes;
    this.createdAt = createdAt;
  }

  /** Full image ID (SHA256 digest). */
  public String getId() {
    return id;
  }

  /** Short image ID — first 12 characters after the {@code sha256:} prefix (if present). */
  public String getShortId() {
    String raw = id.startsWith("sha256:") ? id.substring(7) : id;
    return raw.length() > 12 ? raw.substring(0, 12) : raw;
  }

  /**
   * All repository tags for this image, e.g. {@code ["nginx:latest", "nginx:1.25"]}. Empty list for
   * untagged images.
   */
  public List<String> getTags() {
    return tags;
  }

  /** Primary display tag — first tag in the list, or {@code "<none>"} for untagged images. */
  public String getPrimaryTag() {
    return tags.isEmpty() ? "<none>" : tags.get(0);
  }

  /** Compressed image size in bytes as reported by Docker. */
  public long getSizeBytes() {
    return sizeBytes;
  }

  /**
   * Human-readable size string, e.g. {@code "245 MB"}.
   *
   * <p>Uses SI units (1 MB = 1 000 000 bytes) to match what {@code docker images} displays.
   */
  public String getDisplaySize() {
    if (sizeBytes < 1_000) return sizeBytes + " B";
    if (sizeBytes < 1_000_000) return String.format("%.1f KB", sizeBytes / 1_000.0);
    if (sizeBytes < 1_000_000_000) return String.format("%.1f MB", sizeBytes / 1_000_000.0);
    return String.format("%.2f GB", sizeBytes / 1_000_000_000.0);
  }

  /** Timestamp when the image was created (built or pulled). */
  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    return "ImageInfo{id="
        + getShortId()
        + ", tag='"
        + getPrimaryTag()
        + "', size="
        + getDisplaySize()
        + "}";
  }
}
