package dev.dispatch.ssh;

/**
 * Lifecycle states of a single SSH session.
 *
 * <pre>
 * DISCONNECTED → CONNECTING → CONNECTED → DISCONNECTING → DISCONNECTED
 *                                 ↓
 *                              LOST (unexpected drop)
 * </pre>
 */
public enum SessionState {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  DISCONNECTING,
  LOST
}
