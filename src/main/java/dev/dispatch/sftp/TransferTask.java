package dev.dispatch.sftp;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a single file-copy or directory-copy operation between two {@link FileSession}
 * instances. Supports LOCAL↔REMOTE and REMOTE↔REMOTE transfers.
 *
 * <p>Obtain a cold {@link Observable} via {@link #start()} and subscribe on an I/O scheduler. The
 * observable emits {@link TransferProgress} snapshots and completes when all bytes have been
 * written, or errors if the transfer fails.
 */
public final class TransferTask {

  private static final Logger log = LoggerFactory.getLogger(TransferTask.class);
  private static final int PIPE_BUFFER = 256 * 1024;

  /** Snapshot of transfer state emitted to the progress subscriber. */
  public record TransferProgress(
      String filename, long totalBytes, long transferredBytes, Status status) {

    public enum Status {
      RUNNING,
      DONE,
      CANCELLED
    }

    /** Completion fraction in [0, 1]; returns 0 when total is unknown. */
    public double fraction() {
      return totalBytes > 0 ? (double) transferredBytes / totalBytes : 0;
    }
  }

  private final FileSession src;
  private final String srcPath;
  private final FileSession dest;
  private final String destPath;
  private final AtomicBoolean cancelled = new AtomicBoolean(false);

  /**
   * @param src source session (local or remote)
   * @param srcPath full path of the file or directory to copy
   * @param dest destination session (local or remote)
   * @param destPath full destination path (file name included)
   */
  public TransferTask(FileSession src, String srcPath, FileSession dest, String destPath) {
    this.src = src;
    this.srcPath = srcPath;
    this.dest = dest;
    this.destPath = destPath;
  }

  /**
   * Returns a cold Observable that executes the transfer when subscribed. Must be subscribed on an
   * I/O / virtual-thread scheduler — never on the FX thread.
   */
  public Observable<TransferProgress> start() {
    return Observable.create(
        emitter -> {
          try {
            if (src.isDirectory(srcPath)) {
              transferDirectory(emitter);
            } else {
              transferFile(srcPath, destPath, emitter);
            }
            if (!emitter.isDisposed()) {
              emitter.onComplete();
            }
          } catch (Exception e) {
            log.error("Transfer failed: {} → {}", srcPath, destPath, e);
            if (!emitter.isDisposed()) emitter.onError(e);
          }
        });
  }

  /** Requests cancellation of the running transfer at the next progress checkpoint. */
  public void cancel() {
    cancelled.set(true);
  }

  /** Returns {@code true} if {@link #cancel()} has been called. */
  public boolean isCancelled() {
    return cancelled.get();
  }

  private void transferFile(String from, String to, ObservableEmitter<TransferProgress> emitter)
      throws IOException {
    AtomicLong total = new AtomicLong(-1);
    AtomicLong transferred = new AtomicLong(0);
    String filename = nameOf(from);

    TransferMonitor monitor = buildMonitor(filename, total, transferred, emitter);

    PipedOutputStream pipeOut = new PipedOutputStream();
    PipedInputStream pipeIn = new PipedInputStream(pipeOut, PIPE_BUFFER);

    Throwable[] uploadError = {null};
    Thread uploader =
        Thread.ofVirtual()
            .start(
                () -> {
                  try (pipeIn) {
                    dest.upload(pipeIn, to, -1, TransferMonitor.noop());
                  } catch (IOException | SftpException e) {
                    uploadError[0] = e;
                  }
                });

    try (pipeOut) {
      src.download(from, pipeOut, monitor);
    }

    try {
      uploader.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SftpException("Transfer interrupted for " + to, e);
    }
    if (uploadError[0] != null) {
      throw new SftpException("Upload failed for " + to, uploadError[0]);
    }
  }

  private void transferDirectory(ObservableEmitter<TransferProgress> emitter) throws IOException {
    Set<String> visited = new HashSet<>();
    visited.add(src.realpath(srcPath));
    copyDir(srcPath, destPath, emitter, visited);
  }

  private void copyDir(
      String fromDir,
      String toDir,
      ObservableEmitter<TransferProgress> emitter,
      Set<String> visited)
      throws IOException {
    if (!dest.isDirectory(toDir)) {
      dest.mkdir(toDir);
    }
    for (FileEntry entry : src.list(fromDir)) {
      if (cancelled.get() || emitter.isDisposed()) return;
      if (entry.isParentLink() || entry.isSymlink()) continue;
      String childDest = toDir + "/" + entry.getName();
      if (entry.isDirectory()) {
        String realChild = src.realpath(entry.getPath());
        if (!visited.add(realChild)) {
          log.warn("Symlink cycle detected, skipping: {}", entry.getPath());
          continue;
        }
        copyDir(entry.getPath(), childDest, emitter, visited);
      } else {
        transferFile(entry.getPath(), childDest, emitter);
      }
    }
  }

  private TransferMonitor buildMonitor(
      String filename,
      AtomicLong total,
      AtomicLong transferred,
      ObservableEmitter<TransferProgress> emitter) {
    return new TransferMonitor() {
      @Override
      public void onStart(String name, long totalBytes) {
        total.set(totalBytes);
        emit(TransferProgress.Status.RUNNING);
      }

      @Override
      public void onProgress(long bytes) {
        transferred.set(bytes);
        emit(TransferProgress.Status.RUNNING);
      }

      @Override
      public void onComplete() {
        emit(TransferProgress.Status.DONE);
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }

      private void emit(TransferProgress.Status status) {
        if (!emitter.isDisposed()) {
          emitter.onNext(new TransferProgress(filename, total.get(), transferred.get(), status));
        }
      }
    };
  }

  private static String nameOf(String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 ? path.substring(slash + 1) : path;
  }
}
