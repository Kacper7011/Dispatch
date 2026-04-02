package dev.dispatch.ssh;

import com.jcraft.jsch.ChannelDirectStreamLocal;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An active SSH tunnel that forwards a local TCP port to a remote Unix domain socket.
 *
 * <p>Each incoming TCP connection spawns a {@code direct-streamlocal} SSH channel to the remote
 * socket and pipes data bidirectionally. Close the tunnel to stop accepting new connections and
 * release the local port.
 */
public class Tunnel implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Tunnel.class);

  private final int localPort;
  private final ServerSocket serverSocket;
  private final SshSession sshSession;
  private final String remoteSocketPath;
  private final List<Socket> activeConnections = new CopyOnWriteArrayList<>();

  Tunnel(int localPort, ServerSocket serverSocket, SshSession sshSession, String remoteSocketPath) {
    this.localPort = localPort;
    this.serverSocket = serverSocket;
    this.sshSession = sshSession;
    this.remoteSocketPath = remoteSocketPath;
  }

  /** Returns the local TCP port that clients should connect to. */
  public int getLocalPort() {
    return localPort;
  }

  /** Closes the tunnel: stops the accept loop and closes all active proxy connections. */
  @Override
  public void close() {
    log.debug("Closing tunnel on local port {}", localPort);
    try {
      serverSocket.close();
    } catch (IOException e) {
      log.warn("Error closing server socket on port {}: {}", localPort, e.getMessage());
    }
    for (Socket conn : activeConnections) {
      closeQuietly(conn);
    }
    activeConnections.clear();
    log.info("Tunnel on local port {} closed", localPort);
  }

  /** Starts the accept loop on a virtual thread. Called by {@link TunnelService}. */
  void start() {
    Thread.ofVirtual().name("tunnel-accept-" + localPort).start(this::acceptLoop);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void acceptLoop() {
    log.debug("Tunnel accept loop started on port {}", localPort);
    while (!serverSocket.isClosed()) {
      try {
        Socket client = serverSocket.accept();
        activeConnections.add(client);
        log.debug(
            "New tunnel connection on port {} ({} active)", localPort, activeConnections.size());
        Thread.ofVirtual().name("tunnel-conn-" + localPort).start(() -> handleConnection(client));
      } catch (IOException e) {
        if (!serverSocket.isClosed()) {
          log.error("Tunnel accept error on port {}: {}", localPort, e.getMessage(), e);
        }
      }
    }
    log.debug("Tunnel accept loop ended on port {}", localPort);
  }

  private void handleConnection(Socket client) {
    ChannelDirectStreamLocal channel = null;
    try {
      channel = openStreamLocalChannel();
      pipe(client, channel);
    } catch (Exception e) {
      log.error(
          "Tunnel connection error on port {} → {}: {}",
          localPort,
          remoteSocketPath,
          e.getMessage(),
          e);
    } finally {
      activeConnections.remove(client);
      closeQuietly(client);
      if (channel != null) {
        channel.disconnect();
      }
    }
  }

  private ChannelDirectStreamLocal openStreamLocalChannel() {
    ChannelDirectStreamLocal channel =
        (ChannelDirectStreamLocal) sshSession.openJschChannel("direct-streamlocal@openssh.com");
    channel.setSocketPath(remoteSocketPath);
    try {
      channel.connect();
    } catch (com.jcraft.jsch.JSchException e) {
      throw new SshException(
          "Failed to connect direct-streamlocal channel to " + remoteSocketPath, e);
    }
    return channel;
  }

  private void pipe(Socket client, ChannelDirectStreamLocal channel)
      throws IOException, InterruptedException {
    InputStream clientIn = client.getInputStream();
    OutputStream clientOut = client.getOutputStream();
    InputStream channelIn = channel.getInputStream();
    OutputStream channelOut = channel.getOutputStream();

    // Upload: client → SSH channel (virtual thread)
    Thread upload =
        Thread.ofVirtual()
            .name("tunnel-upload-" + localPort)
            .start(() -> copyStream(clientIn, channelOut));

    // Download: SSH channel → client (current virtual thread)
    copyStream(channelIn, clientOut);
    upload.join();
  }

  private void copyStream(InputStream in, OutputStream out) {
    byte[] buf = new byte[8192];
    try {
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException e) {
      // Stream closed — normal end of connection
      log.debug("Stream copy ended on port {}: {}", localPort, e.getMessage());
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
    }
  }
}
