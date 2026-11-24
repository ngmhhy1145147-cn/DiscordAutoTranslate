package io.github.wxmyyds.discordautotranslate.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reserved, content-free occurrence anchors for retained native leaves. Never repairs markers. */
public final class RetainedNodeMarkers {
    private static final Pattern PREFIX = Pattern.compile("<\\s*/?\\s*keep", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT = Pattern.compile("<keep[1-9][0-9]*/>");
    private static final Pattern STYLE = Pattern.compile("</?x[1-9][0-9]*>");

    private RetainedNodeMarkers() { }

    /** Source text must not collide with the native-node anchor namespace. */
    public static boolean hasReservedPrefix(String text) {
        return text != null && PREFIX.matcher(text).find();
    }

    /** One syntax check shared by engine and reconstruction; null means malformed reserved syntax. */
    private static List<String> read(String text) {
        // Validate reserved syntax across styled chunks too, not only within individual text nodes.
        text = STYLE.matcher(text).replaceAll("");
        List<String> markers = new ArrayList<>();
        Matcher prefix = PREFIX.matcher(text);
        while (prefix.find()) {
            Matcher marker = EXACT.matcher(text);
            marker.region(prefix.start(), text.length());
            if (!marker.lookingAt()) return null;
            markers.add(marker.group());
        }
        return markers;
    }

    /** Requires exact syntax, multiplicity and occurrence order, including rejection of extra markers. */
    public static boolean matchesExpected(String translated, List<String> expected) {
        List<String> actual = read(translated);
        return actual != null && actual.equals(expected);
    }

    /** Analysis only: generated anchor characters are not natural-language text. */
    public static String withoutMarkers(String text) {
        return EXACT.matcher(text).replaceAll("");
    }

    /** Detection only: unwrap styles without splitting words; anchors keep adjacent URL/text apart. */
    public static String maskedForDetection(String text) {
        text = STYLE.matcher(text).replaceAll("");
        return EXACT.matcher(text).replaceAll(" ");
    }

    /** The batch budget counts source Unicode code points, excluding protocol-only markers. */
    public static int sourceCharacters(String text) {
        String visible = STYLE.matcher(withoutMarkers(text)).replaceAll("");
        return visible.codePointCount(0, visible.length());
    }

    public static boolean matchesSource(String translated, String source) {
        List<String> expected = read(source);
        return expected != null && matchesExpected(translated, expected);
    }
}
