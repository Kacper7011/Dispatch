package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileSession;
import java.util.function.Consumer;

/**
 * Async factory for a {@link FileSession}.
 *
 * <p>Called on the FX thread. May show dialogs synchronously, then connect on a virtual thread.
 * Must invoke {@code onReady} on the FX thread once the session is available. If the user cancels
 * (e.g., closes the credential dialog), {@code onReady} must NOT be called.
 */
@FunctionalInterface
public interface AsyncSessionFactory {
  void create(Consumer<FileSession> onReady);
}
