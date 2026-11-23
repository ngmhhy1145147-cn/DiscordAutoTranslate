package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MessageContextTest {
    private static final MessageLocation CHANNEL = new MessageLocation("10", "20");

    @Test
    public void precedingMessagesAreOrderedByUnsignedIdAndStayInTheSameGuildAndChannel() {
        MessageContext context = new MessageContext();
        context.capture("9223372036854775811", CHANNEL, "future", 100);
        context.capture("9223372036854775808", CHANNEL, "more recent", 100);
        context.capture("9223372036854775806", CHANNEL, "older", 100);
        context.capture("9223372036854775807", new MessageLocation("10", "21"),
                "another channel", 100);
        context.capture("9223372036854775805", new MessageLocation("11", "20"),
                "another guild", 100);
        assertEquals("", context.capture("9223372036854775804",
                new MessageLocation(null, "20"), "direct message", 100));

        assertEquals("older\nmore recent",
                context.capture("9223372036854775809", CHANNEL, "current", 100));
        assertEquals("older\nmore recent",
                context.capture("9223372036854775809", CHANNEL, "current rebound", 100));
    }

    @Test
    public void contextBudgetIncludesSeparatorsAndKeepsUnicodeSurrogatePairsIntact() {
        MessageContext context = new MessageContext();
        context.capture("40", CHANNEL, "abc😀中", 100);
        context.capture("41", CHANNEL, "译", 100);
        assertEquals("😀中\n译", context.capture("42", CHANNEL, "正文", 4));
    }

    @Test
    public void directMessageReferencesStayInTheirExactAllowedChannel() {
        MessageContext context = new MessageContext();
        MessageLocation direct = new MessageLocation(null, "20");
        context.capture("40", direct, "first direct message", 100);
        context.capture("41", new MessageLocation(null, "21"), "another direct channel", 100);
        context.capture("42", CHANNEL, "server channel with the same ID", 100);
        assertEquals("first direct message", context.capture("43", direct, "current", 100));
    }

    @Test
    public void editedTextReplacesPriorCaptureAndNonProseNeverBecomesReferenceText() {
        MessageContext context = new MessageContext();
        context.capture("40", CHANNEL, "old text", 100);
        assertEquals("", context.capture("40", CHANNEL,
                "<x1>new</x1> <keep1/> `secret-code` https://example.invalid <@123> words", 100));
        assertEquals("new words", context.capture("41", CHANNEL, "current", 100));
        context.forget("40");
        assertEquals("", context.capture("41", CHANNEL, "current", 100));
        context.clear();
        assertEquals("", context.capture("42", CHANNEL, "later", 100));
    }
}
