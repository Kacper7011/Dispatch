package dev.dispatch.docker.model;

/**
 * Snapshot of a Docker network, as returned by {@code docker network ls}.
 *
 * <p>Immutable — construct directly via the public constructor.
 */
public class NetworkInfo {

  private final String id;
  private final String name;
  private final String driver;
  private final String scope;

  public NetworkInfo(String id, String name, String driver, String scope) {
    this.id = id != null ? id : "";
    this.name = name != null ? name : "";
    this.driver = driver != null ? driver : "";
    this.scope = scope != null ? scope : "";
  }

  /** Full network ID. */
  public String getId() {
    return id;
  }

  /** Short network ID — first 12 characters. */
  public String getShortId() {
    return id.length() > 12 ? id.substring(0, 12) : id;
  }

  /** Network name (e.g. {@code bridge}, {@code seafile_default}). */
  public String getName() {
    return name;
  }

  /** Network driver (e.g. {@code bridge}, {@code host}, {@code overlay}). */
  public String getDriver() {
    return driver;
  }

  /** Network scope: {@code local}, {@code global}, or {@code swarm}. */
  public String getScope() {
    return scope;
  }

  @Override
  public String toString() {
    return "NetworkInfo{name='" + name + "', driver='" + driver + "', scope='" + scope + "'}";
  }
}
