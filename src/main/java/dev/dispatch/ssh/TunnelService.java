package dev.dispatch.ssh;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens and manages SSH port-forward tunnels to remote Unix domain sockets.
 *
 * <p>Primary use case: forward a local TCP port to {@code /var/run/docker.sock} on a remote host,
 * so docker-java can connect to Docker without exposing a TCP port on the remote machine.
 */
public class TunnelService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(TunnelService.class);
  private static final String DOCKER_SOCKET_PATH = "/var/run/docker.sock";

  /** Tunnels keyed by host id. One host can have multiple open tunnels. */
  private final Map<Long, List<Tunnel>> tunnelsByHost = new ConcurrentHashMap<>();

  /**
   * Opens a local TCP port that proxies to {@code /var/run/docker.sock} on the remote host.
   *
   * @param session an active SSH session to the remote host
   * @return the open tunnel; call {@code getLocalPort()} for the port to give docker-java
   * @throws SshException if the session is not connected or the local port cannot be bound
   */
  public Tunnel openDockerTunnel(SshSession session) {
    return openTunnel(session, DOCKER_SOCKET_PATH);
  }

  /**
   * Opens a local TCP port that proxies to a specific Unix socket on the remote host.
   *
   * @param session an active SSH session
   * @param remoteSocketPath path to the Unix socket on the remote host
   * @return the open tunnel
   * @throws SshException if the session is not connected or the local port cannot be bound
   */
  public Tunnel openTunnel(SshSession session, String remoteSocketPath) {
    requireConnected(session);
    try {
      ServerSocket serverSocket = new ServerSocket(0);
      int localPort = serverSocket.getLocalPort();
      Tunnel tunnel = new Tunnel(localPort, serverSocket, session, remoteSocketPath);
      tunnel.start();
      trackTunnel(session.getHost().getId(), tunnel);
      log.info(
          "Tunnel opened: localhost:{} → {}:{}",
          localPort,
          session.getHost().getName(),
          remoteSocketPath);
      return tunnel;
    } catch (IOException e) {
      throw new SshException("Failed to bind local port for tunnel to " + remoteSocketPath, e);
    }
  }

  /** Closes all tunnels associated with the given session. Call when a session disconnects. */
  public void closeAllTunnels(SshSession session) {
    List<Tunnel> tunnels = tunnelsByHost.remove(session.getHost().getId());
    if (tunnels == null || tunnels.isEmpty()) {
      return;
    }
    log.debug("Closing {} tunnel(s) for host {}", tunnels.size(), session.getHost().getName());
    tunnels.forEach(Tunnel::close);
  }

  /** Closes all tunnels for all sessions. Call from {@code App.stop()}. */
  @Override
  public void close() {
    log.info("Closing all tunnels ({} host(s) with active tunnels)", tunnelsByHost.size());
    tunnelsByHost.values().forEach(list -> list.forEach(Tunnel::close));
    tunnelsByHost.clear();
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private static void requireConnected(SshSession session) {
    if (!session.isConnected()) {
      throw new SshException(
          "Cannot open tunnel: session for " + session.getHost().getName() + " is not connected");
    }
  }

  private void trackTunnel(long hostId, Tunnel tunnel) {
    tunnelsByHost.computeIfAbsent(hostId, id -> new CopyOnWriteArrayList<>()).add(tunnel);
  }
}
