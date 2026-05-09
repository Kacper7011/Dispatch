package dev.dispatch.sftp;

/**
 * Receives progress notifications during a file transfer. Implementations must be thread-safe —
 * callbacks arrive from a virtual worker thread, not the FX Application Thread.
 */
public interface TransferMonitor {

  /** Called once before the first byte is read, with the total expected size. */
  void onStart(String filename, long totalBytes);

  /** Called repeatedly as bytes are transferred; {@code bytesTransferred} is cumulative. */
  void onProgress(long bytesTransferred);

  /** Called when all bytes have been successfully written to the destination. */
  void onComplete();

  /**
   * Polled by the transfer loop. Return {@code true} to abort the current transfer cleanly;
   * the partial destination file is left in place.
   */
  boolean isCancelled();

  /** No-op monitor useful for tests and one-shot transfers where progress display is not needed. */
  static TransferMonitor noop() {
    return new TransferMonitor() {
      @Override
      public void onStart(String filename, long totalBytes) {}

      @Override
      public void onProgress(long bytesTransferred) {}

      @Override
      public void onComplete() {}

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }
}
