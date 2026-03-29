package dev.dispatch.ssh;

/**
 * Credentials used to authenticate an SSH session. Instantiate via static factory methods — never
 * store these long-term; fetch from KeyStore just before connecting.
 */
public class SshCredentials {

  private final String password;
  private final String keyPassphrase;

  private SshCredentials(String password, String keyPassphrase) {
    this.password = password;
    this.keyPassphrase = keyPassphrase;
  }

  /** Credentials for password-based authentication. */
  public static SshCredentials password(String password) {
    return new SshCredentials(password, null);
  }

  /** Credentials for key-based authentication with a passphrase-protected private key. */
  public static SshCredentials keyWithPassphrase(String passphrase) {
    return new SshCredentials(null, passphrase);
  }

  /** Credentials for key-based authentication with an unprotected private key. */
  public static SshCredentials keyNoPassphrase() {
    return new SshCredentials(null, null);
  }

  public String getPassword() {
    return password;
  }

  public String getKeyPassphrase() {
    return keyPassphrase;
  }

  /** Returns {@code true} if the private key requires a passphrase. */
  public boolean hasPassphrase() {
    return keyPassphrase != null && !keyPassphrase.isEmpty();
  }
}
