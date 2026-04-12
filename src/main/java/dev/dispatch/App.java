package dev.dispatch;

import atlantafx.base.theme.NordDark;
import dev.dispatch.core.config.AppConfig;
import dev.dispatch.ssh.SshService;
import dev.dispatch.ssh.TunnelService;
import dev.dispatch.storage.DatabaseManager;
import dev.dispatch.ui.MainController;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
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

    // macOS: DECORATED lets the OS render native traffic-light controls automatically.
    // Windows/Linux: UNDECORATED removes the native title bar so our custom chrome takes over.
    // TRANSPARENT was avoided because it causes invisible windows on some Windows GPU drivers.
    boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
    stage.initStyle(isMac ? StageStyle.DECORATED : StageStyle.UNDECORATED);

    FXMLLoader loader = new FXMLLoader(getClass().getResource("/dev/dispatch/fxml/main.fxml"));
    Scene scene =
        new Scene(loader.load(), AppConfig.WINDOW_DEFAULT_WIDTH, AppConfig.WINDOW_DEFAULT_HEIGHT);
    scene.getStylesheets().add(getClass().getResource("/css/dispatch-dark.css").toExternalForm());

    if (isMac) {
      // Let macOS draw the window chrome; flatten our top corners so they meet the native title bar
      scene.getRoot().getStyleClass().add("mac-chrome");
    }
    // Windows/Linux: solid scene fill so the window is always visible regardless of GPU driver.
    // The .app-root CSS class handles border-radius and border for the visual chrome.

    stage.setScene(scene);
    loadIcon(stage, isMac);

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
   * Sets the application icon on all platforms.
   *
   * <p>Loads the source PNG once, then generates multiple pre-scaled variants so that the OS can
   * pick the sharpest match for each context (window chrome ~16 px, taskbar ~32–48 px, Alt+Tab
   * ~128 px). A single icon forces the OS to downscale with a low-quality algorithm, producing a
   * blurry or apparently small icon.
   *
   * <p>On macOS the stage icon list is ignored by the OS; the dock icon is set via the AWT {@link
   * Taskbar} API instead.
   */
  private void loadIcon(Stage stage, boolean isMac) {
    byte[] iconBytes = loadIconBytes();
    if (iconBytes == null) return;

    for (int size : new int[] {16, 32, 48, 128, 256, 512}) {
      Image scaled = scaledFxImage(iconBytes, size);
      if (scaled != null) stage.getIcons().add(scaled);
    }

    if (isMac) {
      try {
        BufferedImage awtIcon = ImageIO.read(new ByteArrayInputStream(iconBytes));
        Taskbar.getTaskbar().setIconImage(awtIcon);
      } catch (UnsupportedOperationException ignored) {
        // Taskbar.setIconImage not supported on this macOS JVM — no-op
      } catch (Exception e) {
        log.warn("Could not set macOS dock icon: {}", e.getMessage());
      }
    }
  }

  /** Reads {@code /img/dispatch.png} into a byte array for reuse across multiple scalings. */
  private byte[] loadIconBytes() {
    try (InputStream s = getClass().getResourceAsStream("/img/dispatch.png")) {
      if (s == null) {
        log.warn("Application icon not found at /img/dispatch.png");
        return null;
      }
      return s.readAllBytes();
    } catch (Exception e) {
      log.warn("Failed to read application icon: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Scales the source PNG bytes to a square of the requested size using bicubic interpolation and
   * re-encodes it as PNG bytes so that JavaFX can consume it without the {@code javafx.swing}
   * module bridge.
   */
  private Image scaledFxImage(byte[] pngBytes, int size) {
    try {
      BufferedImage src = ImageIO.read(new ByteArrayInputStream(pngBytes));
      BufferedImage dst = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = dst.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.drawImage(src, 0, 0, size, size, null);
      g.dispose();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(dst, "png", out);
      return new Image(new ByteArrayInputStream(out.toByteArray()));
    } catch (Exception e) {
      log.warn("Could not scale icon to {}px: {}", size, e.getMessage());
      return null;
    }
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
    // Native DirectWrite renderer on Windows — ClearType-quality text instead of gray anti-alias.
    // Must be set before JavaFX initialises the Prism rendering pipeline.
    System.setProperty("prism.lcdtext", "true");
    System.setProperty("prism.text", "native");
    launch(args);
  }
}
