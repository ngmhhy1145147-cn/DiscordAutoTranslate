package io.github.wxmyyds.discordautotranslate.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.os.Bundle;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.ConfigurationSync;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.PreferenceCommit;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/** Android stubs permit ownership/guard checks; widget lifecycle loading is tested via SettingsPersistence. */
public class SettingsActivityTest {
    private final List<String> logs = new ArrayList<>();

    @Before
    public void captureProjectLogs() {
        ProjectLog.configure(Config.read(null));
        // Test-only observation; the settings Activity never attaches a framework destination.
        ProjectLog.attach((priority, tag, message) -> logs.add(message));
    }

    @After
    public void releaseProjectLogs() {
        ProjectLog.configure(Config.read(null));
        ProjectLog.attach(null);
    }

    @Test
    public void pageCancellationCancelsBothTheQueuedOwnerAndItsFuture() throws Exception {
        SettingsActivity page = new SettingsActivity();
        ApiTester.Call call = ApiTester.newCall(Config.read(new MemoryPrefs()
                .put(Config.KEY_API_URL, "invalid")), ApiTester.TestKind.CONNECTION,
                ApiTester.DEFAULT_TEST_TEXT);
        FutureTask<ApiTester.Result> future = new FutureTask<>(call);
        set(page, "testCall", call);
        set(page, "testFuture", future);
        set(page, "testGeneration", 7L);

        invoke(page, "cancelPendingTest", new Class<?>[0]);

        assertTrue(future.isCancelled());
        assertEquals(Boolean.TRUE, get(call, "cancelled"));
        assertFalse(call.call().ok);
        assertNull(get(page, "testCall"));
        assertNull(get(page, "testFuture"));
        assertEquals(8L, get(page, "testGeneration"));
        invoke(page, "cancelPendingTest", new Class<?>[0]);
        assertEquals(9L, get(page, "testGeneration"));
    }

    @Test
    public void lateTestDeliveryCannotConsumeStoppedDestroyedOrReplacedPageWork() throws Exception {
        for (String reason : new String[]{"stopped", "destroyed", "generation", "task"}) {
            SettingsActivity page = new SettingsActivity();
            ApiTester.Call currentCall = ApiTester.newCall(Config.read(null),
                    ApiTester.TestKind.TRANSLATION, "hello");
            FutureTask<ApiTester.Result> currentTask = new FutureTask<>(currentCall);
            FutureTask<ApiTester.Result> oldTask = new FutureTask<>(
                    ApiTester.newCall(Config.read(null), ApiTester.TestKind.TRANSLATION, "old"));
            set(page, "started", !reason.equals("stopped"));
            set(page, "destroyed", reason.equals("destroyed"));
            set(page, "testGeneration", 2L);
            set(page, "testCall", currentCall);
            set(page, "testFuture", currentTask);

            invoke(page, "finishTest", new Class<?>[]{long.class, Future.class,
                            ApiTester.Result.class, Throwable.class},
                    reason.equals("generation") ? 1L : 2L,
                    reason.equals("task") ? oldTask : currentTask,
                    ApiTester.selfCheck(Config.read(null)), null);

            // Accepted delivery would clear ownership and touch absent stub widgets.
            assertSame(currentTask, get(page, "testFuture"));
            assertSame(currentCall, get(page, "testCall"));
        }
    }

    @Test
    public void scopeEditorGuardRejectsEveryLifecycleAndPersistenceBlockerWithoutConsumingRestore()
            throws Exception {
        for (String reason : new String[]{"stopped", "destroyed", "unloaded", "invalid",
                "loading", "dialog", "save", "sync"}) {
            SettingsActivity page = new SettingsActivity();
            set(page, "started", !reason.equals("stopped"));
            set(page, "destroyed", reason.equals("destroyed"));
            set(page, "configurationLoaded", !reason.equals("unloaded"));
            set(page, "persistedConfigInvalid", reason.equals("invalid"));
            set(page, "saveInProgress", reason.equals("save"));
            set(page, "syncInProgress", reason.equals("sync"));
            if (reason.equals("loading")) {
                Field loading = SettingsPersistence.class.getDeclaredField("loading");
                loading.setAccessible(true);
                loading.set(get(page, "persistence"), true);
            }
            Dialog currentDialog = reason.equals("dialog") ? new Dialog(page) : null;
            set(page, "scopeDialog", currentDialog);
            Bundle restore = new Bundle();
            set(page, "pendingScopeEditor", restore);

            // The button uses this same predicate. No stub widget rendering is asserted.
            assertEquals(reason, Boolean.FALSE, invoke(page, "canOpenScopeEditor", new Class<?>[0]));
            invoke(page, "openScopeEditor", new Class<?>[0]);
            assertSame(restore, get(page, "pendingScopeEditor"));
            assertSame(currentDialog, get(page, "scopeDialog"));
        }
    }

