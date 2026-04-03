package dev.dispatch.docker.model;

import java.time.Instant;
import java.util.List;

/**
 * Snapshot of a Docker container's state, as returned by {@code docker ps -a}.
 *
 * <p>Immutable — create via {@link Builder}. Designed to be safe to pass to the JavaFX thread.
 */
public class ContainerInfo {

  private final String id;
  private final String name;
  private final String image;
  private final ContainerStatus status;
  private final String statusText;
  private final List<PortBinding> ports;
  private final Instant createdAt;

  private ContainerInfo(Builder b) {
    this.id = b.id;
    this.name = b.name;
    this.image = b.image;
    this.status = b.status;
    this.statusText = b.statusText;
    this.ports = List.copyOf(b.ports);
    this.createdAt = b.createdAt;
  }

  /** Full 64-character container ID. */
  public String getId() {
    return id;
  }

  /** First 12 characters of the container ID — matches what {@code docker ps} displays. */
  public String getShortId() {
    return id.length() > 12 ? id.substring(0, 12) : id;
  }

  /** Container name without the leading {@code /}. */
  public String getName() {
    return name;
  }

  /** Image name and tag, e.g. {@code "nginx:latest"}. */
  public String getImage() {
    return image;
  }

  /** Parsed lifecycle state — use this for colour-coding in the UI. */
  public ContainerStatus getStatus() {
    return status;
  }

  /** Raw human-readable status string from Docker, e.g. {@code "Up 2 hours"}. */
  public String getStatusText() {
    return statusText;
  }

  /** Port mappings exposed by this container. Empty list if none. */
  public List<PortBinding> getPorts() {
    return ports;
  }

  /** Timestamp when the container was created. */
  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public String toString() {
    return "ContainerInfo{"
        + "id="
        + getShortId()
        + ", name='"
        + name
        + "', image='"
        + image
        + "', status="
        + status
        + "}";
  }

  /** Builder for {@link ContainerInfo}. */
  public static class Builder {

    private String id = "";
    private String name = "";
    private String image = "";
    private ContainerStatus status = ContainerStatus.UNKNOWN;
    private String statusText = "";
    private List<PortBinding> ports = List.of();
    private Instant createdAt = Instant.EPOCH;

    public Builder id(String id) {
      this.id = id;
      return this;
    }

    public Builder name(String name) {
      // Docker prefixes names with "/" — strip it for display
      this.name = name != null && name.startsWith("/") ? name.substring(1) : name;
      return this;
    }

    public Builder image(String image) {
      this.image = image;
      return this;
    }

    public Builder status(ContainerStatus status) {
      this.status = status;
      return this;
    }

    public Builder statusText(String statusText) {
      this.statusText = statusText;
      return this;
    }

    public Builder ports(List<PortBinding> ports) {
      this.ports = ports != null ? ports : List.of();
      return this;
    }

    public Builder createdAt(Instant createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public ContainerInfo build() {
      return new ContainerInfo(this);
    }
  }
}
