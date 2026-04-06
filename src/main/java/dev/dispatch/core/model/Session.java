package dev.dispatch.core.model;

import java.time.LocalDateTime;

/** Represents a single SSH connection session, persisted in the {@code sessions} table. */
public class Session {

  private long id;
  private long hostId;
  private LocalDateTime connectedAt;
  private LocalDateTime disconnectedAt;

  /** No-arg constructor for use by mappers. */
  public Session() {}

  /** Convenience constructor for a newly opened session (no disconnect time yet). */
  public Session(long hostId, LocalDateTime connectedAt) {
    this.hostId = hostId;
    this.connectedAt = connectedAt;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public long getHostId() {
    return hostId;
  }

  public void setHostId(long hostId) {
    this.hostId = hostId;
  }

  public LocalDateTime getConnectedAt() {
    return connectedAt;
  }

  public void setConnectedAt(LocalDateTime connectedAt) {
    this.connectedAt = connectedAt;
  }

  /** Returns the disconnect time, or {@code null} if the session is still active. */
  public LocalDateTime getDisconnectedAt() {
    return disconnectedAt;
  }

  public void setDisconnectedAt(LocalDateTime disconnectedAt) {
    this.disconnectedAt = disconnectedAt;
  }

  /** Returns true if this session has been closed (disconnectedAt is set). */
  public boolean isClosed() {
    return disconnectedAt != null;
  }

  @Override
  public String toString() {
    return "Session{id=" + id + ", hostId=" + hostId + ", connectedAt=" + connectedAt + "}";
  }
}
