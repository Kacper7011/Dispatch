package dev.dispatch.docker;

/**
 * Result of Docker detection on a remote host.
 *
 * <p>If {@code available} is {@code false}, all other fields are meaningless. If {@code available}
 * is {@code true}, {@code socketPath} points to the Unix socket that should be tunnelled.
 */
public class DockerPresence {

  private final boolean available;
  private final String socketPath;
  private final boolean rootless;
  private final String serverVersion;

  private DockerPresence(
      boolean available, String socketPath, boolean rootless, String serverVersion) {
    this.available = available;
    this.socketPath = socketPath;
    this.rootless = rootless;
    this.serverVersion = serverVersion;
  }

  /** Docker was not found or is not responding on the remote host. */
  public static DockerPresence absent() {
    return new DockerPresence(false, null, false, null);
  }

  /**
   * Docker was found and is responding.
   *
   * @param socketPath path to the Unix socket (differs for rootless installs)
   * @param rootless {@code true} if this is a rootless Docker installation
   * @param serverVersion Docker Engine version string, e.g. {@code "26.1.3"}
   */
  public static DockerPresence present(String socketPath, boolean rootless, String serverVersion) {
    return new DockerPresence(true, socketPath, rootless, serverVersion);
  }

  /** Returns {@code true} if Docker is available and responding. */
  public boolean isAvailable() {
    return available;
  }

  /** Path to the Docker Unix socket on the remote host. */
  public String getSocketPath() {
    return socketPath;
  }

  /**
   * Returns {@code true} if Docker runs in rootless mode (socket path differs from the default
   * {@code /var/run/docker.sock}).
   */
  public boolean isRootless() {
    return rootless;
  }

  /** Docker Engine version string reported by {@code docker info}. */
  public String getServerVersion() {
    return serverVersion;
  }

  @Override
  public String toString() {
    if (!available) return "DockerPresence{absent}";
    return "DockerPresence{version="
        + serverVersion
        + ", rootless="
        + rootless
        + ", socket="
        + socketPath
        + "}";
  }
}