    @Test
    public void scopeEditorGuardAllowsReadyPageAndPendingLifecycleRestoration() throws Exception {
        SettingsActivity page = new SettingsActivity();
        set(page, "started", true);
        set(page, "configurationLoaded", true);
        assertEquals(Boolean.TRUE, invoke(page, "canOpenScopeEditor", new Class<?>[0]));
        Bundle restore = new Bundle();
        set(page, "pendingScopeEditor", restore);
        assertEquals(Boolean.TRUE, invoke(page, "canOpenScopeEditor", new Class<?>[0]));
        assertSame(restore, get(page, "pendingScopeEditor"));
    }

    @Test
    public void glossaryInspectionAndEditsRequireAReadyDraft() throws Exception {
        SettingsActivity page = new SettingsActivity();
        set(page, "started", true);
        set(page, "configurationLoaded", true);
        assertEquals(Boolean.TRUE, invoke(page, "canEditGlossary", new Class<?>[0]));

        for (String flag : new String[]{"destroyed", "persistedConfigInvalid",
                "saveInProgress", "syncInProgress", "importingGlossary"}) {
            set(page, flag, true);
            assertEquals(flag, Boolean.FALSE, invoke(page, "canEditGlossary", new Class<?>[0]));
            set(page, flag, false);
        }
    }

    @Test
    public void failedSaveCannotEnableDraftLoggingOrExposeExceptionContent() throws Exception {
        Config.Snapshot draft = loggingSnapshot(true);
        record("save", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.LOCAL_COMMIT_FAILED, false,
                new IllegalStateException("https://secret.invalid sk-secret response-body")), draft);
        assertTrue(logs.isEmpty());

