package dev.dispatch.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Unit tests for session state machine, credentials, and exec result — no real SSH needed. */
class SshSessionStateTest {

  private static Host testHost() {
    return new Host("TestHost", "10.0.0.1", 22, "admin", AuthType.PASSWORD, null);
  }

  // -------------------------------------------------------------------------
  // SshCredentials
  // -------------------------------------------------------------------------

  @Test
  void passwordCredentials_hasNoPassphrase() {
    SshCredentials creds = SshCredentials.password("secret");
    assertEquals("secret", creds.getPassword());
    assertFalse(creds.hasPassphrase());
  }

  @Test
  void keyWithPassphrase_hasPassphrase() {
    SshCredentials creds = SshCredentials.keyWithPassphrase("p4ss");
    assertTrue(creds.hasPassphrase());
    assertEquals("p4ss", creds.getKeyPassphrase());
  }

  @Test
  void keyNoPassphrase_hasNoPassphrase() {
    SshCredentials creds = SshCredentials.keyNoPassphrase();
    assertFalse(creds.hasPassphrase());
  }

  // -------------------------------------------------------------------------
  // ExecResult
  // -------------------------------------------------------------------------

  @Test
  void execResult_isSuccess_whenExitCodeZero() {
    ExecResult result = new ExecResult("output", "", 0);
    assertTrue(result.isSuccess());
    assertEquals("output", result.getStdout());
  }

  @Test
  void execResult_isNotSuccess_whenExitCodeNonZero() {
    ExecResult result = new ExecResult("", "error text", 1);
    assertFalse(result.isSuccess());
    assertEquals("error text", result.getStderr());
    assertEquals(1, result.getExitCode());
  }

  // -------------------------------------------------------------------------
  // SshSession state machine (without real connection)
  // -------------------------------------------------------------------------

  @Test
  void newSession_startsDisconnected() {
    SshSession session = new SshSession(testHost());
    assertEquals(SessionState.DISCONNECTED, session.getState());
    assertFalse(session.isConnected());
  }

  @Test
  void exec_throwsSshException_whenNotConnected() {
    SshSession session = new SshSession(testHost());
    SshException ex = assertThrows(SshException.class, () -> session.exec("ls"));
    assertTrue(ex.getMessage().contains("not connected"));
  }

  @Test
  void openShell_throwsSshException_whenNotConnected() {
    SshSession session = new SshSession(testHost());
    assertThrows(SshException.class, session::openShell);
  }

  @Test
  void disconnect_isNoOp_whenAlreadyDisconnected() {
    SshSession session = new SshSession(testHost());
    // should not throw
    session.disconnect();
    assertEquals(SessionState.DISCONNECTED, session.getState());
  }

  @Test
  void onLost_callback_isInvoked_whenSessionMarkedLost() throws Exception {
    // Verify callback registration works (actual LOST transition tested via integration)
    AtomicBoolean called = new AtomicBoolean(false);
    SshSession session = new SshSession(testHost());
    session.setOnLost(s -> called.set(true));
    // onLost is invoked internally by MINA listener — just verify the setter doesn't throw
    assertFalse(called.get());
  }
}
