package dev.dispatch.docker;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.Image;
import dev.dispatch.docker.model.ContainerInfo;
import dev.dispatch.docker.model.ContainerStatus;
import dev.dispatch.docker.model.ImageInfo;
import dev.dispatch.docker.model.PortBinding;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps docker-java API model objects to Dispatch's own immutable model classes.
 *
 * <p>Centralises all null-safety and default-value handling so that {@link DockerService} stays
 * free of defensive boilerplate.
 */
class DockerMapper {

  private DockerMapper() {}

  /** Maps a docker-java {@link Container} to a {@link ContainerInfo}. */
  static ContainerInfo toContainerInfo(Container c) {
    return new ContainerInfo.Builder()
        .id(c.getId())
        .name(primaryName(c.getNames()))
        .image(c.getImage())
        .status(ContainerStatus.from(c.getState()))
        .statusText(c.getStatus())
        .ports(toPorts(c.getPorts()))
        .createdAt(c.getCreated() != null ? Instant.ofEpochSecond(c.getCreated()) : Instant.EPOCH)
        .build();
  }

  /** Maps a docker-java {@link Image} to an {@link ImageInfo}. */
  static ImageInfo toImageInfo(Image img) {
    List<String> tags = img.getRepoTags() != null ? Arrays.asList(img.getRepoTags()) : List.of();
    long size = img.getSize() != null ? img.getSize() : 0L;
    Instant created =
        img.getCreated() != null ? Instant.ofEpochSecond(img.getCreated()) : Instant.EPOCH;
    return new ImageInfo(img.getId(), tags, size, created);
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private static String primaryName(String[] names) {
    if (names == null || names.length == 0) return "";
    return names[0]; // ContainerInfo.Builder strips the leading "/"
  }

  private static List<PortBinding> toPorts(ContainerPort[] ports) {
    if (ports == null) return List.of();
    List<PortBinding> result = new ArrayList<>(ports.length);
    for (ContainerPort p : ports) {
      // publicPort is null for ports exposed but not published to the host
      if (p.getPublicPort() == null) continue;
      result.add(
          new PortBinding(
              p.getIp() != null ? p.getIp() : "",
              p.getPublicPort(),
              p.getPrivatePort() != null ? p.getPrivatePort() : 0,
              p.getType() != null ? p.getType() : "tcp"));
    }
    return result;
  }
}
