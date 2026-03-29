package dev.dispatch.ui.host;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import dev.dispatch.storage.HostRepository;
import java.io.File;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Form controller for creating and editing SSH host profiles. */
public class HostFormController {

  private static final Logger log = LoggerFactory.getLogger(HostFormController.class);
  private static final int DEFAULT_PORT = 22;

  @FXML private TextField nameField;
  @FXML private TextField hostnameField;
  @FXML private TextField portField;
  @FXML private TextField usernameField;
  @FXML private RadioButton passwordRadio;
  @FXML private RadioButton keyRadio;
  @FXML private ToggleGroup authGroup;
  @FXML private TextField keyPathField;
  @FXML private HBox keyPathRow;
  @FXML private Label keyPathLabel;
  @FXML private Label errorLabel;

  private HostRepository hostRepository;
  private Stage stage;
  private Host editingHost;

  /**
   * Prepares the form for creating a new host (editingHost == null) or editing an existing one.
   */
  public void init(HostRepository hostRepository, Stage stage, Host editingHost) {
    this.hostRepository = hostRepository;
    this.stage = stage;
    this.editingHost = editingHost;
    portField.setText(String.valueOf(DEFAULT_PORT));
    updateKeyPathVisibility(false);
    if (editingHost != null) {
      populateFields(editingHost);
    }
  }

  // -------------------------------------------------------------------------
  // FXML handlers
  // -------------------------------------------------------------------------

  @FXML
  private void onAuthTypeChanged() {
    updateKeyPathVisibility(keyRadio.isSelected());
  }

  @FXML
  private void onBrowseKey() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Select SSH private key");
    chooser.setInitialDirectory(
        new File(System.getProperty("user.home") + "/.ssh"));
    File file = chooser.showOpenDialog(stage);
    if (file != null) {
      keyPathField.setText(file.getAbsolutePath());
    }
  }

  @FXML
  private void onSave() {
    String error = validate();
    if (error != null) {
      showError(error);
      return;
    }
    Thread.ofVirtual().start(this::persistHost);
  }

  @FXML
  private void onCancel() {
    stage.close();
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  private void populateFields(Host host) {
    nameField.setText(host.getName());
    hostnameField.setText(host.getHostname());
    portField.setText(String.valueOf(host.getPort()));
    usernameField.setText(host.getUsername());
    if (host.getAuthType() == AuthType.KEY) {
      keyRadio.setSelected(true);
      keyPathField.setText(host.getKeyPath() != null ? host.getKeyPath() : "");
      updateKeyPathVisibility(true);
    } else {
      passwordRadio.setSelected(true);
    }
  }

  private String validate() {
    if (nameField.getText().isBlank()) {
      return "Name is required.";
    }
    if (hostnameField.getText().isBlank()) {
      return "Hostname is required.";
    }
    if (!isValidPort(portField.getText())) {
      return "Port must be a number between 1 and 65535.";
    }
    if (usernameField.getText().isBlank()) {
      return "Username is required.";
    }
    if (keyRadio.isSelected() && keyPathField.getText().isBlank()) {
      return "Key path is required for SSH key authentication.";
    }
    return null;
  }

  private boolean isValidPort(String text) {
    try {
      int port = Integer.parseInt(text.trim());
      return port >= 1 && port <= 65535;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private void persistHost() {
    Host host = buildHost();
    if (editingHost == null) {
      hostRepository.save(host);
      log.info("New host created: {}", host.getName());
    } else {
      host.setId(editingHost.getId());
      host.setCreatedAt(editingHost.getCreatedAt());
      hostRepository.update(host);
      log.info("Host updated: {}", host.getName());
    }
    javafx.application.Platform.runLater(stage::close);
  }

  private Host buildHost() {
    AuthType authType = keyRadio.isSelected() ? AuthType.KEY : AuthType.PASSWORD;
    String keyPath = keyRadio.isSelected() ? keyPathField.getText().trim() : null;
    return new Host(
        nameField.getText().trim(),
        hostnameField.getText().trim(),
        Integer.parseInt(portField.getText().trim()),
        usernameField.getText().trim(),
        authType,
        keyPath);
  }

  private void updateKeyPathVisibility(boolean visible) {
    keyPathRow.setVisible(visible);
    keyPathRow.setManaged(visible);
    keyPathLabel.setVisible(visible);
    keyPathLabel.setManaged(visible);
  }

  private void showError(String message) {
    errorLabel.setText(message);
    errorLabel.setVisible(true);
    errorLabel.setManaged(true);
  }
}
