package dev.dispatch.ui.filemanager;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Modal dialog that prompts the user for a sudo password when the file manager
 * encounters a permission-denied error while navigating a remote directory.
 *
 * <p>Returns the entered password, or {@code null} if the user cancelled.
 * Must be shown on the FX Application Thread.
 */
public class SudoPasswordDialog extends Dialog<String> {

  /**
   * Creates the dialog.
   *
   * @param deniedPath the remote path that could not be accessed
   * @param owner      the owning window for modal positioning (may be {@code null})
   */
  public SudoPasswordDialog(String deniedPath, Window owner) {
    if (owner != null) initOwner(owner);

    setTitle("Wymagany dostęp root");

    Label info = new Label("Brak uprawnień do katalogu:\n" + deniedPath
        + "\n\nPodaj hasło sudo, aby kontynuować jako root.");
    info.setWrapText(true);
    info.getStyleClass().add("sudo-dialog-info");

    PasswordField passwordField = new PasswordField();
    passwordField.setPromptText("Hasło sudo…");
    passwordField.getStyleClass().add("sudo-dialog-password");

    VBox content = new VBox(10, info, passwordField);
    content.setPadding(new Insets(4, 0, 0, 0));

    getDialogPane().setContent(content);
    getDialogPane().getStyleClass().add("sudo-dialog");

    ButtonType continueBtn = new ButtonType("Kontynuuj", ButtonBar.ButtonData.OK_DONE);
    getDialogPane().getButtonTypes().addAll(continueBtn, ButtonType.CANCEL);

    // Load application stylesheet so the dialog inherits the dark theme.
    getDialogPane().getStylesheets().add(
        getClass().getResource("/css/dispatch-dark.css").toExternalForm());

    setResultConverter(btn -> btn == continueBtn ? passwordField.getText() : null);

    // Focus the password field on first render.
    Platform.runLater(passwordField::requestFocus);
  }
}
