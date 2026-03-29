package dev.dispatch.ssh;

/** Thrown when an SSH operation fails — connection, authentication, or command execution. */
public class SshException extends RuntimeException {

  public SshException(String message) {
    super(message);
  }

  public SshException(String message, Throwable cause) {
    super(message, cause);
  }
}
