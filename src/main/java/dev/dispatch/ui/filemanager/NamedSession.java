package dev.dispatch.ui.filemanager;

/**
 * A named session option used to populate the session-switcher menu in each file-manager panel.
 *
 * @param label display name shown in the menu (e.g. {@code "Local"} or a hostname)
 * @param factory async factory; may show dialogs on the FX thread then connect on a VT
 */
public record NamedSession(String label, AsyncSessionFactory factory) {}
