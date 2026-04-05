package dev.dispatch.ui.docker;

import dev.dispatch.docker.DockerService;
import dev.dispatch.docker.model.ContainerInfo;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Displays live log output from a Docker container as a colour-coded list, newest lines first.
 *
 * <p>Log lines are streamed via {@link DockerService#streamLogs(String)}, batched every {@value
 * FLUSH_INTERVAL_MS} ms on {@code Schedulers.io()}, then prepended to a virtualised {@link
 * ListView} on the FX Application Thread. The subscription is disposed when the tab is closed.
 */
public class ContainerLogsController {

  private static final Logger log = LoggerFactory.getLogger(ContainerLogsController.class);

  /** Flush buffered log lines to the list at most this often. */
  private static final int FLUSH_INTERVAL_MS = 100;

  /** Maximum number of lines kept — oldest lines (bottom of list) are trimmed first. */
  private static final int MAX_LINES = 5_000;

  private final DockerService dockerService;
  private final ContainerInfo container;
  private final ObservableList<String> logLines = FXCollections.observableArrayList();
  private final ListView<String> logView = new ListView<>(logLines);
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
    configureLogView();
    VBox root = new VBox(buildToolbar(), logView);
    VBox.setVgrow(logView, Priority.ALWAYS);
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

  private void configureLogView() {
    logView.getStyleClass().add("logs-list");
    logView.setCellFactory(lv -> new LogLineCell());
    // Allow cells to grow vertically when text wraps
    logView.setFixedCellSize(Region.USE_COMPUTED_SIZE);
  }

  private HBox buildToolbar() {
    Label titleLabel = new Label("logs  ›  " + container.getName());
    titleLabel.getStyleClass().add("logs-title");

    Button clearBtn = new Button("clear");
    clearBtn.getStyleClass().addAll("button", "button-logs");
    clearBtn.setOnAction(e -> logLines.clear());

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
            .buffer(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .filter(batch -> !batch.isEmpty())
            .subscribe(
                batch -> Platform.runLater(() -> appendBatch(batch)),
                err -> {
                  log.warn("Log stream error for {}: {}", container.getName(), err.getMessage());
                  Platform.runLater(
                      () -> appendBatch(List.of("[stream ended: " + err.getMessage() + "]")));
                },
                () -> Platform.runLater(() -> appendBatch(List.of("[stream ended]"))));
  }

  /**
   * Appends a batch of raw log lines to the bottom of the list. Oldest lines beyond {@value
   * MAX_LINES} are trimmed from the top. When follow is active the view scrolls to the last item.
   */
  private void appendBatch(List<String> incoming) {
    List<String> lines = splitToLines(incoming);
    if (logLines.size() + lines.size() > MAX_LINES) {
      int excess = logLines.size() + lines.size() - MAX_LINES;
      logLines.remove(0, Math.min(excess, logLines.size()));
    }
    logLines.addAll(lines);

    if (follow.get()) {
      logView.scrollTo(logLines.size() - 1);
    }
  }

  /**
   * Splits raw Docker frame strings (which may contain embedded newlines) into individual lines,
   * stripping blank entries.
   */
  private static List<String> splitToLines(List<String> raw) {
    List<String> result = new ArrayList<>();
    for (String chunk : raw) {
      String[] parts = chunk.split("\n", -1);
      for (String part : parts) {
        if (!part.isBlank()) result.add(part);
      }
    }
    return result;
  }

  // ── Log line cell ─────────────────────────────────────────────────────────────

  /** List cell that colours each line according to its log level keyword and wraps long lines. */
  private class LogLineCell extends ListCell<String> {

    private static final List<String> LEVEL_CLASSES =
        List.of("log-error", "log-warn", "log-debug", "log-info");

    private final Label label = new Label();

    LogLineCell() {
      label.setWrapText(true);
      label.setTextOverrun(OverrunStyle.CLIP);
      // Subtract scrollbar width so the label never triggers a horizontal scrollbar
      label.maxWidthProperty().bind(logView.widthProperty().subtract(32));
      setGraphic(label);
      setText(null);
    }

    @Override
    protected void updateItem(String line, boolean empty) {
      super.updateItem(line, empty);
      getStyleClass().removeAll(LEVEL_CLASSES);
      if (empty || line == null) {
        label.setText(null);
        return;
      }
      label.setText(line);
      getStyleClass().add(resolveLevelClass(line));
    }

    private static String resolveLevelClass(String line) {
      String upper = line.toUpperCase();
      if (upper.contains("ERROR") || upper.contains("FATAL") || upper.contains("EXCEPTION")) {
        return "log-error";
      }
      if (upper.contains("WARN")) {
        return "log-warn";
      }
      if (upper.contains("DEBUG") || upper.contains("TRACE")) {
        return "log-debug";
      }
      return "log-info";
    }
  }
}
