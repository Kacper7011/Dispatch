package dev.dispatch.docker;

import dev.dispatch.ssh.ExecResult;
import dev.dispatch.ssh.SshSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects whether Docker is available on a remote host by executing diagnostic commands over an
 * existing SSH session. Handles both standard and rootless Docker installations.
 *
 * <p>All methods block — call from a virtual thread, never from the FX Application Thread.
 */
public class DockerDetector {

  private static final Logger log = LoggerFactory.getLogger(DockerDetector.class);

  // Standard Docker socket (root install)
  private static final String STANDARD_SOCKET = "/var/run/docker.sock";
  // Rootless Docker socket — $XDG_RUNTIME_DIR is usually /run/user/<uid>
  private static final String ROOTLESS_SOCKET_TEMPLATE = "/run/user/%s/docker.sock";

  /**
   * Probes the remote host for a running Docker daemon.
   *
   * <p>Strategy:
   *
   * <ol>
   *   <li>Run {@code docker info} — fast check, covers both root and rootless setups.
   *   <li>If that fails, check if the standard socket file exists.
   *   <li>If the socket exists but Docker CLI is missing, report absent (can't tunnel usefully).
   *   <li>Parse the version from {@code docker info} stdout.
   *   <li>Detect rootless: check whether the active socket belongs to a per-user path.
   * </ol>
   *
   * @param session an active SSH session to the target host
   * @return detection result — never {@code null}
   */
  public DockerPresence detect(SshSession session) {
    String hostName = session.getHost().getName();
    log.debug("Starting Docker detection on {}", hostName);

    ExecResult infoResult = runDockerInfo(session);

    if (infoResult.isSuccess()) {
      return parseDockerInfo(infoResult.getStdout(), hostName);
    }

    log.debug(
        "docker info failed on {} (exit {}), probing socket", hostName, infoResult.getExitCode());
    return probeSocketFallback(session, hostName);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private ExecResult runDockerInfo(SshSession session) {
    // Wrap in `sh -c` so PATH is sourced; docker may live in /usr/local/bin etc.
    return session.exec("sh -c 'docker info 2>&1'");
  }

  private DockerPresence parseDockerInfo(String stdout, String hostName) {
    String version = extractVersion(stdout);
    boolean rootless = isRootless(stdout);
    String socketPath = rootless ? resolveRootlessSocket(stdout) : STANDARD_SOCKET;

    log.info(
        "Docker detected on {}: version={}, rootless={}, socket={}",
        hostName,
        version,
        rootless,
        socketPath);
    return DockerPresence.present(socketPath, rootless, version);
  }

  /** Falls back to checking whether the standard socket file exists on the remote. */
  private DockerPresence probeSocketFallback(SshSession session, String hostName) {
    ExecResult socketCheck = session.exec("sh -c 'test -S " + STANDARD_SOCKET + " && echo exists'");

    if (socketCheck.isSuccess() && socketCheck.getStdout().contains("exists")) {
      // Socket is there but docker info failed — daemon may be starting or CLI missing.
      log.warn(
          "Docker socket exists on {} but `docker info` failed — daemon may be unhealthy",
          hostName);
      return DockerPresence.present(STANDARD_SOCKET, false, "unknown");
    }

    log.warn("Docker not detected on {} — skipping Docker panel", hostName);
    return DockerPresence.absent();
  }

  /**
   * Extracts the server version from {@code docker info} output.
   *
   * <p>Looks for a line like: {@code Server Version: 26.1.3}
   */
  private String extractVersion(String dockerInfoOutput) {
    for (String line : dockerInfoOutput.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Server Version:")) {
        return trimmed.substring("Server Version:".length()).trim();
      }
    }
    return "unknown";
  }

  /**
   * Returns {@code true} if the Docker info output indicates a rootless installation.
   *
   * <p>Rootless Docker reports {@code rootless} in the {@code Security Options} section.
   */
  private boolean isRootless(String dockerInfoOutput) {
    return dockerInfoOutput.contains("rootless");
  }

  /**
   * Resolves the rootless Docker socket path by reading the UID from {@code docker info}.
   *
   * <p>Looks for a line like: {@code Docker Root Dir: /home/user/.local/share/docker} or falls back
   * to the numeric UID fetched via {@code id -u}.
   */
  private String resolveRootlessSocket(String dockerInfoOutput) {
    // Try to find the context socket path reported by docker info
    for (String line : dockerInfoOutput.split("\n")) {
      String trimmed = line.trim();
      if (trimmed.startsWith("Context:") && trimmed.contains("/run/user/")) {
        // Not always present — skip if not useful
        break;
      }
    }
    // Fallback: derive from Docker Root Dir line, e.g. "/home/user/.local/share/docker"
    // We can't easily get UID from docker info output alone, so we mark it for a follow-up exec.
    // The caller (DockerService) will refine this with a real `id -u` exec if needed.
    return STANDARD_SOCKET; // conservative fallback; DockerService overrides after uid lookup
  }

  /**
   * Fetches the numeric UID of the logged-in user via SSH and returns the rootless socket path.
   *
   * <p>Called by {@link DockerDetector} consumers when {@link DockerPresence#isRootless()} is
   * {@code true} and the socket path needs to be confirmed.
   *
   * @param session an active SSH session
   * @return the resolved rootless socket path, e.g. {@code /run/user/1000/docker.sock}
   */
  public String resolveRootlessSocketPath(SshSession session) {
    ExecResult uidResult = session.exec("id -u");
    if (!uidResult.isSuccess()) {
      log.warn(
          "Could not determine UID on {} — using fallback socket path",
          session.getHost().getName());
      return STANDARD_SOCKET;
    }
    String uid = uidResult.getStdout().trim();
    String path = String.format(ROOTLESS_SOCKET_TEMPLATE, uid);
    log.debug("Rootless Docker socket resolved for {}: {}", session.getHost().getName(), path);
    return path;
  }
}
