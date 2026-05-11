package dev.dispatch.sftp;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SshCredentials;
import dev.dispatch.ssh.SshSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Scanner;

/**
 * Manual integration test — requires a real SSH host. Configure HOST/PORT/USERNAME/PASSWORD below,
 * then run via: ./gradlew test --tests "dev.dispatch.sftp.SftpFileSessionManualTest"
 */
public class SftpFileSessionManualTest {

  private static final String HOST = "your-host";
  private static final int PORT = 22;
  private static final String USERNAME = "your-user";
  private static final String PASSWORD = "your-password";

  public static void main(String[] args) throws Exception {
    Host host = new Host("test", HOST, PORT, USERNAME, AuthType.PASSWORD, null);
    SshSession session = new SshSession(host);
    session.connect(SshCredentials.password(PASSWORD));
    System.out.println("SSH connected");

    try (SftpFileSession sftp = new SftpFileSession(session)) {
      String home = sftp.home();
      System.out.println("Home: " + home);

      System.out.println("\n--- list(" + home + ") ---");
      List<FileEntry> entries = sftp.list(home);
      for (FileEntry e : entries) {
        String type = e.isParentLink() ? "[..]" : e.isDirectory() ? "[DIR]" : "     ";
        System.out.printf("%s %-30s %8d%n", type, e.getName(), e.getSize());
      }

      String testFile = home + "/dispatch-sftp-test.txt";
      String content = "Hello from Dispatch SFTP!";
      System.out.println("\n--- upload to " + testFile + " ---");
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      sftp.upload(new ByteArrayInputStream(bytes), testFile, bytes.length, TransferMonitor.noop());
      System.out.println("Upload OK");

      System.out.println("\n--- download from " + testFile + " ---");
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      sftp.download(testFile, buf, TransferMonitor.noop());
      System.out.println("Downloaded: " + buf.toString(StandardCharsets.UTF_8));

      System.out.println("\n--- mkdir + rename + delete ---");
      String dir = home + "/dispatch-test-dir";
      sftp.mkdir(dir);
      System.out.println("mkdir OK: " + dir);
      String renamed = home + "/dispatch-test-dir-renamed";
      sftp.rename(dir, renamed);
      System.out.println("rename OK → " + renamed);
      sftp.delete(renamed, true);
      sftp.delete(testFile, false);
      System.out.println("delete OK");
    }

    System.out.println("\nPress Enter to disconnect...");
    try (Scanner sc = new Scanner(System.in)) {
      sc.nextLine();
    }
    session.disconnect();
    System.out.println("Done.");
  }
}
