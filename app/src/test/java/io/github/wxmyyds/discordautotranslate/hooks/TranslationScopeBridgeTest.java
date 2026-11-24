package io.github.wxmyyds.discordautotranslate.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.TextExtractor;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;
import io.github.wxmyyds.discordautotranslate.core.TranslationTaskId;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Real scope gate, result callback and config transitions; no network or worker submissions. */
public class TranslationScopeBridgeTest {
    private static final String ROW = "123456789012345678";
    private static final String OTHER_ROW = "123456789012345679";
    private final HookBridge bridge = HookBridge.get();
    private final StateStore store = StateStore.get();
    private HookBridge.TranslationConfiguration previousConfiguration;
    private HookConfiguration previousProfile;
    private boolean previousRuntimeActive;

    @Before
    public void setup() throws Exception {
        previousRuntimeActive = HookRuntime.isActive();
        assertTrue("Previous runtime calls must drain", HookRuntime.suspendAndAwait(5000L));
        previousProfile = HookConfiguration.activeConfiguration();
        previousConfiguration = bridge.translationConfiguration();
        assertTrue("Previous scheduler must terminate", Scheduler.resetAndAwait(5000L));
        Scheduler.MainThread.clear();
        RerenderRegistry.clear();
        bridge.reset();
        store.onNewGeneration();
        TranslationCache.get().clear();
        // updateConfig initializes Scheduler even when no translation is submitted.
        bridge.updateConfig(snapshot(true, TranslationScope.ALL));
        ContentNodeTestProfile.activate(TranslationScopeBridgeTest.class);
        HookRuntime.activate();
    }

    @After
    public void cleanup() throws Exception {
        try {
            assertTrue("Test runtime calls must drain", HookRuntime.suspendAndAwait(5000L));
            assertTrue("Config-created scheduler must terminate", Scheduler.resetAndAwait(5000L));
        } finally {
            Scheduler.MainThread.clear();
            RerenderRegistry.clear();
            bridge.reset();
            store.onNewGeneration();
            TranslationCache.get().clear();
            // Restore fields directly: updateConfig here would create another scheduler.
            bridgeField("configuration").set(bridge, previousConfiguration);
            HookConfiguration.activate(previousProfile);
            if (previousRuntimeActive) HookRuntime.activate();
        }
    }

