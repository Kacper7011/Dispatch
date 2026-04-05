package dev.dispatch.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ImageInfo;
import dev.dispatch.docker.model.NetworkInfo;
import dev.dispatch.docker.model.VolumeInfo;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.Tunnel;
import dev.dispatch.ssh.TunnelService;
import io.reactivex.rxjava3.core.Observable;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a Docker connection to a remote host via an SSH tunnel.
 *
 * <p>Call {@link #connect(SshSession)} after the SSH session is established. All operations block —
 * call from a virtual thread. Log streaming returns an {@link Observable} that emits on the calling
 * thread; subscribe on {@code Schedulers.io()} and observe on the FX thread.
 */
public class DockerService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DockerService.class);

  private final TunnelService tunnelService;
  private Tunnel tunnel;
  private DockerClient dockerClient;
  private SshSession sshSession;

  public DockerService(TunnelService tunnelService) {
    this.tunnelService = tunnelService;
  }

  /**
   * Opens an SSH tunnel to the Docker socket and connects the docker-java client.
   *
   * @param session an active SSH session to the target host
   * @throws DockerException if the tunnel or client cannot be established
   */
  public void connect(SshSession session) {
    connect(session, "/var/run/docker.sock");
  }

  /**
   * Opens an SSH tunnel to a specific Docker socket path (e.g. rootless) and connects the client.
   *
   * @param session an active SSH session
   * @param socketPath Unix socket path on the remote host
   */
  public void connect(SshSession session, String socketPath) {
    log.debug("Connecting DockerService for host {}", session.getHost().getName());
    try {
      this.sshSession = session;
      tunnel = tunnelService.openTunnel(session, socketPath);
      dockerClient = buildClient(tunnel.getLocalPort());
      // Ping to verify the tunnel and daemon are actually reachable
      dockerClient.pingCmd().exec();
      log.info(
          "DockerService connected to {} via local port {}",
          session.getHost().getName(),
          tunnel.getLocalPort());
    } catch (Exception e) {
      closeQuietly();
      throw new DockerException(
          "Failed to connect DockerService for " + session.getHost().getName(), e);
    }
  }

  /** Closes the docker client and the SSH tunnel. Safe to call in any state. */
  @Override
  public void close() {
    closeQuietly();
  }

  /** Returns {@code true} if the client is connected and the tunnel is open. */
  public boolean isConnected() {
    return dockerClient != null && tunnel != null;
  }

  // -------------------------------------------------------------------------
  // Container operations
  // -------------------------------------------------------------------------

  /** Returns all containers (running + stopped), newest first. */
  public List<ContainerInfo> listContainers() {
    requireConnected();
    log.debug("Listing containers");
    return dockerClient.listContainersCmd().withShowAll(true).exec().stream()
        .map(DockerMapper::toContainerInfo)
        .toList();
  }

  /** Starts the container with the given id. */
  public void startContainer(String id) {
    requireConnected();
    log.info("Starting container {}", id);
    dockerClient.startContainerCmd(id).exec();
  }

  /** Stops the container with the given id (default timeout: 10 s). */
  public void stopContainer(String id) {
    requireConnected();
    log.info("Stopping container {}", id);
    dockerClient.stopContainerCmd(id).exec();
  }

  /** Restarts the container with the given id. */
  public void restartContainer(String id) {
    requireConnected();
    log.info("Restarting container {}", id);
    dockerClient.restartContainerCmd(id).exec();
  }

  /** Removes the container with the given id. Container must be stopped first. */
  public void removeContainer(String id) {
    requireConnected();
    log.info("Removing container {}", id);
    dockerClient.removeContainerCmd(id).exec();
  }

  // -------------------------------------------------------------------------
  // Log streaming
  // -------------------------------------------------------------------------

  /**
   * Returns a cold {@link Observable} that streams log lines from the given container.
   *
   * <p>Subscribe on {@code Schedulers.io()} — the underlying HTTP stream blocks. Dispose the
   * subscription to stop streaming and close the connection.
   *
   * @param containerId full or short container id
   * @return observable of UTF-8 log lines (stdout + stderr interleaved)
   */
  public Observable<String> streamLogs(String containerId) {
    requireConnected();
    return Observable.create(
        emitter -> {
          ResultCallback.Adapter<Frame> callback =
              new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                  if (!emitter.isDisposed() && frame.getPayload() != null) {
                    emitter.onNext(new String(frame.getPayload(), StandardCharsets.UTF_8));
                  }
                }

                @Override
                public void onError(Throwable t) {
                  emitter.onError(t);
                }

                @Override
                public void onComplete() {
                  emitter.onComplete();
                }
              };

          dockerClient
              .logContainerCmd(containerId)
              .withStdOut(true)
              .withStdErr(true)
              .withFollowStream(true)
              .withTailAll()
              .exec(callback);

          // Close the HTTP stream when the RxJava subscriber disposes
          emitter.setCancellable(callback::close);
        });
  }

  // -------------------------------------------------------------------------
  // Exec
  // -------------------------------------------------------------------------

  /**
   * Opens an interactive exec session inside the given running container via SSH — equivalent to
   * running {@code docker exec -it <id> bash} in a local terminal.
   *
   * <p>Uses an SSH {@code exec} channel with a PTY rather than the Docker HTTP exec API, which
   * avoids HTTP-hijacking limitations of the httpclient5 transport.
   *
   * @param containerId full or short container id (container must be running)
   * @return a {@link DockerExecSession} backed by a live JSch channel
   * @throws DockerException if the SSH channel cannot be opened
   */
  public DockerExecSession openExecSession(String containerId) {
    requireConnected();
    if (sshSession == null) {
      throw new DockerException("No SSH session available — call connect() first");
    }
    log.info("Opening exec session for container {} via SSH", containerId);
    // Prefer bash; fall back to sh — runs on the SSH host where Docker is installed
    String cmd =
        "docker exec -it "
            + containerId
            + " /bin/sh -c 'which bash >/dev/null 2>&1 && exec bash || exec sh'";
    try {
      Object[] result = sshSession.openInteractiveExec(cmd, 220, 50);
      return new DockerExecSession(
          (com.jcraft.jsch.ChannelExec) result[0],
          (java.io.InputStream) result[1],
          (java.io.OutputStream) result[2]);
    } catch (Exception e) {
      throw new DockerException("Failed to open exec for container " + containerId, e);
    }
  }

  // -------------------------------------------------------------------------
  // Image operations
  // -------------------------------------------------------------------------

  /** Returns all locally available images. */
  public List<ImageInfo> listImages() {
    requireConnected();
    log.debug("Listing images");
    return dockerClient.listImagesCmd().withShowAll(true).exec().stream()
        .map(DockerMapper::toImageInfo)
        .toList();
  }

  // -------------------------------------------------------------------------
  // Network operations
  // -------------------------------------------------------------------------

  /** Returns all Docker networks on the remote host. */
  public List<NetworkInfo> listNetworks() {
    requireConnected();
    log.debug("Listing networks");
    return dockerClient.listNetworksCmd().exec().stream().map(DockerMapper::toNetworkInfo).toList();
  }

  // -------------------------------------------------------------------------
  // Volume operations
  // -------------------------------------------------------------------------

  /** Returns all Docker volumes on the remote host. */
  public List<VolumeInfo> listVolumes() {
    requireConnected();
    log.debug("Listing volumes");
    var response = dockerClient.listVolumesCmd().exec();
    if (response.getVolumes() == null) return List.of();
    return response.getVolumes().stream().map(DockerMapper::toVolumeInfo).toList();
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private static DockerClient buildClient(int localPort) {
    String host = "tcp://localhost:" + localPort;
    DefaultDockerClientConfig config =
        DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(host).build();
    ApacheDockerHttpClient httpClient =
        new ApacheDockerHttpClient.Builder().dockerHost(URI.create(host)).build();
    return DockerClientImpl.getInstance(config, httpClient);
  }

  private void requireConnected() {
    if (!isConnected()) {
      throw new DockerException("DockerService is not connected — call connect() first");
    }
  }

  private void closeQuietly() {
    if (dockerClient != null) {
      try {
        dockerClient.close();
      } catch (IOException e) {
        log.warn("Error closing DockerClient: {}", e.getMessage());
      }
      dockerClient = null;
    }
    if (tunnel != null) {
      tunnel.close();
      tunnel = null;
    }
  }
}
