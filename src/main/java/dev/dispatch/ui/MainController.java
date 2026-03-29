package dev.dispatch.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main window controller — scaffold placeholder, full layout added in the ui module. */
public class MainController {

  private static final Logger log = LoggerFactory.getLogger(MainController.class);

  @FXML private Label statusLabel;

  @FXML
  public void initialize() {
    log.debug("MainController initialized");
  }
}
