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
 * Displays live log output from a Docker container in a selectable text area.
 *
 * <p>Log lines are streamed via {@link DockerService#streamLogs(String)}, batched every {@value
 * FLUSH_INTERVAL_MS} ms on {@code Schedulers.io()}, then appended to a read-only {@link TextArea}
 * on the FX Application Thread. The subscription is disposed when the tab is closed.
 */
public class ContainerLogsController {

  private static final Logger log = LoggerFactory.getLogger(ContainerLogsController.class);

  /** Flush buffered log lines to the view at most this often. */
  private static final int FLUSH_INTERVAL_MS = 100;

  /** Maximum number of lines kept — oldest lines are trimmed from the top. */
  private static final int MAX_LINES = 5_000;

  private final DockerService dockerService;
  private final ContainerInfo container;

  /**
   * Mirrors the text in {@link #logView} line-by-line so we can calculate character offsets when
   * trimming the oldest lines without re-reading the TextArea content.
   */
  private final List<String> logLines = new ArrayList<>();

  private final TextArea logView = new TextArea();
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
    logView.setEditable(false);
    logView.setWrapText(true);
    logView.getStyleClass().add("logs-view");
  }

  private HBox buildToolbar() {
    Label titleLabel = new Label("logs  ›  " + container.getName());
    titleLabel.getStyleClass().add("logs-title");

    Button clearBtn = new Button("clear");
    clearBtn.getStyleClass().addAll("button", "button-logs");
    clearBtn.setOnAction(e -> clearLogs());

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

  private void clearLogs() {
    logLines.clear();
    logView.clear();
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
   * Appends a batch of raw log lines to the text area. Oldest lines beyond {@value MAX_LINES} are
   * trimmed from the top. When follow is active the view scrolls to the last line.
   */
  private void appendBatch(List<String> incoming) {
    List<String> lines = splitToLines(incoming);
    if (lines.isEmpty()) return;

    trimIfNeeded(lines.size());

    logLines.addAll(lines);
    String toAppend = String.join("\n", lines) + "\n";

    if (follow.get()) {
      // appendText moves the caret to the end, which scrolls the viewport down
      logView.appendText(toAppend);
    } else {
      // insertText at the end without moving the caret keeps the user's scroll position
      logView.insertText(logView.getLength(), toAppend);
    }
  }

  /**
   * Removes the oldest lines from both {@link #logLines} and the text area so that adding {@code
   * incomingCount} lines will not exceed {@value MAX_LINES}.
   */
  private void trimIfNeeded(int incomingCount) {
    int excess = logLines.size() + incomingCount - MAX_LINES;
    if (excess <= 0) return;

    int toRemove = Math.min(excess, logLines.size());
    int charsToDelete = 0;
    for (int i = 0; i < toRemove; i++) {
      charsToDelete += logLines.get(i).length() + 1; // +1 for the trailing \n
    }
    logView.deleteText(0, charsToDelete);
    logLines.subList(0, toRemove).clear();
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
}
