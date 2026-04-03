package dev.dispatch.docker.model;

/**
 * Lifecycle state of a Docker container, mapped from the raw string returned by the Docker API.
 *
 * <p>Used by the UI to colour-code status badges in the container table.
 */
public enum ContainerStatus {

  /** Container process is running. */
  RUNNING,

  /** Container has stopped (process exited). */
  EXITED,

  /** Container is paused (SIGSTOP sent to all processes). */
  PAUSED,

  /** Container is in the process of restarting. */
  RESTARTING,

  /** Container was created but never started. */
  CREATED,

  /** Container is being removed. */
  REMOVING,

  /** Container is in a broken state. */
  DEAD,

  /** Docker returned a state string that this enum does not recognise. */
  UNKNOWN;

  /**
   * Maps a raw Docker API state string (e.g. {@code "running"}, {@code "exited"}) to the
   * corresponding enum constant. Returns {@link #UNKNOWN} for unrecognised values.
   */
  public static ContainerStatus from(String raw) {
    if (raw == null) return UNKNOWN;
    return switch (raw.toLowerCase()) {
      case "running" -> RUNNING;
      case "exited" -> EXITED;
      case "paused" -> PAUSED;
      case "restarting" -> RESTARTING;
      case "created" -> CREATED;
      case "removing" -> REMOVING;
      case "dead" -> DEAD;
      default -> UNKNOWN;
    };
  }
}
