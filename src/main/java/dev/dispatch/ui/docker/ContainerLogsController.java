package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays live log output from a Docker container in a scrollable, read-only text area.
 *
 * <p>Log lines are streamed via {@link DockerService#streamLogs(String)}, subscribed on {@code
 * Schedulers.io()}, and appended to the UI on the FX Application Thread. Auto-scroll ("follow") can
 * be toggled by the user; the subscription is disposed when the tab is closed.
 */
public class ContainerLogsController {

  private static final Logger log = LoggerFactory.getLogger(ContainerLogsController.class);

  private final DockerService dockerService;
  private final ContainerInfo container;
  private final TextArea logArea = new TextArea();
  private final BooleanProperty follow = new SimpleBooleanProperty(true);
  private Disposable subscription;

  /**
   * Creates a log viewer for the given container.
   *
   * @param dockerService connected Docker service
   * @param container container whose logs to stream
   */
  public ContainerLogsController(DockerService dockerService, ContainerInfo container) {
    this.dockerService = dockerService;
    this.container = container;
  }

  /**
   * Builds and returns the root node ready to be embedded in a Tab.
   *
   * <p>Also starts log streaming — call this only once per instance.
   */
  public VBox createNode() {
    configureLogArea();
    VBox root = new VBox(buildToolbar(), logArea);
    VBox.setVgrow(logArea, Priority.ALWAYS);
    root.getStyleClass().add("logs-root");
    startStreaming();
    return root;
  }

  /**
   * Stops log streaming and releases resources.
   *
   * <p>Must be called when the hosting Tab is closed.
   */
  public void dispose() {
    if (subscription != null && !subscription.isDisposed()) {
      subscription.dispose();
      log.debug("Log stream disposed for {}", container.getName());
    }
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private void configureLogArea() {
    logArea.setEditable(false);
    logArea.setWrapText(false);
    logArea.getStyleClass().add("logs-area");
  }

  private HBox buildToolbar() {
    Label titleLabel = new Label("logs  ›  " + container.getName());
    titleLabel.getStyleClass().add("logs-title");

    Button clearBtn = new Button("clear");
    clearBtn.getStyleClass().addAll("button", "button-logs");
    clearBtn.setOnAction(e -> logArea.clear());

    Button followBtn = new Button("follow ●");
    followBtn.getStyleClass().addAll("button", "button-logs");
    followBtn.setOnAction(e -> toggleFollow(followBtn));

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);

    HBox toolbar = new HBox(8, titleLabel, spacer, clearBtn, followBtn);
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.getStyleClass().add("logs-toolbar");
    toolbar.setPadding(new Insets(6, 10, 6, 14));
    return toolbar;
  }

  private void toggleFollow(Button followBtn) {
    follow.set(!follow.get());
    followBtn.setText(follow.get() ? "follow ●" : "follow ○");
  }

  private void startStreaming() {
    log.info("Starting log stream for {}", container.getName());
    subscription =
        dockerService
            .streamLogs(container.getId())
            .subscribeOn(Schedulers.io())
            .subscribe(
                line -> Platform.runLater(() -> appendLine(line)),
                err -> {
                  log.warn("Log stream error for {}: {}", container.getName(), err.getMessage());
                  Platform.runLater(() -> appendLine("\n[stream ended: " + err.getMessage() + "]"));
                },
                () -> Platform.runLater(() -> appendLine("\n[stream ended]")));
  }

  private void appendLine(String line) {
    logArea.appendText(line);
    if (follow.get()) {
      logArea.setScrollTop(Double.MAX_VALUE);
    }
  }
}
