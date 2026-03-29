package dev.dispatch.storage;

/** Thrown when a storage operation fails. Wraps underlying SQL or IO exceptions. */
public class StorageException extends RuntimeException {

  public StorageException(String message) {
    super(message);
  }

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
