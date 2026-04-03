package dev.dispatch.docker;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ImageInfo;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshSession;
import dev.dispatch.ssh.TunnelService;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;

/** Manual test harness for DockerService. Run main() directly from the IDE. */
public class DockerServiceManualTest {

  private static final String HOST = "10.10.10.115";
  private static final int PORT = 22;
  private static final String USERNAME = "kacper";
  private static final String KEY_PATH = "~/.ssh/prox_mac_ed25519"; // null → PASSWORD
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

    System.out.println("=== Connecting via SSH ===");
    SshSession session = new SshSession(host);
    session.connect(credentials);
    System.out.println("Connected.\n");

    DockerDetector detector = new DockerDetector();
    DockerPresence presence = detector.detect(session);
    System.out.println("Docker: " + presence);
    if (!presence.isAvailable()) {
      System.out.println("Docker not found — aborting.");
      session.disconnect();
      return;
    }

    String socketPath =
        presence.isRootless()
            ? detector.resolveRootlessSocketPath(session)
            : presence.getSocketPath();

    try (TunnelService tunnelService = new TunnelService();
        DockerService docker = new DockerService(tunnelService)) {

      docker.connect(session, socketPath);
      System.out.println("DockerService connected.\n");

      System.out.println("=== Containers ===");
      List<ContainerInfo> containers = docker.listContainers();
      containers.forEach(
          c ->
              System.out.printf(
                  "  %-15s %-30s %s%n", c.getShortId(), c.getName(), c.getStatusText()));

      System.out.println("\n=== Images ===");
      List<ImageInfo> images = docker.listImages();
      images.forEach(
          img ->
              System.out.printf(
                  "  %-15s %-40s %s%n",
                  img.getShortId(), img.getPrimaryTag(), img.getDisplaySize()));

      if (!containers.isEmpty()) {
        ContainerInfo first = containers.get(0);
        System.out.println("\n=== Logs: " + first.getName() + " (5 s) ===");
        docker
            .streamLogs(first.getId())
            .subscribeOn(Schedulers.io())
            .take(20)
            .blockingForEach(line -> System.out.print(line));
      }

      System.out.println("\n\nPress Enter to disconnect...");
      System.in.read();
    }

    session.disconnect();
    System.out.println("Done.");
  }
}
