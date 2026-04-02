package dev.dispatch.ssh;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;

/**
 * Manual test harness for TunnelService.
 *
 * <p>Connects to a real SSH host, opens a tunnel to /var/run/docker.sock, then prints the local
 * port. Use curl to verify the tunnel works.
 *
 * <p>Run: ./gradlew test --tests "dev.dispatch.ssh.TunnelManualTest" (will fail — see main())
 * Better: run main() directly from IDE, or via a Gradle exec task.
 */
public class TunnelManualTest {

  // --- Configure these before running ---
  private static final String HOST = "10.10.10.115"; // your homelab host IP
  private static final int PORT = 22;
  private static final String USERNAME = "kacper";
  // Set one: KEY_PATH for key auth, or PASSWORD for password auth
  private static final String KEY_PATH = "~/.ssh/prox_mac_ed25519"; // set null to use password
  private static final String PASSWORD = null; // set null to use key auth

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
        KEY_PATH != null
            ? SshCredentials.keyNoPassphrase()
            : SshCredentials.password(PASSWORD);

    System.out.println("Connecting to " + HOST + "...");
    SshSession session = new SshSession(host);
    session.connect(credentials);
    System.out.println("Connected.");

    try (TunnelService tunnelService = new TunnelService();
        Tunnel tunnel = tunnelService.openDockerTunnel(session)) {
      int localPort = tunnel.getLocalPort();
      System.out.println("Tunnel open on local port: " + localPort);
      System.out.println();
      System.out.println("Now run in another terminal:");
      System.out.println(
          "  curl -s http://localhost:" + localPort + "/v1.41/containers/json | head -c 500");
      System.out.println();
      System.out.println("Press Enter to close tunnel and disconnect...");
      System.in.read();
    }

    session.disconnect();
    System.out.println("Done.");
  }
}
