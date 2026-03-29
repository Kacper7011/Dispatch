package dev.dispatch.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostRepositoryTest {

  private Path tempDb;
  private DatabaseManager db;
  private HostRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    tempDb = Files.createTempFile("dispatch-test-", ".db");
    db = new DatabaseManager(tempDb);
    repo = new HostRepository(db);
  }

  @AfterEach
  void tearDown() throws Exception {
    db.close();
    Files.deleteIfExists(tempDb);
  }

  @Test
  void save_setsGeneratedId() {
    Host host = new Host("Pi4", "192.168.1.100", 22, "pi", AuthType.KEY, "/home/user/.ssh/id_rsa");
    repo.save(host);

    assertTrue(host.getId() > 0, "id should be set after save");
  }

  @Test
  void findById_returnsCorrectHost() {
    Host host = new Host("Pi4", "192.168.1.100", 22, "pi", AuthType.KEY, "/home/user/.ssh/id_rsa");
    repo.save(host);

    Optional<Host> found = repo.findById(host.getId());
    assertTrue(found.isPresent());
    assertEquals("Pi4", found.get().getName());
    assertEquals("192.168.1.100", found.get().getHostname());
    assertEquals(22, found.get().getPort());
    assertEquals(AuthType.KEY, found.get().getAuthType());
  }

  @Test
  void findById_returnsEmpty_whenNotFound() {
    assertTrue(repo.findById(999L).isEmpty());
  }

  @Test
  void findAll_returnsAllHosts_inAlphabeticalOrder() {
    repo.save(new Host("Zebra", "10.0.0.1", 22, "admin", AuthType.PASSWORD, null));
    repo.save(new Host("Alpha", "10.0.0.2", 22, "root", AuthType.KEY, "/root/.ssh/id_ed25519"));

    List<Host> hosts = repo.findAll();
    assertEquals(2, hosts.size());
    assertEquals("Alpha", hosts.get(0).getName());
    assertEquals("Zebra", hosts.get(1).getName());
  }

  @Test
  void update_changesPersistedFields() {
    Host host = new Host("OldName", "10.0.0.1", 22, "user", AuthType.PASSWORD, null);
    repo.save(host);

    host.setName("NewName");
    host.setPort(2222);
    host.setAuthType(AuthType.KEY);
    host.setKeyPath("/home/user/.ssh/id_ed25519");
    repo.update(host);

    Host updated = repo.findById(host.getId()).orElseThrow();
    assertEquals("NewName", updated.getName());
    assertEquals(2222, updated.getPort());
    assertEquals(AuthType.KEY, updated.getAuthType());
    assertNotNull(updated.getKeyPath());
  }

  @Test
  void delete_removesHost() {
    Host host = new Host("ToDelete", "10.0.0.1", 22, "user", AuthType.PASSWORD, null);
    repo.save(host);

    repo.delete(host.getId());

    assertTrue(repo.findById(host.getId()).isEmpty());
    assertEquals(0, repo.findAll().size());
  }

  @Test
  void save_passwordHost_withNullKeyPath() {
    Host host = new Host("Server", "10.0.0.5", 22, "admin", AuthType.PASSWORD, null);
    repo.save(host);

    Host found = repo.findById(host.getId()).orElseThrow();
    assertEquals(AuthType.PASSWORD, found.getAuthType());
    assertTrue(found.getKeyPath() == null || found.getKeyPath().isEmpty());
  }
}
