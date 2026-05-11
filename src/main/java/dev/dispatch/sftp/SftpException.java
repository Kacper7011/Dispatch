package dev.dispatch.sftp;

/** Unchecked exception thrown by {@link FileSession} implementations on I/O or protocol errors. */
public class SftpException extends RuntimeException {

  public SftpException(String message) {
    super(message);
  }

  public SftpException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Returns {@code true} when the root cause is an SFTP permission-denied error
   * (SSH_FX_PERMISSION_DENIED = 3). Allows the UI layer to detect access-denied without importing
   * JSch types.
   */
  public boolean isPermissionDenied() {
    Throwable cause = getCause();
    if (cause instanceof com.jcraft.jsch.SftpException sftp) {
      return sftp.id == 3;
    }
    return false;
  }
}
