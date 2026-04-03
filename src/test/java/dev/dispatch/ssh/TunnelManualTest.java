package dev.dispatch.ssh;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import dev.dispatch.docker.DockerDetector;
import dev.dispatch.docker.DockerPresence;

/**
 * Manual test harness for TunnelService + DockerDetector.
 *
 * <p>Run main() directly from the IDE (right-click → Run).
 */
public class TunnelManualTest {

  // --- Configure these before running ---
  private static final String HOST = "10.10.10.115";
  private static final int PORT = 22;
  private static final String USERNAME = "kacper";
  private static final String KEY_PATH = "~/.ssh/prox_mac_ed25519"; // null → use PASSWORD
  private static final String PASSWORD = null;

  public static void main(String[] args) throws Exception {
    Host host =
        new Host(
            "test-host",
            HOST,
            PORT,
            USERNAME,
            KEY_PATH != null ? AuthType.KEY : AuthType.PASSWORD,
            KEY_PATH);

    SshCredentials credentials =
        KEY_PATH != null ? SshCredentials.keyNoPassphrase() : SshCredentials.password(PASSWORD);

    System.out.println("=== SSH ===");
    System.out.println("Connecting to " + HOST + "...");
    SshSession session = new SshSession(host);
    session.connect(credentials);
    System.out.println("Connected.\n");

    System.out.println("=== Docker Detection ===");
    DockerDetector detector = new DockerDetector();
    DockerPresence presence = detector.detect(session);
    System.out.println("Result: " + presence);

    if (!presence.isAvailable()) {
      System.out.println("Docker not found — stopping test.");
      session.disconnect();
      return;
    }

    String socketPath = presence.getSocketPath();
    if (presence.isRootless()) {
      socketPath = detector.resolveRootlessSocketPath(session);
      System.out.println("Rootless socket resolved: " + socketPath);
    }

    System.out.println("\n=== Tunnel ===");
    try (TunnelService tunnelService = new TunnelService();
        Tunnel tunnel = tunnelService.openTunnel(session, socketPath)) {
      int localPort = tunnel.getLocalPort();
      System.out.println("Tunnel open: localhost:" + localPort + " → " + HOST + ":" + socketPath);
      System.out.println();
      System.out.println("Run in another terminal:");
      System.out.println(
          "  curl -s http://localhost:"
              + localPort
              + "/v1.41/containers/json | python3 -m json.tool | head -40");
      System.out.println();
      System.out.println("Press Enter to close...");
      System.in.read();
    }

    session.disconnect();
    System.out.println("Done.");
  }
}
