package dev.dispatch.ui;

import dev.dispatch.core.model.AuthType;
import dev.dispatch.core.model.Host;
import dev.dispatch.ssh.SshCredentials;
import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;

/**
 * Utility dialogs for collecting SSH credentials before connecting. Shows a password dialog for
 * PASSWORD auth and a passphrase dialog for KEY auth.
 */
public final class CredentialDialog {

  private CredentialDialog() {}

  /**
   * Shows the appropriate credential dialog for the given host's auth type.
   *
   * @return credentials if confirmed, empty if the user cancelled
   */
  public static Optional<SshCredentials> prompt(Host host) {
    if (host.getAuthType() == AuthType.PASSWORD) {
      return promptForPassword(host);
    } else {
      return promptForKeyPassphrase(host);
    }
  }

  private static Optional<SshCredentials> promptForPassword(Host host) {
    Dialog<SshCredentials> dialog =
        buildDialog(
            "Connect — " + host.getName(),
            "Password for " + host.getUsername() + "@" + host.getHostname());

    PasswordField field = new PasswordField();
    field.setPromptText("Password");
    setContent(dialog, field);

    dialog.setResultConverter(
        btn -> btn == ButtonType.OK ? SshCredentials.password(field.getText()) : null);

    return dialog.showAndWait();
  }

  private static Optional<SshCredentials> promptForKeyPassphrase(Host host) {
    Dialog<SshCredentials> dialog =
        buildDialog(
            "Connect — " + host.getName(),
            "Passphrase for key: "
                + host.getKeyPath()
                + "\n(leave blank if key has no passphrase)");

    PasswordField field = new PasswordField();
    field.setPromptText("Passphrase (optional)");
    setContent(dialog, field);

    dialog.setResultConverter(
        btn -> {
          if (btn != ButtonType.OK) return null;
          String passphrase = field.getText();
          return passphrase.isBlank()
              ? SshCredentials.keyNoPassphrase()
              : SshCredentials.keyWithPassphrase(passphrase);
        });

    return dialog.showAndWait();
  }

  private static Dialog<SshCredentials> buildDialog(String title, String header) {
    Dialog<SshCredentials> dialog = new Dialog<>();
    dialog.setTitle(title);
    dialog.setHeaderText(header);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    dialog
        .getDialogPane()
        .getStylesheets()
        .add(CredentialDialog.class.getResource("/css/dispatch-dark.css").toExternalForm());
    return dialog;
  }

  private static void setContent(Dialog<?> dialog, PasswordField field) {
    VBox box = new VBox(6, new Label(""), field);
    box.setPadding(new Insets(12, 24, 12, 24));
    dialog.getDialogPane().setContent(box);
    // Focus the field when the dialog opens
    dialog.setOnShown(e -> field.requestFocus());
  }
}