    @Test
    public void bodyEmbedAndPollSlotsUseOnlyTheirContainingRowLocation() {
        Config.Snapshot config = snapshot(true, new TranslationScope(false,
                Collections.emptyList(), Collections.singletonList(rule("20", true))));
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "20"));
        for (String key : keys(ROW)) {
            assertEquals(ROW, HookBridge.cardRowOf(key));
            if (HookBridge.isCardKey(key)) {
                // An independently recorded segment location cannot override its row.
                bridge.rememberMessageLocation(key, new MessageLocation("11", "21"));
            }
            assertTrue(key, bridge.translationAllowed(key, config));
        }
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "21"));
        for (String key : keys(ROW)) {
            if (HookBridge.isCardKey(key)) {
                bridge.rememberMessageLocation(key, new MessageLocation("11", "20"));
            }
            assertFalse(key, bridge.translationAllowed(key, config));
        }
    }

    @Test
    public void disablingMessageTargetsGatesStandingBodyEmbedAndPollState() {
        Config.Snapshot disabled = Config.read(new MemoryPrefs()
                .put(Config.KEY_ENABLED, true).put(Config.KEY_TRANSLATE_MESSAGES, false));
        bridge.updateConfig(disabled);
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "20"));
        for (String key : keys(ROW)) {
            assertFalse(bridge.translationAllowed(key, disabled));
            assertFalse(bridge.scopeAllowsDisplay(key));
        }
    }

    @Test
    public void qualityCacheIdentityStaysStableOnRebindButIsIsolatedPerCanonicalMessage()
            throws Exception {
        Config.Snapshot contextual = Config.read(new MemoryPrefs()
                .put(Config.KEY_TARGET_LANG, "简体中文")
                .put(Config.KEY_CONTEXT_ENABLED, true));
        bridge.updateConfig(contextual);
        forbidRequests();
        MessageLocation location = new MessageLocation("10", "20");
        bridge.rememberMessageLocation(ROW, location);
        bridge.rememberMessageLocation(OTHER_ROW, location);
        PrimitiveStructurableText subject = new PrimitiveStructurableText("当前正文用于检测");
        bridge.onMessageBound(ROW, subject);
        bridge.onMessageBound(OTHER_ROW, subject);
        StateStore.Entry first = store.peek(OTHER_ROW);
        assertNotNull(first);
        assertNotEquals(store.peek(ROW).contentHash, first.contentHash);
        assertSame(first, bridge.matchingState(OTHER_ROW, TextExtractor.extract(subject)));

        bridge.onMessageBound(ROW, new PrimitiveStructurableText("早期内容已编辑"));
        bridge.onMessageBound(OTHER_ROW, subject);
        assertSame(first, store.peek(OTHER_ROW));
        assertSame(first, bridge.matchingState(OTHER_ROW, TextExtractor.extract(subject)));
    }

    @Test
    public void malformedBatchFailsAllRowsAndRetryAppliesAllValidResultsByMessage()
            throws Exception {
        Config.Snapshot quality = Config.read(new MemoryPrefs()
                .put(Config.KEY_CONTEXT_ENABLED, true)
                .put(Config.KEY_TARGET_LANG, "简体中文"));
        bridge.updateConfig(quality);
        forbidRequests();
        MessageLocation location = new MessageLocation(null, "20");
        Scheduler.BatchItem first = prepareQualityMember(ROW, location, "第一行正文");
        Scheduler.BatchItem second = prepareQualityMember(OTHER_ROW, location, "另一条正文");
        List<Scheduler.BatchItem> batch = List.of(first, second);
        Field constructor = StructurableTextFactory.class.getDeclaredField("sPrimitiveCtor");
        constructor.setAccessible(true);
        Object previous = constructor.get(null);
        java.lang.reflect.Constructor<?> primitive =
                PrimitiveStructurableText.class.getDeclaredConstructor(String.class);
        primitive.setAccessible(true);
        constructor.set(null, primitive);
        try {
            bridge.completeQualityBatch(location, batch, batch, true,
                    List.of("第一条译文", "无效锚点<keep17/>"), null);
            for (Scheduler.BatchItem item : batch) {
                StateStore.Entry entry = store.peek(item.id.messageId);
                assertEquals(StateStore.Status.FAILED, entry.status);
                assertNull(entry.translatedText);
                assertNull(TranslationCache.get().get(item.id.contentHash));
            }

            List<Scheduler.BatchItem> retry = new java.util.ArrayList<>();
            for (Scheduler.BatchItem item : batch) {
                StateStore.Entry entry = store.peek(item.id.messageId);
                assertTrue(store.prepareRetry(entry));
                retry.add(new Scheduler.BatchItem(store.begin(entry), item.text));
            }
            List<String> values = List.of("第一条译文", "第二条译文");
            bridge.completeQualityBatch(location, retry, retry, true, values, null);
            for (int i = 0; i < retry.size(); i++) {
                Scheduler.BatchItem item = retry.get(i);
                StateStore.Entry entry = store.peek(item.id.messageId);
                assertEquals(StateStore.Status.TRANSLATED, entry.status);
                assertEquals(values.get(i), entry.translatedText);
                assertEquals(values.get(i), TranslationCache.get().get(item.id.contentHash));
            }
        } finally {
            constructor.set(null, previous);
        }
    }

    @Test
    public void repliesFromObsoleteScopeNeverPublishAnyBatchMember() throws Exception {
        bridge.updateConfig(Config.read(new MemoryPrefs()
                .put(Config.KEY_CONTEXT_ENABLED, true)
                .put(Config.KEY_TARGET_LANG, "简体中文")));
        forbidRequests();
        MessageLocation location = new MessageLocation("10", "20");
        Scheduler.BatchItem first = prepareQualityMember(ROW, location, "第一行正文");
        Scheduler.BatchItem second = prepareQualityMember(OTHER_ROW, location, "另一条正文");
        List<Scheduler.BatchItem> batch = List.of(first, second);
        TranslationScope empty = new TranslationScope(false, Collections.emptyList(),
                Collections.emptyList());
        bridge.updateConfig(snapshot(true, empty));
        bridge.completeQualityBatch(location, batch, batch, true,
                List.of("第一条译文", "第二条译文"), null);
        for (Scheduler.BatchItem item : batch) {
            assertNull(store.peek(item.id.messageId));
            assertNull(TranslationCache.get().get(item.id.contentHash));
        }
    }

    private Scheduler.BatchItem prepareQualityMember(String id, MessageLocation location, String text) {
        bridge.rememberMessageLocation(id, location);
        // The source already matches the target script, so only the production hash/source binding runs.
        bridge.onMessageBound(id, new PrimitiveStructurableText(text));
        TranslationTaskId task = store.begin(store.peek(id));
        assertNotNull(task);
        return new Scheduler.BatchItem(task, text);
    }

    @Test
    public void channelThenGuildThenDefaultPrecedenceAppliesToEverySurface() {
        for (boolean defaultTranslate : new boolean[]{false, true}) {
            Config.Snapshot config = snapshot(true, new TranslationScope(defaultTranslate,
                    Arrays.asList(rule("10", true), rule("11", false)),
                    Arrays.asList(rule("20", false), rule("21", true))));
            assertLocationDecision(config, "10", "20", false);
            assertLocationDecision(config, "11", "21", true);
            assertLocationDecision(config, "10", "22", true);
            assertLocationDecision(config, "11", "22", false);
            assertLocationDecision(config, "12", "22", defaultTranslate);
        }
    }

    @Test
    public void directMessagesHaveNoGuildAndStillHonorChannelAndDefault() {
        for (boolean defaultTranslate : new boolean[]{false, true}) {
            Config.Snapshot config = snapshot(true, new TranslationScope(defaultTranslate,
                    Collections.singletonList(rule("10", !defaultTranslate)),
                    Collections.singletonList(rule("20", !defaultTranslate))));
            // A DM channel matching a server rule's ID must not become a guild match.
            assertLocationDecision(config, null, "10", defaultTranslate);
            assertLocationDecision(config, null, "20", !defaultTranslate);
            assertLocationDecision(config, null, "21", defaultTranslate);
        }
    }

    @Test
    public void globalOffAndInvalidConfigDenyEvenAnExplicitChannelAllow() {
        TranslationScope scope = new TranslationScope(false, Collections.emptyList(),
                Collections.singletonList(rule("20", true)));
        assertLocationDecision(snapshot(true, scope), null, "20", true);
        assertLocationDecision(snapshot(false, scope), null, "20", false);
        assertLocationDecision(Config.invalidSnapshot(), null, "20", false);
        Config.Snapshot malformed = Config.read(new MemoryPrefs()
                .put(Config.KEY_TRANSLATION_SCOPE, "{\"defaultTranslate\":true}"));
        assertNotNull(malformed.configError);
        assertLocationDecision(malformed, null, "20", false);
    }

    @Test
    public void unknownForgottenAndResetLocationsDenyWithoutDefaultAllowingThem() {
        Config.Snapshot config = snapshot(true, TranslationScope.ALL);
        for (String key : keys(ROW)) assertFalse(bridge.translationAllowed(key, config));
        assertFalse(bridge.translationAllowed(null, config));
        bridge.rememberMessageLocation(OTHER_ROW, new MessageLocation(null, "20"));
        for (String key : keys(ROW)) assertFalse(bridge.translationAllowed(key, config));
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        for (String key : keys(ROW)) assertTrue(bridge.translationAllowed(key, config));
        bridge.forgetMessageLocation(ROW);
        for (String key : keys(ROW)) assertFalse(bridge.translationAllowed(key, config));
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        bridge.reset();
        for (String key : keys(ROW)) assertFalse(bridge.translationAllowed(key, config));
    }

    @Test
    public void acceptedAsyncCallbackIsReadmittedAndNeverCachesADeniedResult() throws Exception {
        TranslationScope denyChannel = new TranslationScope(true, Collections.emptyList(),
                Collections.singletonList(rule("20", false)));
        Config.Snapshot[] denied = {
                snapshot(true, denyChannel), snapshot(false, TranslationScope.ALL),
                Config.invalidSnapshot()
        };
        for (Config.Snapshot config : denied) {
            // Change only the callback's current snapshot, preserving task ownership so that
            // this test isolates re-admission rather than generation invalidation.
            setFakeConfiguration(config, bridge.translationConfiguration().engine);
            bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
            for (String key : keys(ROW)) assertDeniedCallback(key);
        }
        setFakeConfiguration(snapshot(true, TranslationScope.ALL), bridge.translationConfiguration().engine);
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        bridge.forgetMessageLocation(ROW);
        for (String key : keys(ROW)) assertDeniedCallback(key);
        for (String key : keys(OTHER_ROW)) assertDeniedCallback(key);
    }

    @Test
    public void deniedProducerDoesNotCreateStateOrConsumeAnExistingApiCacheHit() throws Exception {
        Config.Snapshot[] deniedConfigs = {
                snapshot(true, new TranslationScope(false, Collections.emptyList(), Collections.emptyList())),
                snapshot(true, new TranslationScope(true, Collections.emptyList(),
                        Collections.singletonList(rule("20", false)))),
                snapshot(false, TranslationScope.ALL), Config.invalidSnapshot()
        };
        Object input = new PrimitiveStructurableText("English text with a cached translation");
        TextExtractor.Result extracted = TextExtractor.extract(input);
        assertNotNull("Fixture must be a real extractor input", extracted);
        for (Config.Snapshot config : deniedConfigs) {
            bridge.updateConfig(config);
            forbidRequests();
            bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
            String hash = Scheduler.contentHash(extracted.text, config.version());
            TranslationCache.get().put(hash, "cached translation");
            for (String key : keys(ROW)) {
                bridge.onMessageBound(key, input);
                assertNull(store.peek(key));
                assertNull(store.source(key));
            }
            assertEquals("cached translation", TranslationCache.get().get(hash));
        }
        Config.Snapshot allowing = snapshot(true, TranslationScope.ALL);
        bridge.updateConfig(allowing);
        forbidRequests();
        String hash = Scheduler.contentHash(extracted.text, allowing.version());
        TranslationCache.get().put(hash, "cached translation");
        bridge.forgetMessageLocation(ROW);
        for (String key : keys(ROW)) {
            bridge.onMessageBound(key, input);
            assertNull(store.peek(key));
        }
        for (String key : keys(OTHER_ROW)) {
            bridge.onMessageBound(key, input);
            assertNull(store.peek(key));
        }
        // Positive control through the identical producer and cache, without a request.
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        for (String key : keys(ROW)) {
            bridge.onMessageBound(key, input);
            assertEquals(StateStore.Status.TRANSLATED, store.peek(key).status);
            assertEquals("cached translation", store.peek(key).translatedText);
            assertSame(input, store.source(key).content);
        }
    }

    @Test
    public void deniedProducerDoesNotObtainANewHashOrReplaceRetainedStateAndSource() throws Exception {
        Config.Snapshot[] configs = {
                snapshot(false, TranslationScope.ALL),
                snapshot(true, new TranslationScope(true, Collections.emptyList(),
                        Collections.singletonList(rule("20", false)))),
                snapshot(true, TranslationScope.ALL)
        };
        Object retained = new PrimitiveStructurableText("Retained source");
        Object replacement = new PrimitiveStructurableText("Changed English source");
        assertNotNull(TextExtractor.extract(replacement));
        for (int i = 0; i < configs.length; i++) {
            bridge.updateConfig(configs[i]);
            forbidRequests();
            bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
            if (i == 2) bridge.forgetMessageLocation(ROW);
            for (String key : keys(ROW)) {
                StateStore.Entry entry = store.obtain(key, "retained-hash");
                assertTrue(store.useCached(entry, "retained translation"));
                store.rememberSource(key, new StateStore.Source(retained, true, "old-edit"));
                bridge.onMessageBound(key, replacement);
                assertSame(entry, store.peek(key));
                assertEquals("retained-hash", entry.contentHash);
                assertEquals(StateStore.Status.TRANSLATED, entry.status);
                assertEquals("retained translation", entry.translatedText);
                assertSame(retained, store.source(key).content);
                assertEquals("old-edit", store.source(key).editedTimestamp);
            }
        }
    }

    @Test
    public void displayGateUsesRowScopeButGlobalOffKeepsExistingTranslationsVisible() {
        Object input = new PrimitiveStructurableText("Previously translated English text");
        TextExtractor.Result extracted = TextExtractor.extract(input);
        assertNotNull(extracted);
        Config.Snapshot off = snapshot(false, TranslationScope.ALL);
        bridge.updateConfig(off);
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        String hash = Scheduler.contentHash(extracted.text, off.version());
        for (String key : keys(ROW)) {
            StateStore.Entry entry = store.obtain(key, hash);
            assertTrue(store.useCached(entry, "retained translation"));
            if (HookBridge.isCardKey(key)) {
                CardState.bind(HookBridge.cardIdentityOf(key), key, Collections.singletonList(key));
            }
            assertFalse(bridge.translationAllowed(key, off));
            assertTrue(bridge.scopeAllowsDisplay(key));
            assertFalse(bridge.showingOriginal(key, entry));
            assertSame(entry, bridge.currentTranslation(key, input).state);
            bridge.forgetMessageLocation(ROW);
            assertFalse(bridge.scopeAllowsDisplay(key));
            assertTrue(bridge.showingOriginal(key, entry));
            assertNull(bridge.currentTranslation(key, input));
            assertSame(entry, store.peek(key));
            bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        }
        Config.Snapshot scope = snapshot(true, new TranslationScope(false,
                Collections.singletonList(rule("10", true)),
                Collections.singletonList(rule("20", false))));
        bridge.updateConfig(scope);
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "20"));
        for (String key : keys(ROW)) assertFalse(bridge.scopeAllowsDisplay(key));
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "21"));
        for (String key : keys(ROW)) assertTrue(bridge.scopeAllowsDisplay(key));
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "21"));
        for (String key : keys(ROW)) assertFalse(bridge.scopeAllowsDisplay(key));
        bridge.updateConfig(Config.invalidSnapshot());
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        for (String key : keys(ROW)) assertFalse(bridge.scopeAllowsDisplay(key));
    }

    @Test
    public void schedulerRejectedResultDoesNotCacheEvenWhenScopeAllowsIt() {
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));
        for (String key : keys(ROW)) {
            String hash = "rejected:" + key;
            StateStore.Entry entry = store.obtain(key, hash);
            TranslationTaskId task = store.begin(entry);
            assertNotNull(task);
            bridge.onTranslateResult(task, false, "discarded translation", null);
            assertEquals(StateStore.Status.IDLE, entry.status);
            assertNull(entry.translatedText);
            assertNull(TranslationCache.get().get(hash));
            assertFalse(store.owns(task));
        }
    }

    @Test
    public void matchingPolicyChangesClearStateCardsCacheAndInFlightGeneration() {
        TranslationScope scope = new TranslationScope(true,
                Arrays.asList(rule("10", true), rule("11", false)),
                Arrays.asList(rule("20", true), rule("21", false)));
        TranslationScope[] changed = {
                new TranslationScope(false, scope.servers, scope.channels),
                new TranslationScope(true, Arrays.asList(rule("10", true), rule("12", false)), scope.channels),
                new TranslationScope(true, scope.servers, Arrays.asList(rule("20", true), rule("22", false))),
                new TranslationScope(true, Arrays.asList(rule("10", true), rule("11", true)), scope.channels),
                new TranslationScope(true, scope.servers, Arrays.asList(rule("20", true), rule("21", true))),
                new TranslationScope(true, Collections.emptyList(), scope.channels),
                new TranslationScope(true, scope.servers, Collections.emptyList())
        };
        for (TranslationScope after : changed) assertPolicyInvalidates(scope, after);
    }

    private void assertPolicyInvalidates(TranslationScope scope, TranslationScope changedScope) {
        Config.Snapshot before = snapshot(true, scope);
        // The row remains allowed, proving the obsolete callback is rejected by ownership,
        // not merely by the new policy's gate.
        Config.Snapshot after = snapshot(true, changedScope);
        assertNotEquals(before.translationScope, after.translationScope);
        assertEquals(before.version(), after.version());
        String hash = Scheduler.contentHash("Same API input", before.version());
        assertEquals(hash, Scheduler.contentHash("Same API input", after.version()));
        bridge.updateConfig(before);
        bridge.rememberMessageLocation(ROW, new MessageLocation(null, "20"));

        StateStore.Entry body = store.obtain(ROW, hash);
        TranslationTaskId obsolete = store.begin(body);
        assertNotNull(obsolete);
        store.rememberSource(ROW, new StateStore.Source(new Object(), true, "revision"));
        String embed = keys(ROW)[1];
        String poll = keys(ROW)[3];
        assertTrue(store.useCached(store.obtain(embed, "embed-hash"), "retained embed"));
        assertTrue(store.useCached(store.obtain(poll, "poll-hash"), "retained poll"));
        CardState.bind(HookBridge.cardIdentityOf(embed), embed, Collections.singletonList(embed));
        CardState.bind(HookBridge.cardIdentityOf(poll), poll, Collections.singletonList(poll));
        assertNotNull(CardState.forKey(embed));
        assertNotNull(CardState.forKey(poll));
        TranslationCache.get().put(hash, "retained API result");
        TranslationCache.get().put("embed-hash", "retained embed");
        int generation = store.generation();

        bridge.updateConfig(after);

        assertEquals(generation + 1, store.generation());
        assertFalse(store.owns(obsolete));
        assertNull(store.peek(ROW));
        assertNull(store.source(ROW));
        assertNull(store.peek(embed));
        assertNull(store.peek(poll));
        assertNull(CardState.forKey(embed));
        assertNull(CardState.forKey(poll));
        assertNull(TranslationCache.get().get(hash));
        assertNull(TranslationCache.get().get("embed-hash"));
        assertTrue(bridge.translationAllowed(ROW, after));

        StateStore.Entry current = store.obtain(ROW, hash);
        TranslationTaskId task = store.begin(current);
        assertNotNull(task);
        bridge.onTranslateResult(obsolete, true, "old generation", null);
        assertSame(current, store.peek(ROW));
        assertEquals(StateStore.Status.TRANSLATING, current.status);
        assertTrue(store.owns(task));
        assertNull(TranslationCache.get().get(hash));
        bridge.onTranslateResult(task, true, "current generation", null);
        assertEquals(StateStore.Status.TRANSLATED, current.status);
        assertEquals("current generation", TranslationCache.get().get(hash));
    }

    @Test
    public void equalImmutableScopeDoesNotInvalidateOwnedWorkOrRetainedState() {
        TranslationScope scope = new TranslationScope(false,
                Collections.singletonList(rule("10", true)), Collections.emptyList());
        assertPolicyRetains(scope, TranslationScope.parse(scope.toJson()));
    }

    @Test
    public void labelAndOrderChangesKeepStateCardsCacheAndTaskOwnership() {
        TranslationScope scope = new TranslationScope(false,
                Arrays.asList(rule("10", true), rule("11", false)),
                Arrays.asList(rule("20", true), rule("21", false)));
        TranslationScope renamed = new TranslationScope(false,
                Arrays.asList(new TranslationScope.Rule("10", "renamed server", true), rule("11", false)),
                Arrays.asList(new TranslationScope.Rule("20", "renamed channel", true), rule("21", false)));
        TranslationScope reordered = new TranslationScope(false,
                Arrays.asList(rule("11", false), rule("10", true)),
                Arrays.asList(rule("21", false), rule("20", true)));
        assertNotEquals(scope, renamed);
        assertNotEquals(scope, reordered);
        assertPolicyRetains(scope, renamed);
        assertPolicyRetains(scope, reordered);
    }

    private void assertPolicyRetains(TranslationScope scope, TranslationScope updatedScope) {
        // Each display-only edit starts with independent retained/work fixtures.
        store.onNewGeneration();
        CardState.clear();
        TranslationCache.get().clear();
        Config.Snapshot before = snapshot(true, scope);
        Config.Snapshot updated = snapshot(true, updatedScope);
        bridge.updateConfig(before);
        bridge.rememberMessageLocation(ROW, new MessageLocation("10", "20"));
        StateStore.Entry entry = store.obtain(ROW, "hash");
        TranslationTaskId task = store.begin(entry);
        assertNotNull(task);
        Object source = new Object();
        store.rememberSource(ROW, new StateStore.Source(source, false, null));
        String cardKey = keys(ROW)[1];
        StateStore.Entry cardEntry = store.obtain(cardKey, "card-hash");
        assertTrue(store.useCached(cardEntry, "retained card translation"));
        CardState.Card card = CardState.bind(HookBridge.cardIdentityOf(cardKey), cardKey,
                Collections.singletonList(cardKey));
        TranslationCache.get().put("retained", "translation");
        int generation = store.generation();

        bridge.updateConfig(updated);

        assertEquals(before.version(), updated.version());
        assertEquals(generation, store.generation());
        assertSame(entry, store.peek(ROW));
        assertSame(source, store.source(ROW).content);
        assertSame(card, CardState.forKey(cardKey));
        assertSame(cardEntry, store.peek(cardKey));
        assertEquals(StateStore.Status.TRANSLATED, cardEntry.status);
        assertEquals("retained card translation", cardEntry.translatedText);
        assertTrue(store.owns(task));
        assertEquals("translation", TranslationCache.get().get("retained"));
        bridge.onTranslateResult(task, true, "completed after display-only edit", null);
        assertEquals(StateStore.Status.TRANSLATED, entry.status);
        assertEquals("completed after display-only edit", entry.translatedText);
        assertEquals("completed after display-only edit", TranslationCache.get().get("hash"));
    }

    /** A regression must fail locally in the fake engine, never contact an API. */
    private void forbidRequests() throws Exception {
        setFakeConfiguration(bridge.translationConfiguration().snapshot, text -> {
            throw new AssertionError("Scope/cache-only test unexpectedly submitted a request");
        });
    }

    private void setFakeConfiguration(Config.Snapshot snapshot, Scheduler.Engine engine)
            throws Exception {
        bridgeField("configuration").set(bridge,
                new HookBridge.TranslationConfiguration(snapshot, engine, texts -> {
                    throw new AssertionError("Scope/cache-only test unexpectedly submitted a batch");
                }));
    }

    private void assertDeniedCallback(String key) {
        String hash = "denied:" + key;
        StateStore.Entry entry = store.obtain(key, hash);
        TranslationTaskId task = store.begin(entry);
        assertNotNull(task);
        assertTrue(store.owns(task));
        bridge.onTranslateResult(task, true, "must not be cached", null);
        assertEquals(StateStore.Status.IDLE, entry.status);
        assertNull(entry.translatedText);
        assertNull(entry.lastError);
        assertNull(TranslationCache.get().get(hash));
        assertFalse(store.owns(task));
    }

    private void assertLocationDecision(Config.Snapshot config, String guild, String channel,
                                        boolean expected) {
        bridge.rememberMessageLocation(ROW, new MessageLocation(guild, channel));
        for (String key : keys(ROW)) assertEquals(key, expected, bridge.translationAllowed(key, config));
    }

    private static Config.Snapshot snapshot(boolean enabled, TranslationScope scope) {
        Config.Snapshot snapshot = Config.read(new MemoryPrefs()
                .put(Config.KEY_ENABLED, enabled)
                .put(Config.KEY_TRANSLATION_SCOPE, scope.toJson()));
        assertNull(snapshot.configError);
        return snapshot;
    }

    private static TranslationScope.Rule rule(String id, boolean translate) {
        return new TranslationScope.Rule(id, "display-only label", translate);
    }

    /** Actual state-key namespaces; the gate must strip them rather than treating them as rows. */
    private static String[] keys(String row) {
        return new String[]{row,
                row + "\u0000embed\u0000card-hash\u0000title-hash",
                row + "\u0000embed\u0000card-hash\u0000description-hash",
                row + "\u0000poll\u0000question\u0000question-hash",
                row + "\u0000poll\u0000answer:1\u0000answer-hash"};
    }

    public static final class PrimitiveStructurableText {
        private final String content;

        PrimitiveStructurableText(String content) { this.content = content; }

        public String getContent() { return content; }
    }

    private static Field bridgeField(String name) throws Exception {
        Field field = HookBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
