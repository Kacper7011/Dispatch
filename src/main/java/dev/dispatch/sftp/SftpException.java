package dev.dispatch.sftp;

/** Unchecked exception thrown by {@link FileSession} implementations on I/O or protocol errors. */
public class SftpException extends RuntimeException {

  public SftpException(String message) {
    super(message);
  }

  public SftpException(String message, Throwable cause) {
    super(message, cause);
  }
}
