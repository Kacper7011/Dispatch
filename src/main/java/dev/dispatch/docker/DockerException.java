package dev.dispatch.docker;

/** Thrown when a Docker operation fails — detection, container management, or API errors. */
public class DockerException extends RuntimeException {

  public DockerException(String message) {
    super(message);
  }

  public DockerException(String message, Throwable cause) {
    super(message, cause);
  }
}
