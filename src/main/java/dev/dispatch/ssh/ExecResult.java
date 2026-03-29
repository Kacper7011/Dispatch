package dev.dispatch.ssh;

/** Holds the result of a remote command executed over SSH. */
public class ExecResult {

  private final String stdout;
  private final String stderr;
  private final int exitCode;

  public ExecResult(String stdout, String stderr, int exitCode) {
    this.stdout = stdout;
    this.stderr = stderr;
    this.exitCode = exitCode;
  }

  /** Returns {@code true} if the remote command exited with code 0. */
  public boolean isSuccess() {
    return exitCode == 0;
  }

  public String getStdout() {
    return stdout;
  }

  public String getStderr() {
    return stderr;
  }

  public int getExitCode() {
    return exitCode;
  }

  @Override
  public String toString() {
    return "ExecResult{exitCode=" + exitCode + ", stdout='" + stdout.trim() + "'}";
  }
}
