package dev.dispatch.core.model;

import java.time.LocalDateTime;

/** Represents a saved SSH host profile. */
public class Host {

  private long id;
  private String name;
  private String hostname;
  private int port;
  private String username;
  private AuthType authType;
  private String keyPath;
  private LocalDateTime createdAt;

  /** No-arg constructor for use by mappers. */
  public Host() {}

  /** Convenience constructor for creating a new (unsaved) host. */
  public Host(
      String name,
      String hostname,
      int port,
      String username,
      AuthType authType,
      String keyPath) {
    this.name = name;
    this.hostname = hostname;
    this.port = port;
    this.username = username;
    this.authType = authType;
    this.keyPath = keyPath;
    this.createdAt = LocalDateTime.now();
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getHostname() {
    return hostname;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public int getPort() {
    return port;
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public AuthType getAuthType() {
    return authType;
  }

  public void setAuthType(AuthType authType) {
    this.authType = authType;
  }

  public String getKeyPath() {
    return keyPath;
  }

  public void setKeyPath(String keyPath) {
    this.keyPath = keyPath;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  @Override
  public String toString() {
    return "Host{id=" + id + ", name='" + name + "', hostname='" + hostname + ":" + port + "'}";
  }
}
