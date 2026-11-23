package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.*;

import android.view.View;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Real Embed registration, state, cache, bridge and scheduler; no Android handler or network. */
public class EmbedCardStateTest {
    private static final String ROW = "123456789012345678";
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();
    private final BlockingQueue<Runnable> deliveries = new LinkedBlockingQueue<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private HookBridge.TranslationConfiguration previousConfiguration;

    @Before
    public void setup() throws Exception {
        HookRuntime.suspend();
        assertTrue(Scheduler.resetAndAwait(5000L));
        Scheduler.MainThread.clear();
        ContentNodeTestProfile.activate(ContentNodesTest.class, EmbedCardStateTest.class);
        bridge.reset();
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "223456789012345678"));
        store.onNewGeneration();
        TranslationCache.get().clear();
        RerenderRegistry.clear();
        RenderDispatcher.reset();
        previousConfiguration = bridge.translationConfiguration();
        Config.Snapshot testConfig = Config.read(new MemoryPrefs()
                .put(Config.KEY_ENABLED, true)
                .put(Config.KEY_API_URL, "https://example.invalid/unused")
                .put(Config.KEY_TARGET_LANG, "Simplified Chinese"));
        installEngine(testConfig, false);
        installQueuedDispatcher();
        HookRuntime.activate();
    }

    @After
    public void cleanup() throws Exception {
        try {
            assertTrue(HookRuntime.suspendAndAwait(5000L));
            assertTrue("All fake engine workers must terminate", Scheduler.resetAndAwait(5000L));
        } finally {
            Scheduler.MainThread.clear();
            RenderDispatcher.reset();
            RerenderRegistry.clear();
            bridge.reset();
            store.onNewGeneration();
            TranslationCache.get().clear();
            if (previousConfiguration != null) bridgeField("configuration").set(bridge, previousConfiguration);
            ContentNodeTestProfile.clear();
        }
    }

    private static Field bridgeField(String name) throws Exception {
        Field field = HookBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void installEngine(boolean fail) throws Exception {
        installEngine(bridge.translationConfiguration().snapshot, fail);
    }

    private void installEngine(Config.Snapshot config, boolean fail) throws Exception {
        bridgeField("configuration").set(bridge, new HookBridge.TranslationConfiguration(
                config, (text, precedingContext) -> {
            requests.add(text);
            if (fail) throw new IllegalStateException("fake translation failure");
            return "译文：" + text;
        }));
    }

    /** Use the existing package-private test dispatcher seam from the hooks test package. */
    private void installQueuedDispatcher() throws Exception {
        Class<?> dispatcher = Class.forName(Scheduler.MainThread.class.getName() + "$Dispatcher");
        Object queued = Proxy.newProxyInstance(dispatcher.getClassLoader(), new Class<?>[]{dispatcher},
                (proxy, method, args) -> {
                    if (!"post".equals(method.getName())) {
                        throw new AssertionError("Unexpected dispatcher call: " + method.getName());
                    }
                    deliveries.add((Runnable) args[0]);
                    return true;
                });
        Method install = Scheduler.MainThread.class.getDeclaredMethod("setDispatcherForTests", dispatcher);
        install.setAccessible(true);
        install.invoke(null, queued);
    }

    private void deliver(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            Runnable delivery = deliveries.poll(5L, TimeUnit.SECONDS);
            assertNotNull("Fake engine result was not posted", delivery);
            delivery.run();
        }
    }

    /** Native-like input that can observe the real producer's capture before it obtains state. */
    public static final class PrimitiveStructurableText {
        private final String content;
        Runnable onRead;

        PrimitiveStructurableText(String content) { this.content = content; }

        public String getContent() {
            if (onRead != null) onRead.run();
            return content;
        }
    }

    private static Object text(String value) {
        return new ContentNodesTest.PrimitiveStructurableText(value);
    }

    private static String hash(String key) {
        return key.substring(key.lastIndexOf('\u0000') + 1);
    }

    private void cache(String key, String translation) {
        TranslationCache.get().put(hash(key), translation);
    }

    public static void nativeBind() {}

    /** Bypass only model parsing/hook installation; exercise the actual registration and producer. */
    private String register(View view, List<Object> texts) throws Exception {
        Constructor<EmbedBinding> ctor = EmbedBinding.class.getDeclaredConstructor(String.class, List.class);
        ctor.setAccessible(true);
        EmbedBinding binding = ctor.newInstance(ROW, texts);
        return bridge.registerEmbed(binding, bridge.embedStateKeys(ROW, texts), view, null,
                EmbedCardStateTest.class.getMethod("nativeBind"), new Object[0]);
    }

    private void assertChoice(List<String> keys, boolean original) {
        for (String key : keys) {
            if (key != null) assertEquals(original, bridge.showingOriginal(key, store.peek(key)));
        }
    }

    @Test
    public void sharedModelInstanceUsesCurrentCardScopeInsteadOfLastGlobalIdentity() {
        Object shared = text("Shared English title");
        List<Object> first = Arrays.asList(shared, text("First card body"));
        List<Object> second = Arrays.asList(shared, text("Second card body"));
        List<String> firstKeys = bridge.embedStateKeys(ROW, first);
        List<String> secondKeys = bridge.embedStateKeys(ROW, second);
        bridge.rememberContent(secondKeys.get(0), shared);
        assertEquals(secondKeys.get(0), bridge.resolveMessageId(shared));
        try (InlineIconInjector.HostScope scope =
                     InlineIconInjector.enterCardHost(null, firstKeys.get(0), first, firstKeys)) {
            assertEquals(firstKeys.get(0), bridge.resolveMessageId(shared));
            assertNull(bridge.resolveMessageId(text("Unregistered source")));
            try (InlineIconInjector.HostScope nested = InlineIconInjector.enterCardHost(
                    null, secondKeys.get(0), second, secondKeys)) {
                assertEquals(secondKeys.get(0), bridge.resolveMessageId(shared));
            }
            assertEquals(firstKeys.get(0), bridge.resolveMessageId(shared));
        }
        assertEquals(secondKeys.get(0), bridge.resolveMessageId(shared));
    }

    @Test
    public void completeMembershipExistsBeforeFirstProducerCaptureOrCacheHit() throws Exception {
        PrimitiveStructurableText primaryText = new PrimitiveStructurableText("中文主段");
        List<Object> texts = Arrays.asList(primaryText, text("Cached English secondary"));
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        cache(keys.get(1), "缓存译文");
        List<List<String>> observedMemberships = new ArrayList<>();
        primaryText.onRead = () -> {
            CardState.Card card = CardState.forKey(keys.get(0));
            if (card != null && store.peek(keys.get(0)) == null) {
                observedMemberships.add(new ArrayList<>(card.keys));
            }
        };
        assertEquals(keys.get(0), register(new View(null), texts));
        assertFalse("Card membership must precede the first producer capture", observedMemberships.isEmpty());
        for (List<String> observed : observedMemberships) assertEquals(keys, observed);
        assertEquals(StateStore.Status.TRANSLATED, CardState.forKey(keys.get(0)).status());
        assertTrue(requests.isEmpty());
    }

    @Test
    public void idleChinesePrimaryUsesTranslatedSecondaryStatusAndOneSynchronizedControl() throws Exception {
        List<Object> texts = Arrays.asList(text("已经是中文标题"), text("English secondary description"));
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        cache(keys.get(1), "已翻译的说明");
        String primary = register(new View(null), texts);
        assertEquals(keys.get(0), primary);
        CardState.Card card = CardState.forKey(primary);
        assertNotNull(card);
        assertSame(card, CardState.forKey(keys.get(1)));
        assertEquals(keys, card.keys);
        StateStore.Entry title = store.peek(primary);
        StateStore.Entry description = store.peek(keys.get(1));
        assertEquals(StateStore.Status.IDLE, title.status);
        assertEquals(StateStore.Status.TRANSLATED, description.status);
        assertEquals(StateStore.Status.TRANSLATED, card.status());
        assertEquals("已翻译的说明", description.translatedText);
        title.showingOriginal = true;
        description.showingOriginal = true;
        assertChoice(keys, false); // Individual entry choices cannot override the card.
        try (InlineIconInjector.HostScope scope = InlineIconInjector.enterHost(null, primary)) {
            assertTrue(scope.allows(primary));
            assertFalse(scope.allows(keys.get(1)));
            scope.recordInjection(primary);
            assertFalse(scope.allows(primary));
        }
        bridge.onIconClicked(primary);
        assertChoice(keys, true);
        bridge.onIconClicked(primary);
        assertChoice(keys, false);
        assertEquals(StateStore.Status.IDLE, title.status);
        assertEquals(StateStore.Status.TRANSLATED, card.status());
        assertTrue(requests.isEmpty());
        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void sharedPrimaryInTwoSameRowEmbedsHasIndependentStateAndChoice() throws Exception {
        List<Object> firstTexts = Arrays.asList(text("共同的中文标题"), text("First English description"));
        List<Object> secondTexts = Arrays.asList(text("共同的中文标题"), text("Second English description"));
        List<String> first = bridge.embedStateKeys(ROW, firstTexts);
        List<String> second = bridge.embedStateKeys(ROW, secondTexts);
        cache(first.get(1), "第一张卡片");
        cache(second.get(1), "第二张卡片");
        register(new View(null), firstTexts);
        register(new View(null), secondTexts);
        assertNotEquals(first.get(0), second.get(0));
        assertEquals(hash(first.get(0)), hash(second.get(0)));
        assertNotEquals(HookBridge.cardIdentityOf(first.get(0)), HookBridge.cardIdentityOf(second.get(0)));
        CardState.Card firstCard = CardState.forKey(first.get(0));
        CardState.Card secondCard = CardState.forKey(second.get(0));
        assertNotNull(firstCard);
        assertNotNull(secondCard);
        assertNotSame(firstCard, secondCard);
        assertNotSame(store.peek(first.get(0)), store.peek(second.get(0)));
        bridge.onIconClicked(first.get(0));
        assertChoice(first, true);
        assertChoice(second, false);
        bridge.onIconClicked(second.get(0));
        bridge.onIconClicked(first.get(0));
        assertChoice(first, false);
        assertChoice(second, true);
        assertEquals("第一张卡片", store.peek(first.get(1)).translatedText);
        assertEquals("第二张卡片", store.peek(second.get(1)).translatedText);
        assertTrue(requests.isEmpty());
    }

    @Test
    public void duplicateSegmentsShareOneStateAndFirstNonemptyControl() throws Exception {
        List<Object> texts = Arrays.asList(null, text(" \n "), text("Repeated English text"),
                text("Repeated English text"), text("Another English segment"));
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        cache(keys.get(2), "重复文字");
        cache(keys.get(4), "其他段落");
        String primary = register(new View(null), texts);
        assertEquals(keys.get(2), primary);
        assertEquals(primary, keys.get(3));
        CardState.Card card = CardState.forKey(primary);
        assertEquals(Arrays.asList(primary, keys.get(4)), card.keys);
        assertSame(store.peek(primary), store.peek(keys.get(3)));
        try (InlineIconInjector.HostScope scope = InlineIconInjector.enterHost(null, primary)) {
            assertTrue(scope.allows(keys.get(2)));
            assertFalse(scope.allows(keys.get(4)));
            scope.recordInjection(keys.get(2));
            assertFalse(scope.allows(keys.get(3)));
        }
        bridge.onIconClicked(keys.get(4));
        bridge.toggleShowingOriginal(keys.get(4));
        assertChoice(keys, false);
        bridge.onIconClicked(primary);
        assertChoice(keys, true);
        bridge.onIconClicked(keys.get(4));
        bridge.toggleShowingOriginal(keys.get(4));
        assertChoice(keys, true);
        bridge.onIconClicked(keys.get(3)); // The duplicate is the same single control key.
        assertChoice(keys, false);
        assertTrue(requests.isEmpty());
    }

    @Test
    public void rebindAndRealLateCompletionPreserveCardChoiceDespiteEntryReset() throws Exception {
        List<Object> texts = Arrays.asList(text("中文主段"), text("English late completion"));
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        View view = new View(null);
        String primary = register(view, texts);
        assertEquals(StateStore.Status.TRANSLATING, CardState.forKey(primary).status());
        bridge.onIconClicked(primary);
        assertChoice(keys, true);
        CardState.Card previous = CardState.forKey(primary);
        assertEquals(primary, register(view, texts));
        assertNotSame(previous, CardState.forKey(primary));
        assertChoice(keys, true);
        deliver(1);
        StateStore.Entry completed = store.peek(keys.get(1));
        assertEquals(StateStore.Status.TRANSLATED, completed.status);
        assertFalse(completed.showingOriginal);
        assertChoice(keys, true);
        assertEquals(StateStore.Status.TRANSLATED, CardState.forKey(primary).status());
        register(view, texts);
        assertChoice(keys, true);
        bridge.onIconClicked(primary);
        assertChoice(keys, false);
        assertEquals(Collections.singletonList("English late completion"), requests);
        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void switchingBackRetriesEveryCurrentFailedSecondaryThroughCanonicalSources() throws Exception {
        installEngine(true);
        Object firstSource = text("First English retry segment");
        Object secondSource = text("Second English retry segment");
        List<Object> texts = Arrays.asList(text("中文主段"), firstSource, secondSource, firstSource);
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        View view = new View(null);
        String primary = register(view, texts);
        CardState.Card card = CardState.forKey(primary);
        assertEquals(Arrays.asList(primary, keys.get(1), keys.get(2)), card.keys);
        assertEquals(StateStore.Status.TRANSLATING, card.status());
        deliver(2);
        assertEquals(StateStore.Status.IDLE, store.peek(primary).status);
        assertEquals(StateStore.Status.FAILED, card.status());
        for (int i : new int[]{1, 2}) {
            StateStore.Entry failed = store.peek(keys.get(i));
            assertEquals(StateStore.Status.FAILED, failed.status);
            StateStore.Source canonical = store.source(keys.get(i));
            assertNotNull(canonical);
            assertSame(texts.get(i), canonical.content);
            assertFalse(canonical.revisionKnown);
            bridge.onMessageBound(keys.get(i), texts.get(i)); // Native rebind never auto-retries.
            bridge.onIconClicked(keys.get(i)); // Neither secondary can control or retry the card.
            assertSame(failed, store.peek(keys.get(i)));
            assertEquals(StateStore.Status.FAILED, failed.status);
        }
        assertEquals(2, requests.size());
        bridge.onIconClicked(primary); // Original selection is not a retry.
        assertChoice(keys, true);
        register(view, texts);
        assertChoice(keys, true);
        assertEquals(2, requests.size());
        installEngine(false);
        bridge.onIconClicked(primary);
        assertChoice(keys, false);
        assertEquals(StateStore.Status.TRANSLATING, CardState.forKey(primary).status());
        for (int i : new int[]{1, 2}) {
            assertEquals(StateStore.Status.TRANSLATING, store.peek(keys.get(i)).status);
            assertSame(texts.get(i), store.source(keys.get(i)).content);
        }
        deliver(2);
        assertEquals(StateStore.Status.TRANSLATED, CardState.forKey(primary).status());
        assertEquals(StateStore.Status.IDLE, store.peek(primary).status);
        for (int i : new int[]{1, 2}) {
            String sourceText = i == 1 ? "First English retry segment" : "Second English retry segment";
            assertEquals(2, Collections.frequency(requests, sourceText));
            StateStore.Entry entry = store.peek(keys.get(i));
            assertEquals(StateStore.Status.TRANSLATED, entry.status);
            assertEquals("译文：" + sourceText, entry.translatedText);
            assertEquals(entry.translatedText, TranslationCache.get().get(entry.contentHash));
        }
        assertEquals(4, requests.size());
        assertChoice(keys, false);
        bridge.onIconClicked(primary);
        bridge.onIconClicked(primary);
        assertEquals(4, requests.size());
        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void retryUsesOnlyCurrentMembershipAndRejectsAMismatchedCanonicalSource() throws Exception {
        installEngine(true);
        List<Object> texts = Arrays.asList(text("中文主段"), text("Current English failure"),
                text("Mismatched English failure"), text("Removed English failure"));
        List<String> keys = bridge.embedStateKeys(ROW, texts);
        String primary = register(new View(null), texts);
        deliver(3);
        assertEquals(3, requests.size());
        CardState.bind(HookBridge.cardIdentityOf(primary), primary, keys.subList(0, 3));
        assertNull(CardState.forKey(keys.get(3)));
        bridge.rememberCanonicalContent(keys.get(2), text("Changed canonical content"));
        installEngine(false);
        bridge.onIconClicked(primary);
        bridge.onIconClicked(primary);
        assertEquals(StateStore.Status.TRANSLATING, store.peek(keys.get(1)).status);
        assertEquals(StateStore.Status.FAILED, store.peek(keys.get(2)).status);
        assertEquals(StateStore.Status.FAILED, store.peek(keys.get(3)).status);
        deliver(1);
        assertEquals(2, Collections.frequency(requests, "Current English failure"));
        assertEquals(1, Collections.frequency(requests, "Mismatched English failure"));
        assertEquals(1, Collections.frequency(requests, "Removed English failure"));
        assertEquals(4, requests.size());
        assertEquals(StateStore.Status.FAILED, CardState.forKey(primary).status());
        assertFalse(bridge.showingOriginal(keys.get(1), store.peek(keys.get(1))));
        assertTrue(bridge.showingOriginal(keys.get(3), store.peek(keys.get(3))));
        assertTrue(deliveries.isEmpty());
    }

    @Test
    public void absentCardAlwaysShowsOriginalRegardlessOfStoredEntryChoice() {
        List<String> keys = bridge.embedStateKeys(ROW, Arrays.asList(text("English primary"), text("secondary")));
        StateStore.Entry entry = store.obtain(keys.get(0), hash(keys.get(0)));
        assertTrue(store.useCached(entry, "译文"));
        assertFalse(entry.showingOriginal);
        assertTrue(bridge.showingOriginal(keys.get(0), entry));
        CardState.bind(HookBridge.cardIdentityOf(keys.get(0)), keys.get(0), keys);
        assertFalse(bridge.showingOriginal(keys.get(0), entry));
        CardState.clear();
        assertTrue(bridge.showingOriginal(keys.get(0), entry));
        bridge.onIconClicked(keys.get(0));
        assertTrue(bridge.showingOriginal(keys.get(0), entry));
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
        assertTrue(requests.isEmpty());
    }
}
