package dev.dispatch.ssh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SshServiceTest {

  private SshService service;

  @BeforeEach
  void setUp() {
    service = new SshService();
  }

  @AfterEach
  void tearDown() {
    service.close();
  }

  @Test
  void getSession_returnsEmpty_whenNoSessionExists() {
    Optional<SshSession> session = service.getSession(999L);
    assertFalse(session.isPresent());
  }

  @Test
  void getAllSessions_returnsEmptyCollection_initially() {
    assertTrue(service.getAllSessions().isEmpty());
  }

  @Test
  void disconnect_isNoOp_whenNoSessionExists() {
    // should not throw
    service.disconnect(42L);
    assertTrue(service.getAllSessions().isEmpty());
  }

  @Test
  void close_isIdempotent_withNoActiveSessions() {
    // should not throw even with no sessions
    service.close();
  }
}
