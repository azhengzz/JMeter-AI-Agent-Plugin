package org.gitee.jmeter.ai.gui;

import javax.swing.JLabel;

/**
 * Safety helper for labels that display untrusted text (AI responses, tool
 * output). Swing's {@link JLabel} auto-renders a leading {@code <html>} tag
 * through its HTML kit - including {@code <img src="...">}, which can fetch
 * remote resources - so any label fed model output must force plain-text
 * rendering via the {@code html.disable} client property.
 */
public final class LabelUtils {

    private LabelUtils() {
    }

    /** A label that always renders its text literally, never as HTML. */
    public static JLabel plain(String text) {
        JLabel label = new JLabel(text);
        disableHtml(label);
        return label;
    }

    /** Forces plain-text rendering on an existing label. */
    public static void disableHtml(JLabel label) {
        label.putClientProperty("html.disable", Boolean.TRUE);
    }
}
