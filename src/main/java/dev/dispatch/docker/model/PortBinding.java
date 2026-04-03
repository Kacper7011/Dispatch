package dev.dispatch.docker.model;

/**
 * A single port mapping exposed by a container (host port → container port).
 *
 * <p>Corresponds to one entry in the Docker API {@code Ports} array.
 */
public class PortBinding {

  private final String ip;
  private final int publicPort;
  private final int privatePort;
  private final String type;

  public PortBinding(String ip, int publicPort, int privatePort, String type) {
    this.ip = ip;
    this.publicPort = publicPort;
    this.privatePort = privatePort;
    this.type = type;
  }

  /** Host IP the port is bound to (e.g. {@code "0.0.0.0"} or {@code "::"}). */
  public String getIp() {
    return ip;
  }

  /** Port number on the host machine. */
  public int getPublicPort() {
    return publicPort;
  }

  /** Port number inside the container. */
  public int getPrivatePort() {
    return privatePort;
  }

  /** Protocol — {@code "tcp"} or {@code "udp"}. */
  public String getType() {
    return type;
  }

  @Override
  public String toString() {
    return ip + ":" + publicPort + "->" + privatePort + "/" + type;
  }
}
