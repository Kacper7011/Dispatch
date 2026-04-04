package dev.dispatch;

import atlantafx.base.theme.NordDark;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.TunnelService;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.ui.MainController;
import java.io.IOException;
import java.io.InputStream;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX entry point. Creates application services and wires them into the main window. */
public class App extends Application {

  private static final Logger log = LoggerFactory.getLogger(App.class);

  private DatabaseManager dbManager;
  private SshService sshService;
  private TunnelService tunnelService;

  @Override
  public void start(Stage stage) throws IOException {
    loadFonts();
    Application.setUserAgentStylesheet(new NordDark().getUserAgentStylesheet());

    dbManager = new DatabaseManager();
    sshService = new SshService();
    tunnelService = new TunnelService();

    // TRANSPARENT lets us draw our own rounded corners and border
    stage.initStyle(StageStyle.TRANSPARENT);

    FXMLLoader loader = new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/main.fxml"));
    Scene scene = new Scene(loader.load(), 1280, 800);
    scene.setFill(Color.TRANSPARENT);
    scene.getStylesheets().add(getClass().getResource("/css/dispatch-dark.css").toExternalForm());

    // Clip the root to a rounded rectangle so children don't bleed out at the corners
    applyRoundedClip((Region) scene.getRoot());

    stage.setScene(scene);

    MainController ctrl = loader.getController();
    ctrl.init(dbManager, sshService, tunnelService, stage);

    stage.setTitle("Dispatch");
    stage.show();
    log.info("Dispatch started");
  }

  @Override
  public void stop() {
    log.info("Shutting down");
    if (tunnelService != null) tunnelService.close();
    if (sshService != null) sshService.close();
    if (dbManager != null) dbManager.close();
  }

  /**
   * Binds a rounded-rectangle clip to the root pane so that all children are visually clipped to
   * the app's rounded corners. The clip tracks the root's size as the window is resized.
   */
  private void applyRoundedClip(Region root) {
    Rectangle clip = new Rectangle();
    clip.setArcWidth(12);
    clip.setArcHeight(12);
    clip.widthProperty().bind(root.widthProperty());
    clip.heightProperty().bind(root.heightProperty());
    root.setClip(clip);
  }

  private void loadFonts() {
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
