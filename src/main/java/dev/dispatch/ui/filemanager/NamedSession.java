package dev.dispatch.ui.filemanager;

import dev.dispatch.sftp.FileSession;
import java.util.function.Supplier;

/**
 * A named factory for a {@link FileSession}, used to populate the session-switcher
 * menu in each file-manager panel.
 *
 * @param label   display name shown in the menu (e.g. {@code "Local"} or a hostname)
 * @param factory called each time the user picks this session; must open a fresh session
 */
public record NamedSession(String label, Supplier<FileSession> factory) {}
