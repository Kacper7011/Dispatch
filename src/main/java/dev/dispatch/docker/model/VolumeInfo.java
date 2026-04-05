package dev.dispatch.docker.model;

/**
 * Snapshot of a Docker volume, as returned by {@code docker volume ls}.
 *
 * <p>Immutable — construct directly via the public constructor.
 */
public class VolumeInfo {

  private final String name;
  private final String driver;
  private final String mountpoint;

  public VolumeInfo(String name, String driver, String mountpoint) {
    this.name = name != null ? name : "";
    this.driver = driver != null ? driver : "";
    this.mountpoint = mountpoint != null ? mountpoint : "";
  }

  /** Volume name. */
  public String getName() {
    return name;
  }

  /** Volume driver (e.g. {@code local}). */
  public String getDriver() {
    return driver;
  }

  /** Absolute mountpoint path on the host (e.g. {@code /var/lib/docker/volumes/…/_data}). */
  public String getMountpoint() {
    return mountpoint;
  }

  @Override
  public String toString() {
    return "VolumeInfo{name='" + name + "', driver='" + driver + "'}";
  }
}
