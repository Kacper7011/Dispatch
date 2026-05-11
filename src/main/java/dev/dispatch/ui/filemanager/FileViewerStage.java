package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileEntry;
import dev.dispatch.sftp.FileSession;
import dev.dispatch.sftp.SftpException;
import dev.dispatch.sftp.TransferMonitor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Non-modal floating viewer and editor for remote/local files. Loads content via {@link
 * FileSession#download}, renders it in a RichTextFX {@link CodeArea} with per-language syntax
 * highlighting, and saves edits back via {@link FileSession#upload}.
 *
 * <p>Call {@link #show()} from the FX Application Thread; all blocking I/O runs on virtual threads.
 */
public final class FileViewerStage {

  private static final Logger log = LoggerFactory.getLogger(FileViewerStage.class);
  private static final long MAX_BYTES = 2L * 1024 * 1024;

  private final Stage stage;
  private final FileEntry entry;
  private final FileSession session;
  private final SyntaxHighlighter highlighter;

  private CodeArea codeArea;
  private Label statusLeft;
  private Label statusRight;
  private Label dirtyLabel;
  private Button saveBtn;

  private boolean loading = false;
  private boolean dirty = false;

  public FileViewerStage(FileEntry entry, FileSession session, Window owner) {
    this.entry = entry;
    this.session = session;
    this.highlighter = SyntaxHighlighter.forFile(entry.getName());

    stage = new Stage();
    if (owner != null) stage.initOwner(owner);
    stage.initModality(Modality.NONE);
    stage.setTitle(entry.getName() + " — " + session.displayName());
    stage.setWidth(880);
    stage.setHeight(580);
    stage.setResizable(true);

    stage.setScene(buildScene());
  }

  /** Shows the Stage and starts loading the file content asynchronously. */
  public void show() {
    stage.show();
    loadContent();
  }

  // ── Scene construction ────────────────────────────────────────────────────────

  private Scene buildScene() {
    // Toolbar
    Label nameLabel = new Label(entry.getName());
    nameLabel.getStyleClass().add("file-viewer-name");

    dirtyLabel = new Label("● MODIFIED");
    dirtyLabel.getStyleClass().add("file-viewer-dirty");
    dirtyLabel.setVisible(false);

    Label encLabel = new Label("UTF-8");
    encLabel.getStyleClass().add("file-viewer-enc");

    saveBtn = new Button("Save");
    saveBtn.getStyleClass().add("file-viewer-save");
    saveBtn.setOnAction(e -> saveContent());
    saveBtn.setDisable(true);

    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox toolbar = new HBox(8, nameLabel, spacer, dirtyLabel, encLabel, saveBtn);
    toolbar.getStyleClass().add("file-viewer-toolbar");
    toolbar.setAlignment(Pos.CENTER_LEFT);
    toolbar.setPadding(new Insets(6, 10, 6, 10));

    // Code area + scroll wrapper
    codeArea = new CodeArea();
    codeArea.getStyleClass().add("file-viewer-area");
    codeArea.setEditable(false);
    codeArea
        .textProperty()
        .addListener(
            (obs, o, n) -> {
              if (!loading) setDirty(true);
            });
    codeArea.caretPositionProperty().addListener((obs, o, n) -> updateCaret());

    @SuppressWarnings("unchecked")
    VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
    VBox.setVgrow(scroll, Priority.ALWAYS);

    // Status bar
    statusLeft = new Label("Ln 1, Col 1");
    statusLeft.getStyleClass().add("file-viewer-status-text");
    statusRight = new Label("Loading…");
    statusRight.getStyleClass().add("file-viewer-status-text");
    Region statusSpacer = new Region();
    HBox.setHgrow(statusSpacer, Priority.ALWAYS);
    HBox statusBar = new HBox(statusLeft, statusSpacer, statusRight);
    statusBar.getStyleClass().add("file-viewer-status");
    statusBar.setPadding(new Insets(3, 10, 3, 10));

    VBox root = new VBox(toolbar, scroll, statusBar);
    root.getStyleClass().add("file-viewer-root");

    Scene scene = new Scene(root);
    scene.getStylesheets().add(getClass().getResource("/css/dispatch-dark.css").toExternalForm());
    scene.getAccelerators().put(KeyCombination.keyCombination("Ctrl+S"), this::saveContent);

    // Async re-highlight on every text change (120 ms debounce, runs on VT)
    var sub =
        codeArea
            .multiPlainChanges()
            .successionEnds(Duration.ofMillis(120))
            .subscribe(ignored -> Thread.ofVirtual().start(this::rehighlight));

    stage.setOnCloseRequest(
        e -> {
          if (dirty) {
            e.consume();
            confirmClose();
          }
        });
    stage.setOnHidden(e -> sub.unsubscribe());

    return scene;
  }

  // ── Load / save ───────────────────────────────────────────────────────────────

  private void loadContent() {
    Thread.ofVirtual()
        .start(
            () -> {
              if (entry.getSize() > MAX_BYTES) {
                Platform.runLater(() -> statusRight.setText("File too large to display (> 2 MB)"));
                return;
              }
              try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                session.download(entry.getPath(), bos, TransferMonitor.noop());
                byte[] bytes = bos.toByteArray();
                if (isBinary(bytes)) {
                  Platform.runLater(() -> statusRight.setText("Binary file — cannot display"));
                  return;
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                StyleSpans<Collection<String>> spans = highlighter.highlight(text);
                Platform.runLater(() -> populate(text, spans));
              } catch (SftpException e) {
                log.error("Cannot read {}", entry.getPath(), e);
                Platform.runLater(() -> statusRight.setText("Error: " + e.getMessage()));
              }
            });
  }

  private void populate(String text, StyleSpans<Collection<String>> spans) {
    loading = true;
    codeArea.replaceText(text);
    if (!text.isEmpty()) codeArea.setStyleSpans(0, spans);
    loading = false;
    codeArea.setEditable(true);
    saveBtn.setDisable(false);
    statusRight.setText(entry.getPath());
    updateCaret();
  }

  private void rehighlight() {
    String text = codeArea.getText();
    if (text.isEmpty()) return;
    StyleSpans<Collection<String>> spans = highlighter.highlight(text);
    Platform.runLater(
        () -> {
          if (codeArea.getLength() == text.length()) codeArea.setStyleSpans(0, spans);
        });
  }

  private void saveContent() {
    String text = codeArea.getText();
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                session.upload(
                    new ByteArrayInputStream(bytes),
                    entry.getPath(),
                    bytes.length,
                    TransferMonitor.noop());
                Platform.runLater(() -> setDirty(false));
              } catch (SftpException e) {
                log.error("Cannot write {}", entry.getPath(), e);
                Platform.runLater(
                    () ->
                        new Alert(
                                Alert.AlertType.ERROR,
                                "Save failed: " + e.getMessage(),
                                ButtonType.OK)
                            .showAndWait());
              }
            });
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  private void setDirty(boolean d) {
    dirty = d;
    dirtyLabel.setVisible(d);
    stage.setTitle((d ? "● " : "") + entry.getName() + " — " + session.displayName());
  }

  private void updateCaret() {
    int ln = codeArea.getCurrentParagraph() + 1;
    int col = codeArea.getCaretColumn() + 1;
    statusLeft.setText("Ln " + ln + ", Col " + col);
  }

  private void confirmClose() {
    new Alert(
            Alert.AlertType.CONFIRMATION,
            "Unsaved changes in " + entry.getName() + ". Close anyway?",
            ButtonType.YES,
            ButtonType.NO)
        .showAndWait()
        .filter(b -> b == ButtonType.YES)
        .ifPresent(
            b -> {
              dirty = false;
              stage.close();
            });
  }

  private static boolean isBinary(byte[] bytes) {
    int check = Math.min(bytes.length, 8192);
    for (int i = 0; i < check; i++) {
      if (bytes[i] == 0) return true;
    }
    return false;
  }
}
