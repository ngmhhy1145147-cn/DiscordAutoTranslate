package io.github.wxmyyds.discordautotranslate.hooks;

import io.github.wxmyyds.discordautotranslate.core.RetainedNodeMarkers;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** In-memory prose observed by the canonical body renderer, ordered by Discord message ID. */
final class MessageContext {
    private static final int MAX_MESSAGES = 128;
    private static final Pattern NON_PROSE = Pattern.compile(
            "```[\\s\\S]*?(?:```|\\z)|`[^`\\r\\n]*`|(?:https?://|www\\.)\\S+"
                    + "|<(?:@!?|@&|#)[0-9]+>|<t:[0-9]+(?::[tTdDfFR])?>"
                    + "|<a?:[A-Za-z0-9_]+:[0-9]+>|:[A-Za-z0-9_]+:",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Map<String, Message> observed = new LinkedHashMap<>();

    /**
     * Capture the preceding same-channel excerpts as they exist at this bind, then update the
     * current message. Out-of-order native binds and updates cannot put future text in a request.
     */
    synchronized String capture(String messageId, MessageLocation location, String source, int limit) {
        if (!TranslationScope.isValidId(messageId)) {
            observed.remove(messageId);
            return "";
        }

        long id = Long.parseUnsignedLong(messageId);
        List<Message> previous = new ArrayList<>();
        for (Message message : observed.values()) {
            if (Long.compareUnsigned(message.id, id) < 0
                    && Objects.equals(location.guildId, message.location.guildId)
                    && location.channelId.equals(message.location.channelId)) {
                previous.add(message);
            }
        }
        previous.sort((left, right) -> Long.compareUnsigned(right.id, left.id));
        Deque<String> excerpts = new ArrayDeque<>();
        int remaining = limit;
        for (Message message : previous) {
            if (!excerpts.isEmpty()) remaining--; // Newline separating two observed messages.
            if (remaining <= 0) break;
            String excerpt = lastCodePoints(message.text, remaining);
            excerpts.addFirst(excerpt);
            remaining -= excerpt.codePointCount(0, excerpt.length());
        }

        String unmarked = RetainedNodeMarkers.maskedForDetection(source);
        String prose = WHITESPACE.matcher(NON_PROSE.matcher(unmarked).replaceAll(" "))
                .replaceAll(" ").trim();
        observed.remove(messageId);
        if (prose.codePoints().anyMatch(Character::isLetter)) {
            observed.put(messageId, new Message(id, location, lastCodePoints(prose, limit)));
            if (observed.size() > MAX_MESSAGES) observed.remove(observed.keySet().iterator().next());
        }
        return String.join("\n", excerpts);
    }

    synchronized void forget(String messageId) {
        observed.remove(messageId);
    }

    synchronized void clear() {
        observed.clear();
    }

    private static String lastCodePoints(String text, int limit) {
        if (text.codePointCount(0, text.length()) <= limit) return text;
        return text.substring(text.offsetByCodePoints(text.length(), -limit)).trim();
    }

    private static final class Message {
        final long id;
        final MessageLocation location;
        final String text;

        Message(long id, MessageLocation location, String text) {
            this.id = id;
            this.location = location;
            this.text = text;
        }
    }
}
