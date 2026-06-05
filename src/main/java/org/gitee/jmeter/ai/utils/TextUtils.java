package org.gitee.jmeter.ai.utils;

import java.util.regex.Pattern;

/**
 * Text processing utilities.
 */
public final class TextUtils {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think[\\s\\S]*?</think\\s*>");
    private static final Pattern THINK_UNCLOSED = Pattern.compile("<think[\\s\\S]*$");

    private TextUtils() {}

    /**
     * Remove &lt;think&gt;…&lt;/think&gt; blocks that some reasoning models
     * embed in their content, plus any unclosed trailing &lt;think&gt; tag.
     */
    public static String stripThink(String text) {
        if (text == null || text.isEmpty()) return text;
        text = THINK_BLOCK.matcher(text).replaceAll("");
        text = THINK_UNCLOSED.matcher(text).replaceAll("");
        return text.trim();
    }
}
