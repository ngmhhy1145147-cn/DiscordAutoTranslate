package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.RetainedNodeMarkers;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MessageBatchQueueTest {
    private final MessageLocation guildChannel = new MessageLocation("10", "20");
    private final List<List<String>> requests = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final MessageBatchQueue queue = new MessageBatchQueue((callback, delayMs) -> {
        assertEquals(120L, delayMs);
        timers.add(callback);
        return true;
    }, (location, messages, error) -> {
        assertNull(error);
        List<String> ordered = new ArrayList<>();
        for (Scheduler.BatchItem message : messages) ordered.add(message.text);
        requests.add(ordered);
    });

    @Test
    public void exactBudgetClosesOneOrderedBatchAndLaterMessagesNeverReuseIt() {
        Scheduler.BatchItem first = item("9223372036854775806", 40);
        Scheduler.BatchItem second = item("9223372036854775808", 35);
        Scheduler.BatchItem third = item("9223372036854775809", 25);
        Scheduler.BatchItem next = item("9223372036854775810", 35);
        offer(guildChannel, second);
        offer(guildChannel, second);
        offer(new MessageLocation("10", "20"), first);
        assertTrue(requests.isEmpty());
        offer(guildChannel, third);
        offer(guildChannel, next);
        timers.forEach(Runnable::run);
        assertEquals(List.of(List.of(first.text, second.text, third.text), List.of(next.text)), requests);
    }

    @Test
    public void overflowDoesNotSplitOrDiscardAnIndividualMessage() {
        Scheduler.BatchItem first = item("40", 70);
        Scheduler.BatchItem overflow = item("41", 35);
        Scheduler.BatchItem oversized = item("42", 130);
        offer(guildChannel, first);
        offer(guildChannel, overflow);
        assertEquals(List.of(List.of(first.text)), requests);
        offer(guildChannel, oversized);
        timers.forEach(Runnable::run);
        assertEquals(List.of(List.of(first.text), List.of(overflow.text), List.of(oversized.text)), requests);
    }

    @Test
    public void timersSeparateGuildsAndDirectChannelsAndClearingCancelsOldWindows() {
        Scheduler.BatchItem first = item("40", 50);
        Scheduler.BatchItem second = item("41", 50);
        Scheduler.BatchItem third = item("42", 50);
        Scheduler.BatchItem fourth = item("43", 50);
        MessageLocation dm = new MessageLocation(null, "20");
        offer(dm, first);
        offer(guildChannel, second);
        offer(new MessageLocation(null, "21"), third);
        assertTrue(requests.isEmpty());
        offer(new MessageLocation(null, "20"), fourth);
        assertEquals(List.of(List.of(first.text, fourth.text)), requests);
        assertEquals(Arrays.asList(second, third), queue.clear());
        timers.forEach(Runnable::run);
        assertEquals(1, requests.size());
    }

    @Test
    public void staleEditedRowDoesNotKeepTheOldBudgetOrJoinTheNewBatch() {
        Scheduler.BatchItem old = item("40", 80);
        offer(guildChannel, old);
        Scheduler.BatchItem replacement = item("40", 45);
        queue.offer(guildChannel, replacement, 45, 100, id -> !old.id.equals(id));
        Scheduler.BatchItem next = item("41", 55);
        offer(guildChannel, next);
        timers.forEach(Runnable::run);
        assertEquals(List.of(List.of(replacement.text, next.text)), requests);
    }

    @Test
    public void characterBudgetUsesUnicodeCodePointsWithoutWireMarkers() {
        assertEquals(4, RetainedNodeMarkers.sourceCharacters("<x1>中😀\na</x1><keep1/>"));
    }

    @Test
    public void timerAdmissionFailureDiscardsWindowThroughTheNormalResultPath() {
        List<Throwable> failures = new ArrayList<>();
        MessageBatchQueue unavailable = new MessageBatchQueue((timer, delayMs) -> false,
                (location, batch, error) -> {
                    assertEquals(1, batch.size());
                    failures.add(error);
                });
        unavailable.offer(guildChannel, item("40", 40), 40, 100, id -> true);
        assertEquals(1, failures.size());
        assertNotNull(failures.get(0));
        assertTrue(unavailable.clear().isEmpty());
    }

    private void offer(MessageLocation location, Scheduler.BatchItem item) {
        queue.offer(location, item, item.text.length(), 100, id -> true);
    }

    private static Scheduler.BatchItem item(String id, int length) {
        String text = id.substring(id.length() - 1).repeat(length);
        return new Scheduler.BatchItem(TranslationTaskId.create(id, text, 1), text);
    }
}
