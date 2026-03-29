package dev.dispatch;

import atlantafx.base.theme.NordDark;
import dev.dispatch.ssh.SshService;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.ui.MainController;
import java.io.IOException;
import java.io.InputStream;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX entry point. Creates application services and wires them into the main window. */
public class App extends Application {

  private static final Logger log = LoggerFactory.getLogger(App.class);

  private DatabaseManager dbManager;
  private SshService sshService;

  @Override
  public void start(Stage stage) throws IOException {
    loadFonts();
    Application.setUserAgentStylesheet(new NordDark().getUserAgentStylesheet());

    dbManager = new DatabaseManager();
    sshService = new SshService();

    FXMLLoader loader =
        new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/main.fxml"));
    Scene scene = new Scene(loader.load(), 1280, 800);
    scene.getStylesheets().add(
        getClass().getResource("/css/dispatch-dark.css").toExternalForm());

    MainController ctrl = loader.getController();
    ctrl.init(dbManager, sshService);

    stage.setTitle("Dispatch");
    stage.setScene(scene);
    stage.show();
    log.info("Dispatch started");
  }

  @Override
  public void stop() {
    log.info("Shutting down");
    if (sshService != null) sshService.close();
    if (dbManager != null) dbManager.close();
  }

  private void loadFonts() {
    // Place fonts in src/main/resources/fonts/JetBrainsMono/ — download from jetbrains.com/lp/mono
    loadFont("/fonts/JetBrainsMono/JetBrainsMono-Regular.ttf");
    loadFont("/fonts/JetBrainsMono/JetBrainsMono-Bold.ttf");
  }

  private void loadFont(String resourcePath) {
    InputStream stream = getClass().getResourceAsStream(resourcePath);
    if (stream == null) {
      log.warn("Font not found at {}, falling back to system monospace font", resourcePath);
      return;
    }
    Font.loadFont(stream, 13);
    log.debug("Font loaded: {}", resourcePath);
  }

  public static void main(String[] args) {
    launch(args);
  }
}