        ProjectLog.configure(loggingSnapshot(true));
        record("save", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.LOCAL_COMMIT_FAILED, false,
                new IllegalStateException("https://secret.invalid sk-secret response-body")),
                loggingSnapshot(false));
        assertEquals(Arrays.asList("settings save end status=LOCAL_COMMIT_FAILED"
                + " direction=SAVE localCommitted=false exception=IllegalStateException"), logs);
    }

    @Test
    public void committedSaveEnablesLoggingBeforeItsResultAndRemoteFailureStaysDistinct()
            throws Exception {
        record("save", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.SUCCESS, true, null), loggingSnapshot(true));
        record("save", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.REMOTE_COMMIT_FAILED, true,
                new SecurityException("credential response https://secret.invalid")),
                loggingSnapshot(true));
        assertEquals(Arrays.asList(
                "settings save end status=SUCCESS direction=SAVE localCommitted=true",
                "settings save end status=REMOTE_COMMIT_FAILED direction=SAVE"
                        + " localCommitted=true exception=SecurityException"), logs);
    }

    @Test
    public void confirmedImportEnablesLogsButFailedImportDoesNotApplyRemoteDraft() throws Exception {
        record("sync", syncResult(ConfigurationSync.Direction.IMPORT,
                ConfigurationSync.Status.LOCAL_COMMIT_FAILED, false, null), loggingSnapshot(true));
        assertTrue(logs.isEmpty());
        record("sync", syncResult(ConfigurationSync.Direction.IMPORT,
                ConfigurationSync.Status.SUCCESS, true, null), loggingSnapshot(true));
        record("sync", syncResult(ConfigurationSync.Direction.EXPORT,
                ConfigurationSync.Status.REMOTE_COMMIT_FAILED, false, null), loggingSnapshot(false));
        assertEquals(Arrays.asList(
                "settings sync end status=SUCCESS direction=IMPORT localCommitted=true",
                "settings sync end status=REMOTE_COMMIT_FAILED direction=EXPORT"
                        + " localCommitted=false"), logs);
    }

    @Test
    public void onlyCommittedResetDisablesLoggingAndSuppressesItsSuccessOutput() throws Exception {
        ProjectLog.configure(loggingSnapshot(true));
        record("reset", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.LOCAL_COMMIT_FAILED, false,
                new IllegalArgumentException("secret reset failure")), Config.read(null));
        assertEquals(1, logs.size());
        assertEquals("settings reset end status=LOCAL_COMMIT_FAILED direction=SAVE"
                + " localCommitted=false exception=IllegalArgumentException", logs.get(0));
        record("reset", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.SUCCESS, true, null), Config.read(null));
        ProjectLog.info("after-reset");
        assertEquals(1, logs.size());
    }

    @Test
    public void unexpectedFutureFailureLogsOnlyItsTypeUnderTheSavedGate() throws Exception {
        Throwable failure = new AssertionError("sk-secret https://secret.invalid raw-body");
        invoke(null, "recordUnexpectedTestFailure", new Class<?>[]{Throwable.class}, failure);
        assertTrue(logs.isEmpty());
        ProjectLog.configure(loggingSnapshot(true));
        invoke(null, "recordUnexpectedTestFailure", new Class<?>[]{Throwable.class}, failure);
        assertEquals(Arrays.asList("settings test future end status=UNKNOWN_FAILURE"
                + " exception=AssertionError"), logs);
    }

    @Test
    public void rollbackFailureLogsFixedFlagAndExceptionTypesOnly() throws Exception {
        Constructor<PreferenceCommit.Result> constructor = PreferenceCommit.Result.class
                .getDeclaredConstructor(boolean.class, Throwable.class, boolean.class, Throwable.class);
        constructor.setAccessible(true);
        Throwable failure = new IllegalStateException("sk-secret original-body");
        PreferenceCommit.Result transaction = constructor.newInstance(false, failure, true,
                new SecurityException("https://secret.invalid rollback-body"));
        ProjectLog.configure(loggingSnapshot(true));
        record("save", syncResult(ConfigurationSync.Direction.SAVE,
                ConfigurationSync.Status.LOCAL_COMMIT_FAILED, false, transaction, failure),
                loggingSnapshot(false));
        assertEquals(Arrays.asList("settings save end status=LOCAL_COMMIT_FAILED direction=SAVE"
                + " localCommitted=false exception=IllegalStateException rollbackFailed=true"
                + " rollbackException=SecurityException"), logs);
    }

    private static Config.Snapshot loggingSnapshot(boolean enabled) {
        return Config.read(new MemoryPrefs().put(Config.KEY_LOGGING_ENABLED, enabled)
                .put(Config.KEY_API_URL, "https://secret.invalid/v1")
                .put(Config.KEY_API_KEY, "sk-secret")
                .put(Config.KEY_EXTRA_HEADERS, "X-Secret: header-secret")
                .put(Config.KEY_SYSTEM_PROMPT, "private prompt body"));
    }

    private static ConfigurationSync.Result syncResult(ConfigurationSync.Direction direction,
            ConfigurationSync.Status status, boolean localCommitted, Throwable failure)
            throws Exception {
        return syncResult(direction, status, localCommitted, null, failure);
    }

    private static ConfigurationSync.Result syncResult(ConfigurationSync.Direction direction,
            ConfigurationSync.Status status, boolean localCommitted,
            PreferenceCommit.Result transaction, Throwable failure) throws Exception {
        Constructor<ConfigurationSync.Result> constructor = ConfigurationSync.Result.class
                .getDeclaredConstructor(ConfigurationSync.Direction.class,
                        ConfigurationSync.Status.class, boolean.class,
                        PreferenceCommit.Result.class, Throwable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(direction, status, localCommitted, transaction, failure);
    }

    private static void record(String operation, ConfigurationSync.Result result,
                               Config.Snapshot confirmedLocal) throws Exception {
        invoke(null, "recordConfigurationResult",
                new Class<?>[]{String.class, ConfigurationSync.Result.class, Config.Snapshot.class},
                operation, result, confirmedLocal);
    }

    private static Object invoke(SettingsActivity page, String name, Class<?>[] parameters,
                                 Object... arguments) throws Exception {
        Method method = SettingsActivity.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(page, arguments);
    }

    private static void set(SettingsActivity page, String name, Object value) throws Exception {
        Field field = SettingsActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(page, value);
    }

    private static Object get(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
