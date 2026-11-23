package io.github.wxmyyds.discordautotranslate.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.R;
import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.GlossaryLibrary;
import io.github.wxmyyds.discordautotranslate.core.TranslationScope;
import io.github.wxmyyds.discordautotranslate.core.ConfigurationSync;
import io.github.wxmyyds.discordautotranslate.core.Protocols;
import io.github.wxmyyds.discordautotranslate.core.MemoryPrefs;
import io.github.wxmyyds.discordautotranslate.core.PreferenceCommit;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.translate.ApiTester;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import io.github.libxposed.service.XposedService;

/** 设置界面（需求 §5.7）。 */
public class SettingsActivity extends Activity implements XposedServiceConnectionHolder.Listener {

    private static final String LOCAL_PREFS_NAME = "settings_before_activation";
    private static final String STATE_DRAFT = "configuration_draft";
    private static final int PICK_GLOSSARY = 1;

    private static final String[] TARGET_LANGUAGE_VALUES = {
            "简体中文", "繁體中文", "English", "日本語", "한국어", "Русский",
            "Українська", "Español", "Français", "Deutsch", "Português", "Italiano",
            "العربية", "فارسی", "हिन्दी", "বাংলা", "ไทย", "עברית", "Ελληνικά",
            "Tiếng Việt", "Türkçe", "Bahasa Indonesia"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_EN = {
            "Simplified Chinese", "Traditional Chinese", "English", "Japanese", "Korean",
            "Russian", "Ukrainian", "Spanish", "French", "German", "Portuguese", "Italian",
            "Arabic", "Persian", "Hindi", "Bengali", "Thai", "Hebrew", "Greek",
            "Vietnamese", "Turkish", "Indonesian"
    };
    private static final String[] TARGET_LANGUAGE_LABELS_ZH = {
            "简体中文", "繁體中文", "英语", "日语", "韩语", "俄语", "乌克兰语", "西班牙语",
            "法语", "德语", "葡萄牙语", "意大利语", "阿拉伯语", "波斯语", "印地语",
            "孟加拉语", "泰语", "希伯来语", "希腊语", "越南语", "土耳其语", "印尼语"
    };

    /** 下拉框显示顺序：0=OpenAI-Responses，1=OpenAI-Compatible，2=Anthropic-Messages。 */
    private static final String[] PROTOCOL_ENTRIES =
            {"OpenAI-Responses", "OpenAI-Compatible", "Anthropic-Messages"};

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService testExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor();
    private final SettingsPersistence persistence =
            new SettingsPersistence(saveExecutor, action -> mainHandler.post(action));

    @Nullable
    private XposedService service;
    private SharedPreferences prefs;
    @Nullable
    private SharedPreferences remotePrefs;
    @Nullable
    private Future<?> testFuture;
    @Nullable
    private ApiTester.Call testCall;

    private long testGeneration;
    private volatile long serviceGeneration;
    private long subscribedConnectionGeneration = -1;
    private long formGeneration;
    private long syncAttemptGeneration = -1;
    private long syncJobGeneration;
    private boolean saveInProgress;
    private boolean syncInProgress;
    private boolean started;
    private volatile boolean destroyed;
    private boolean configurationLoaded;

    private Spinner protocolSpinner;
    private Spinner targetLanguageSpinner;
    private ArrayAdapter<String> targetLanguageAdapter;
    private final List<String> targetLanguageValues = new ArrayList<>();
    private boolean chineseUi;
    private boolean loadingUi;
    private boolean hasUnsavedChanges;
    private boolean persistedConfigInvalid;
    private CharSequence[] fieldHints;
    @Nullable
    private String baselineProtocol;
    @Nullable
    private String baselineTargetLanguage;
    private EditText urlInput;
    private EditText keyInput;
    private EditText modelInput;
    private EditText headersInput;
    private EditText systemPromptInput;
    private EditText timeoutInput;
    private EditText concurrencyInput;
    private Switch enabledInput;
    private Switch loggingInput;
    private Switch streamingInput;
    private TextView enabledHint;
    private TextView loggingHint;
    private TextView streamingHint;
    private TextView statusText;
    private TextView resultText;
    private EditText testTextInput;
    private Button saveButton;
    private Button resetButton;
    private Button configCheckButton;
    private Button testConnectionButton;
    private Button testTranslationButton;
    private Button scopeButton;
    private TextView scopeSummary;
    private TranslationScope scopeDraft = TranslationScope.ALL;
    private CheckBox messageTarget;
    private CheckBox serverNameTarget;
    private CheckBox channelNameTarget;
    private CheckBox contextEnabledInput;
    private EditText contextCharactersInput;
    private Spinner glossarySpinner;
    private Button glossaryImportButton;
    private Button glossaryRemoveButton;
    private GlossaryLibrary glossaryDraft = GlossaryLibrary.EMPTY;
    private boolean importingGlossary;
    private Dialog scopeDialog;
    private Bundle pendingScopeEditor;
    private static final String STATE_SCOPE_EDITOR = "scope_editor";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chineseUi = isChineseSystemLanguage();
        setTitle(R.string.module_name);
        setContentView(buildContentView());
        prefs = getSharedPreferences(LOCAL_PREFS_NAME, MODE_PRIVATE);
        installDirtyTracking();
        restoreDraft(savedInstanceState);
        pendingScopeEditor = savedInstanceState == null ? null : savedInstanceState.getBundle(STATE_SCOPE_EDITOR);
        loadIntoUi();
        updateSaveButtonState();
        if (!persistedConfigInvalid) {
            updateStatus(getString(R.string.status_local_ready));
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (configurationLoaded && !saveInProgress && !syncInProgress && !hasUnsavedChanges) {
            loadIntoUi();
        }
        if (service != null && (XposedServiceConnectionHolder.currentService() == null
                || subscribedConnectionGeneration
                        != XposedServiceConnectionHolder.connectionGeneration())) {
            onServiceDied(service);
        }
        XposedServiceConnectionHolder.bind(this);
        persistence.start();
        if (pendingScopeEditor != null && configurationLoaded && !persistence.isLoading()) openScopeEditor();
        updateSaveButtonState();
    }

    @Override
    protected void onStop() {
        started = false;
        persistence.stop();
        if (scopeDialog != null) {
            pendingScopeEditor = TranslationScopeEditor.saveState(scopeDialog);
            scopeDialog.dismiss(); scopeDialog = null;
        }
        XposedServiceConnectionHolder.unbind(this);
        cancelPendingTest();
        updateSaveButtonState();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        persistence.destroy();
        mainHandler.removeCallbacksAndMessages(null);
        cancelPendingTest();
        testExecutor.shutdownNow();
        saveExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (scopeDialog != null) outState.putBundle(STATE_SCOPE_EDITOR, TranslationScopeEditor.saveState(scopeDialog));
        else if (pendingScopeEditor != null) outState.putBundle(STATE_SCOPE_EDITOR, pendingScopeEditor);
        if (persistedConfigInvalid || !hasUnsavedChanges) {
            return;
        }
        Bundle draft = new Bundle();
        EditText[] fields = configurationFields();
        for (int i = 0; i < fields.length; i++) {
            draft.putString("field_" + i, fields[i].getText().toString());
        }
        draft.putBoolean("enabled", enabledInput.isChecked());
        draft.putString("scope", scopeDraft.toJson());
        draft.putBoolean(Config.KEY_TRANSLATE_MESSAGES, messageTarget.isChecked());
        draft.putBoolean(Config.KEY_TRANSLATE_SERVER_NAMES, serverNameTarget.isChecked());
        draft.putBoolean(Config.KEY_TRANSLATE_CHANNEL_NAMES, channelNameTarget.isChecked());
        draft.putBoolean(Config.KEY_CONTEXT_ENABLED, contextEnabledInput.isChecked());
        draft.putString(Config.KEY_GLOSSARY_LIBRARY, glossaryDraft.toJson());
        draft.putBoolean("logging", loggingInput.isChecked());
        draft.putBoolean("streaming", streamingInput.isChecked());
        draft.putInt("protocol", protocolSpinner.getSelectedItemPosition());
        draft.putString("language", selectedTargetLanguage());
        draft.putString("baseline_protocol", baselineProtocol);
        draft.putString("baseline_language", baselineTargetLanguage);
        draft.putBoolean("dirty", hasUnsavedChanges);
        outState.putBundle(STATE_DRAFT, draft);
    }

    private void restoreDraft(@Nullable Bundle savedState) {
        Bundle draft = savedState == null ? null : savedState.getBundle(STATE_DRAFT);
        if (draft == null || !draft.getBoolean("dirty") || persistedConfigInvalid) {
            return;
        }
        loadingUi = true;
        try {
            EditText[] fields = configurationFields();
            for (int i = 0; i < fields.length; i++) {
                fields[i].setText(draft.getString("field_" + i));
            }
            enabledInput.setChecked(draft.getBoolean("enabled"));
            scopeDraft = TranslationScope.parse(draft.getString("scope"));
            updateScopeSummary();
            messageTarget.setChecked(draft.getBoolean(Config.KEY_TRANSLATE_MESSAGES, Config.DEF_TRANSLATE_MESSAGES));
            serverNameTarget.setChecked(draft.getBoolean(Config.KEY_TRANSLATE_SERVER_NAMES,
                    Config.DEF_TRANSLATE_SERVER_NAMES));
            channelNameTarget.setChecked(draft.getBoolean(Config.KEY_TRANSLATE_CHANNEL_NAMES,
                    Config.DEF_TRANSLATE_CHANNEL_NAMES));
            contextEnabledInput.setChecked(draft.getBoolean(Config.KEY_CONTEXT_ENABLED, Config.DEF_CONTEXT_ENABLED));
            String glossary = draft.getString(Config.KEY_GLOSSARY_LIBRARY);
            showGlossary(glossary == null ? GlossaryLibrary.EMPTY : GlossaryLibrary.parse(glossary));
            loggingInput.setChecked(draft.getBoolean("logging", Config.DEF_LOGGING_ENABLED));
            protocolSpinner.setSelection(draft.getInt("protocol"));
            streamingInput.setChecked(draft.getBoolean("streaming"));
            String language = draft.getString("language");
            ensureTargetLanguageAvailable(language);
            targetLanguageSpinner.setSelection(targetLanguagePosition(language));
            baselineProtocol = draft.getString("baseline_protocol");
            baselineTargetLanguage = draft.getString("baseline_language");
            hasUnsavedChanges = draft.getBoolean("dirty");
            formGeneration++;
            updateStreamingAvailability();
        } finally {
            loadingUi = false;
        }
    }

    // ------------------------------------------------------------ 服务连接

    @Override
    public void onServiceConnected(@NonNull XposedService connectedService) {
        if (!started || destroyed || prefs == null || service == connectedService) {
            return;
        }
        service = connectedService;
        subscribedConnectionGeneration = XposedServiceConnectionHolder.connectionGeneration();
        serviceGeneration++;
        syncJobGeneration++;
        syncInProgress = false;
        try {
            remotePrefs = connectedService.getRemotePreferences(Config.GROUP);
        } catch (Throwable t) {
            remotePrefs = null;
            ProjectLog.warn("settings sync access status=FAILED exception="
                    + t.getClass().getSimpleName());
            updateStatus(getString(R.string.status_sync_pending));
            updateSaveButtonState();
            return;
        }
        startServiceSync();
    }

    private void startServiceSync() {
        if (!started || destroyed || service == null || remotePrefs == null
                || !configurationLoaded || persistence.isLoading()
                || saveInProgress || syncInProgress || scopeDialog != null || pendingScopeEditor != null
                || syncAttemptGeneration == serviceGeneration) {
            return;
        }
        // One attempt per actual connection generation, not per Activity start/replay.
        syncAttemptGeneration = serviceGeneration;
        final long generation = serviceGeneration;
        final long importFormGeneration = formGeneration;
        final long job = ++syncJobGeneration;
        final XposedService connectedService = service;
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        syncInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_syncing));
        ProjectLog.info("settings sync start");
        try {
            saveExecutor.execute(() -> {
                final ConfigurationSync.Result result;
                final ConfigurationSync.LocalState localState;
                synchronized (PreferenceCommit.class) {
                    if (destroyed || generation != serviceGeneration) {
                        ProjectLog.info("settings sync end status=CANCELLED");
                        return;
                    }
                    result = ConfigurationSync.synchronize(local, remote);
                    localState = ConfigurationSync.loadLocal(local);
                    recordConfigurationResult("sync", result, localState.snapshot);
                }
                persistence.post(() -> {
                    if (!started || destroyed || local != prefs) {
                        return;
                    }
                    // Refresh only confirmed authority; never load a failed initial import.
                    boolean localConfirmed = result.imported()
                            || result.direction == ConfigurationSync.Direction.EXPORT;
                    if (localConfirmed && !saveInProgress && !hasUnsavedChanges
                            && importFormGeneration == formGeneration) {
                        applyLocalState(localState, true);
                    }
                    if (generation != serviceGeneration || job != syncJobGeneration
                            || remote != remotePrefs) {
                        return;
                    }
                    syncInProgress = false;
                    if (localState.poisoned) {
                        showInvalidSavedConfig();
                        showPersistenceFailure(true,
                                getString(R.string.status_save_persist_failed));
                        return;
                    }
                    if (result.status == ConfigurationSync.Status.INVALID_SOURCE) {
                        // Invalid remote data cannot invalidate/erase the local draft.
                        if (result.direction != ConfigurationSync.Direction.IMPORT) {
                            showInvalidSavedConfig();
                        }
                        updateSaveButtonState();
                        updateStatus(getString(R.string.status_invalid_saved_config));
                        return;
                    }
                    updateSaveButtonState();
                    updateStatus(result.succeeded()
                            ? getString(R.string.status_connected,
                                    connectedService.getFrameworkVersion(),
                                    connectedService.getApiVersion())
                            : getString(R.string.status_sync_failed));
                });
            });
        } catch (Throwable t) {
            ProjectLog.warn("settings sync end status=FAILED exception="
                    + t.getClass().getSimpleName());
            syncInProgress = false;
            updateSaveButtonState();
            updateStatus(getString(R.string.status_sync_failed));
        }
    }

    @Override
    public void onServiceDied(@NonNull XposedService deadService) {
        if (!started || destroyed || service != deadService) {
            return;
        }
        service = null;
        remotePrefs = null;
        serviceGeneration++;
        syncJobGeneration++;
        syncInProgress = false;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_local_ready));
    }

    // ------------------------------------------------------------ UI 构建

    @SuppressWarnings("deprecation") // Platform ActionBar dispatches its height via legacy content insets.
    private View buildContentView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        statusText = new TextView(this);
        statusText.setTextSize(12f);
        root.addView(statusText, matchWrap());

        enabledInput = new Switch(this);
        enabledHint = addToggle(root, enabledInput, SettingsToggleText.Setting.TRANSLATION);

        scopeButton = new Button(this);
        scopeButton.setText(R.string.scope_title);
        scopeButton.setOnClickListener(view -> openScopeEditor());
        root.addView(scopeButton, matchWrap());
        scopeSummary = new TextView(this);
        scopeSummary.setTextSize(12f);
        root.addView(scopeSummary, matchWrap());
        updateScopeSummary();
        addTranslationOptions(root);

        loggingInput = new Switch(this);
        loggingHint = addToggle(root, loggingInput, SettingsToggleText.Setting.LOGGING);
        TextView loggingPrivacy = new TextView(this);
        loggingPrivacy.setTextSize(12f);
        loggingPrivacy.setText(R.string.hint_logging_privacy);
        root.addView(loggingPrivacy, matchWrap());

        // 供应商协议：请求体与译文解析按所选协议内置实现，
        // 不再让用户手写 JSON 请求模板与译文提取路径。
        TextView protocolLabel = new TextView(this);
        protocolLabel.setText(R.string.label_protocol);
        protocolLabel.setPadding(0, dp(8), 0, 0);
        root.addView(protocolLabel, matchWrap());
        protocolSpinner = new Spinner(this);
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PROTOCOL_ENTRIES);
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocolSpinner.setAdapter(protocolAdapter);
        protocolSpinner.setSelection(positionOf(Config.DEF_PROTOCOL));
        root.addView(protocolSpinner, matchWrap());
        protocolSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                updateStreamingAvailability();
                markSpinnerDirty();
            }
        });
        TextView protocolHint = new TextView(this);
        protocolHint.setTextSize(12f);
        protocolHint.setText(R.string.hint_protocol);
        root.addView(protocolHint, matchWrap());

        streamingInput = new Switch(this);
        streamingHint = addToggle(root, streamingInput, SettingsToggleText.Setting.STREAMING);
        TextView toggleDraftHint = new TextView(this);
        toggleDraftHint.setTextSize(12f);
        toggleDraftHint.setText(R.string.hint_switch_draft);
        root.addView(toggleDraftHint, matchWrap());

        // 注意：TYPE_TEXT_VARIATION_* 必须与 TYPE_CLASS_TEXT 组合使用。
        urlInput = addField(root, getString(R.string.label_api_url),
                getString(R.string.hint_api_url),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        keyInput = addField(root, getString(R.string.label_api_key), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        modelInput = addField(root, getString(R.string.label_model), Config.DEF_MODEL,
                InputType.TYPE_CLASS_TEXT);
        TextView targetLanguageLabel = new TextView(this);
        targetLanguageLabel.setText(R.string.label_target_language);
        targetLanguageLabel.setPadding(0, dp(8), 0, 0);
        root.addView(targetLanguageLabel, matchWrap());
        targetLanguageSpinner = new Spinner(this);
        targetLanguageValues.clear();
        targetLanguageValues.addAll(Arrays.asList(TARGET_LANGUAGE_VALUES));
        String[] labels = chineseUi ? TARGET_LANGUAGE_LABELS_ZH : TARGET_LANGUAGE_LABELS_EN;
        targetLanguageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<>(Arrays.asList(labels)));
        targetLanguageAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        targetLanguageSpinner.setAdapter(targetLanguageAdapter);
        targetLanguageSpinner.setSelection(targetLanguagePosition(Config.DEF_TARGET_LANG));
        targetLanguageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                markSpinnerDirty();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                markSpinnerDirty();
            }
        });
        root.addView(targetLanguageSpinner, matchWrap());
        headersInput = addField(root, getString(R.string.label_headers), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        systemPromptInput = addField(root, getString(R.string.label_system_prompt),
                getString(R.string.hint_system_prompt),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        timeoutInput = addField(root, getString(R.string.label_timeout),
                String.valueOf(Config.DEF_TIMEOUT_MS), InputType.TYPE_CLASS_NUMBER);
        concurrencyInput = addField(root, getString(R.string.label_concurrency),
                String.valueOf(Config.DEF_MAX_CONCURRENCY), InputType.TYPE_CLASS_NUMBER);


        testTextInput = addField(root, getString(R.string.label_test_text),
                getString(R.string.hint_test_text),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        fieldHints = new CharSequence[configurationFields().length];
        EditText[] fields = configurationFields();
        for (int i = 0; i < fields.length; i++) {
            fieldHints[i] = fields[i].getHint();
        }

        TextView privacy = new TextView(this);
        privacy.setTextSize(12f);
        privacy.setText(R.string.privacy_notice);
        root.addView(privacy, matchWrap());

        saveButton = new Button(this);
        saveButton.setText(R.string.button_save);
        saveButton.setEnabled(false);
        saveButton.setOnClickListener(v -> save());
        root.addView(saveButton, matchWrap());

        resetButton = new Button(this);
        resetButton.setText(R.string.button_reset_saved_settings);
        resetButton.setVisibility(View.GONE);
        resetButton.setOnClickListener(v -> confirmResetSavedSettings());
        root.addView(resetButton, matchWrap());

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);

        configCheckButton = new Button(this);
        configCheckButton.setText(R.string.button_config_check);
        configCheckButton.setOnClickListener(v -> runSelfCheck());
        testRow.addView(configCheckButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testConnectionButton = new Button(this);
        testConnectionButton.setText(R.string.button_test_connection);
        testConnectionButton.setOnClickListener(v -> runTestTranslation(true));
        testRow.addView(testConnectionButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        testTranslationButton = new Button(this);
        testTranslationButton.setText(R.string.button_test_translation);
        testTranslationButton.setOnClickListener(v -> runTestTranslation(false));
        testRow.addView(testTranslationButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(testRow, matchWrap());

        resultText = new TextView(this);
        resultText.setTextSize(12f);
        resultText.setPadding(0, dp(8), 0, 0);
        root.addView(resultText, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            // These content insets include the native ActionBar, unlike raw system-bar bounds.
            // Pad the viewport so its first/last controls remain reachable at the scroll limits.
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView addToggle(LinearLayout parent, Switch input, SettingsToggleText.Setting setting) {
        input.setShowText(false);
        input.setTextSize(16f);
        input.setMinimumHeight(dp(48));
        parent.addView(input, matchWrap());
        TextView hint = new TextView(this);
        hint.setTextSize(12f);
        parent.addView(hint, matchWrap());
        input.setOnCheckedChangeListener((button, checked) -> {
            updateToggleText(input, hint, setting);
            markDirty();
        });
        updateToggleText(input, hint, setting);
        return hint;
    }

    private void updateToggleText(Switch input, TextView hint, SettingsToggleText.Setting setting) {
        SettingsToggleText copy = SettingsToggleText.select(setting, input.isChecked(),
                configurationLoaded, persistedConfigInvalid, protocolSpinner == null ? null
                        : protocolAt(protocolSpinner.getSelectedItemPosition()));
        input.setText(getString(R.string.switch_title_state, getString(setting.title), getString(copy.state)));
        hint.setText(copy.description);
    }

    private void updateScopeSummary() {
        scopeSummary.setText(getString(R.string.scope_summary,
                getString(scopeDraft.defaultTranslate ? R.string.scope_default_translate
                        : R.string.scope_default_do_not_translate),
                scopeDraft.servers.size(), scopeDraft.channels.size()));
    }

    private void addTranslationOptions(LinearLayout parent) {
        TextView targets = new TextView(this);
        targets.setText(R.string.label_translation_targets);
        targets.setPadding(0, dp(8), 0, 0);
        parent.addView(targets, matchWrap());
        messageTarget = addOption(parent, R.string.target_message_body);
        serverNameTarget = addOption(parent, R.string.target_server_names);
        channelNameTarget = addOption(parent, R.string.target_channel_names);
        contextEnabledInput = addOption(parent, R.string.label_context_mode);
        contextCharactersInput = addField(parent, getString(R.string.label_context_characters),
                Integer.toString(Config.DEF_CONTEXT_CHARACTERS), InputType.TYPE_CLASS_NUMBER);
        TextView contextHint = new TextView(this);
        contextHint.setTextSize(12f);
        contextHint.setText(R.string.hint_context_mode);
        parent.addView(contextHint, matchWrap());

        TextView glossaryLabel = new TextView(this);
        glossaryLabel.setText(R.string.label_glossary);
        glossaryLabel.setPadding(0, dp(8), 0, 0);
        parent.addView(glossaryLabel, matchWrap());
        glossarySpinner = new Spinner(this);
        glossarySpinner.setContentDescription(getString(R.string.label_glossary));
        parent.addView(glossarySpinner, matchWrap());
        glossaryImportButton = new Button(this);
        glossaryImportButton.setText(R.string.button_import_glossary);
        glossaryImportButton.setOnClickListener(view -> pickGlossary());
        glossaryRemoveButton = new Button(this);
        glossaryRemoveButton.setText(R.string.button_remove_glossary);
        glossaryRemoveButton.setOnClickListener(view -> confirmRemoveGlossary());
        LinearLayout glossaryButtons = new LinearLayout(this);
        for (Button button : new Button[]{glossaryImportButton, glossaryRemoveButton}) {
            glossaryButtons.addView(button, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        parent.addView(glossaryButtons, matchWrap());
        TextView glossaryHint = new TextView(this);
        glossaryHint.setTextSize(12f);
        glossaryHint.setText(R.string.hint_glossary);
        parent.addView(glossaryHint, matchWrap());
        showGlossary(glossaryDraft);
        glossarySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (loadingUi || !glossarySpinner.isEnabled() || position < 0
                        || position > glossaryDraft.packs().size()
                        || position != glossarySpinner.getSelectedItemPosition()) {
                    return;
                }
                String name = position == 0 ? null : glossaryDraft.packs().get(position - 1).name;
                if (!Objects.equals(name, glossaryDraft.activeName())) {
                    glossaryDraft = glossaryDraft.select(name);
                    markDirty();
                    updateSaveButtonState();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Selection is always present when the adapter has been populated.
            }
        });
    }

    private CheckBox addOption(LinearLayout parent, int label) {
        CheckBox input = new CheckBox(this);
        input.setText(label);
        input.setMinimumHeight(dp(48));
        input.setOnCheckedChangeListener((button, checked) -> markDirty());
        parent.addView(input, matchWrap());
        return input;
    }

    private void showGlossary(GlossaryLibrary library) {
        glossaryDraft = library;
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.glossary_disabled));
        int position = 0;
        for (GlossaryLibrary.Pack pack : library.packs()) {
            names.add(getString(R.string.glossary_entry, pack.name, pack.terms.size()));
            if (pack.name.equals(library.activeName())) {
                position = names.size() - 1;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        glossarySpinner.setAdapter(adapter);
        glossarySpinner.setSelection(position);
    }

    private boolean canEditGlossary() {
        return started && !destroyed && configurationLoaded && !persistedConfigInvalid
                && !persistence.isLoading() && !saveInProgress && !syncInProgress && !importingGlossary;
    }

    @SuppressWarnings("deprecation") // This platform Activity does not use an AndroidX ActivityResultRegistry.
    private void pickGlossary() {
        if (!canEditGlossary()) {
            return;
        }
        Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(document, PICK_GLOSSARY);
        } catch (ActivityNotFoundException failure) {
            toast(getString(R.string.glossary_import_unavailable));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GLOSSARY || resultCode != RESULT_OK || data == null
                || data.getData() == null || importingGlossary) {
            return;
        }
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) {
            toast(getString(R.string.glossary_import_failed));
            return;
        }
        importingGlossary = true;
        updateSaveButtonState();
        saveExecutor.execute(() -> {
            final GlossaryLibrary.Pack pack;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("No document stream");
                }
                pack = GlossaryLibrary.readPack(input);
            } catch (IOException | RuntimeException failure) {
                persistence.post(() -> finishGlossaryImport(null));
                return;
            }
            persistence.post(() -> finishGlossaryImport(pack));
        });
    }

    private void finishGlossaryImport(@Nullable GlossaryLibrary.Pack pack) {
        importingGlossary = false;
        try {
            if (pack == null || persistedConfigInvalid || !configurationLoaded) {
                toast(getString(R.string.glossary_import_failed));
                return;
            }
            showGlossary(glossaryDraft.addAndSelect(pack));
            markDirty();
            toast(getString(R.string.glossary_import_complete));
        } catch (IllegalArgumentException failure) {
            toast(getString(R.string.glossary_import_conflict));
        } finally {
            updateSaveButtonState();
        }
    }

    private void confirmRemoveGlossary() {
        String selected = glossaryDraft.activeName();
        if (!canEditGlossary() || selected == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.glossary_remove_message, selected))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_remove_glossary, (dialog, which) -> {
                    if (canEditGlossary() && selected.equals(glossaryDraft.activeName())) {
                        showGlossary(glossaryDraft.removeActive());
                        markDirty();
                        updateSaveButtonState();
                    }
                })
                .show();
    }

    /** Shared by the button and entry, including lifecycle and persistence exclusion. */
    private boolean canOpenScopeEditor() {
        return started && !destroyed && configurationLoaded && !persistedConfigInvalid
                && !persistence.isLoading() && scopeDialog == null && !saveInProgress && !syncInProgress;
    }

    private void openScopeEditor() {
        if (!canOpenScopeEditor()) return;
        Bundle restore = pendingScopeEditor;
        pendingScopeEditor = null;
        TranslationScope initial = restore == null ? scopeDraft
                : TranslationScope.parse(restore.getString("scope"));
        scopeDialog = TranslationScopeEditor.show(this, initial, updated -> {
            scopeDraft = updated;
            updateScopeSummary();
            markDirty();
        });
        if (restore != null) TranslationScopeEditor.restoreState(scopeDialog, restore);
        scopeDialog.setOnDismissListener(dialog -> {
            scopeDialog = null;
            updateSaveButtonState();
            if (started && !destroyed && pendingScopeEditor == null) startServiceSync();
        });
        updateSaveButtonState();
    }

    private EditText addField(LinearLayout parent, String label, String hint, int inputType) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setPadding(0, dp(8), 0, 0);
        parent.addView(tv, matchWrap());

        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(inputType);
        parent.addView(et, matchWrap());
        return et;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 下拉框位置 → 协议存储值；无效位置返回 null 供配置校验拒绝。 */
    @Nullable
    private static String protocolAt(int position) {
        if (position == 0) {
            return Config.PROTOCOL_OPENAI_RESPONSES;
        }
        if (position == 1) {
            return Config.PROTOCOL_OPENAI_COMPATIBLE;
        }
        if (position == 2) {
            return Config.PROTOCOL_ANTHROPIC_MESSAGES;
        }
        return null;
    }

    /** 协议存储值 → 下拉框位置；未知值返回无选中位置。 */
    private static int positionOf(String protocol) {
        if (Config.PROTOCOL_OPENAI_RESPONSES.equals(protocol)) {
            return 0;
        }
        if (Config.PROTOCOL_OPENAI_COMPATIBLE.equals(protocol)) {
            return 1;
        }
        if (Config.PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) {
            return 2;
        }
        return AdapterView.INVALID_POSITION;
    }

    private void installDirtyTracking() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        for (EditText field : configurationFields()) {
            field.addTextChangedListener(watcher);
        }
    }

    private void markDirty() {
        if (!loadingUi && configurationLoaded && !persistedConfigInvalid) {
            formGeneration++;
            hasUnsavedChanges = true;
        }
    }

    private void markSpinnerDirty() {
        if (loadingUi || !configurationLoaded || persistedConfigInvalid) {
            return;
        }
        String protocol = protocolAt(protocolSpinner.getSelectedItemPosition());
        String targetLanguage = selectedTargetLanguage();
        if (!sameValue(protocol, baselineProtocol)
                || !sameValue(targetLanguage, baselineTargetLanguage)) {
            formGeneration++;
            hasUnsavedChanges = true;
        }
    }

    private static boolean sameValue(@Nullable String left, @Nullable String right) {
        return left == null ? right == null : left.equals(right);
    }

    private void updateStreamingAvailability() {
        if (streamingInput != null && protocolSpinner != null) {
            int position = protocolSpinner.getSelectedItemPosition();
            boolean hasSelection = position >= 0 && position < 3;
            streamingInput.setEnabled(configurationLoaded && !persistedConfigInvalid && hasSelection
                    && Protocols.supportsStreaming(protocolAt(position)));
            updateToggleText(streamingInput, streamingHint, SettingsToggleText.Setting.STREAMING);
        }
    }

    /** 显示旧配置中的列表外语言，避免升级后静默改写用户设置。 */
    private void ensureTargetLanguageAvailable(String language) {
        if (language == null || targetLanguageAdapter == null
                || targetLanguageValues.contains(language)) {
            return;
        }
        targetLanguageValues.add(language);
        targetLanguageAdapter.add(getString(R.string.legacy_language));
    }

    private int targetLanguagePosition(String language) {
        return targetLanguageValues.indexOf(language);
    }

    @Nullable
    private String selectedTargetLanguage() {
        int position = targetLanguageSpinner.getSelectedItemPosition();
        return position >= 0 && position < targetLanguageValues.size()
                ? targetLanguageValues.get(position) : null;
    }

    private boolean isChineseSystemLanguage() {
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        return "zh".equalsIgnoreCase(locale.getLanguage());
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------ 读写

    private EditText[] configurationFields() {
        return new EditText[] {urlInput, keyInput, modelInput, headersInput,
                systemPromptInput, timeoutInput, concurrencyInput, testTextInput, contextCharactersInput};
    }

    /** 无效快照的占位值不是用户保存的设置；锁定页面直到用户明确重置。 */
    private void showInvalidSavedConfig() {
        persistedConfigInvalid = true;
        pendingScopeEditor = null;
        if (scopeDialog != null) { scopeDialog.dismiss(); scopeDialog = null; }
        boolean wasLoading = loadingUi;
        loadingUi = true;
        try {
            cancelPendingTest();
            ProjectLog.configure(Config.invalidSnapshot());
            enabledInput.setChecked(false);
            scopeDraft = TranslationScope.ALL;
            scopeSummary.setText("");
            messageTarget.setChecked(false);
            serverNameTarget.setChecked(false);
            channelNameTarget.setChecked(false);
            contextEnabledInput.setChecked(false);
            showGlossary(GlossaryLibrary.EMPTY);
            loggingInput.setChecked(false);
            streamingInput.setChecked(false);
            protocolSpinner.setSelection(AdapterView.INVALID_POSITION);
            targetLanguageSpinner.setSelection(AdapterView.INVALID_POSITION);
            for (EditText field : configurationFields()) {
                field.setText("");
                field.setHint(null);
            }
            baselineProtocol = null;
            baselineTargetLanguage = null;
            hasUnsavedChanges = false;
            resultText.setText("");
        } finally {
            loadingUi = wasLoading;
        }
        updateSaveButtonState();
        updateStatus(getString(R.string.status_invalid_saved_config));
    }

    /** All persisted reads, including poison checks, run on the persistence executor. */
    private void loadIntoUi() {
        final SharedPreferences local = prefs;
        if (local == null || destroyed || persistence.isLoading()) {
            return;
        }
        try {
            persistence.load(local, () -> formGeneration, () -> hasUnsavedChanges,
                    (state, replaceForm) -> {
                        if (destroyed || local != prefs) {
                            return;
                        }
                        applyLocalState(state, replaceForm);
                        updateSaveButtonState();
                        if (pendingScopeEditor != null && started && !persistedConfigInvalid) openScopeEditor();
                        else startServiceSync();
                    });
        } catch (RuntimeException failure) {
            ProjectLog.warn("settings load end status=FAILED exception="
                    + failure.getClass().getSimpleName());
            updateStatus(getString(R.string.status_save_persist_failed));
        }
        updateSaveButtonState();
    }

    private void applyLocalState(ConfigurationSync.LocalState state, boolean replaceForm) {
        configurationLoaded = true;
        if (state.snapshot.configError != null) {
            showInvalidSavedConfig();
            if (state.poisoned) {
                showPersistenceFailure(true, getString(R.string.status_save_persist_failed));
            }
            return;
        }
        persistedConfigInvalid = false;
        ProjectLog.configure(state.snapshot);
        // Draft restoration precedes the asynchronous read. Neither it nor newer edits
        // can be replaced by the completion of an older load/import.
        if (replaceForm) {
            applySnapshot(state.snapshot);
        }
    }

    private void applySnapshot(Config.Snapshot snapshot) {
        loadingUi = true;
        try {
            persistedConfigInvalid = false;
            EditText[] fields = configurationFields();
            for (int i = 0; i < fields.length; i++) {
                fields[i].setHint(fieldHints[i]);
            }
            enabledInput.setChecked(snapshot.enabled);
            scopeDraft = snapshot.translationScope;
            updateScopeSummary();
            messageTarget.setChecked(snapshot.translateMessages);
            serverNameTarget.setChecked(snapshot.translateServerNames);
            channelNameTarget.setChecked(snapshot.translateChannelNames);
            contextEnabledInput.setChecked(snapshot.contextEnabled);
            setTextOrPlaceholder(contextCharactersInput, Integer.toString(snapshot.contextCharacters),
                    Integer.toString(Config.DEF_CONTEXT_CHARACTERS));
            showGlossary(snapshot.glossaryLibrary);
            loggingInput.setChecked(snapshot.loggingEnabled);
            protocolSpinner.setSelection(positionOf(snapshot.protocol));
            streamingInput.setChecked(snapshot.streaming);
            updateStreamingAvailability();
            setTextOrPlaceholder(urlInput, snapshot.apiUrl, Config.DEF_API_URL);
            setTextOrPlaceholder(keyInput, snapshot.apiKey, Config.DEF_API_KEY);
            setTextOrPlaceholder(modelInput, snapshot.model, Config.DEF_MODEL);
            ensureTargetLanguageAvailable(snapshot.targetLang);
            targetLanguageSpinner.setSelection(targetLanguagePosition(snapshot.targetLang));
            setTextOrPlaceholder(headersInput, snapshot.extraHeaders, Config.DEF_EXTRA_HEADERS);
            setTextOrPlaceholder(systemPromptInput, snapshot.systemPrompt, Config.DEF_SYSTEM_PROMPT);
            setTextOrPlaceholder(timeoutInput, String.valueOf(snapshot.timeoutMs),
                    String.valueOf(Config.DEF_TIMEOUT_MS));
            setTextOrPlaceholder(concurrencyInput, String.valueOf(snapshot.maxConcurrency),
                    String.valueOf(Config.DEF_MAX_CONCURRENCY));
            baselineProtocol = snapshot.protocol;
            baselineTargetLanguage = snapshot.targetLang;
            hasUnsavedChanges = false;
        } finally {
            loadingUi = false;
            updateSaveButtonState();
        }
    }

    private void save() {
        if (persistedConfigInvalid) {
            updateStatus(getString(R.string.status_invalid_saved_config));
            return;
        }
        if (!configurationLoaded || persistence.isLoading() || saveInProgress
                || syncInProgress || importingGlossary) {
            return;
        }
        if (!started || destroyed || prefs == null) {
            updateStatus(getString(R.string.status_save_persist_failed));
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        if (snapshot.configError != null) {
            ProjectLog.warn("settings save end status=INVALID_SOURCE");
            updateStatus(getString(R.string.status_invalid_config));
            return;
        }
        commitConfiguration(snapshot);
    }

    private void confirmResetSavedSettings() {
        if (!started || destroyed || !persistedConfigInvalid || persistence.isLoading()
                || saveInProgress || syncInProgress) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.button_reset_saved_settings)
                .setMessage(R.string.confirm_reset_saved_settings)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.button_reset_saved_settings,
                        (dialog, which) -> commitConfiguration(null))
                .show();
    }

    /** 保存与显式重置共用本机提交和远程同步链路；null 仅表示已确认的重置。 */
    private void commitConfiguration(@Nullable Config.Snapshot snapshot) {
        final boolean resetting = snapshot == null;
        final String operation = resetting ? "reset" : "save";
        if (!configurationLoaded || persistence.isLoading() || saveInProgress || syncInProgress
                || resetting != persistedConfigInvalid) {
            return;
        }
        if (!resetting && snapshot.configError != null) {
            updateStatus(getString(R.string.status_invalid_config));
            return;
        }
        final SharedPreferences local = prefs;
        final SharedPreferences remote = remotePrefs;
        final long savedFormGeneration = formGeneration;
        final long savedServiceGeneration = serviceGeneration;
        if (!started || destroyed || local == null) {
            showPersistenceFailure(resetting, getString(R.string.status_save_persist_failed));
            return;
        }

        saveInProgress = true;
        updateSaveButtonState();
        updateStatus(getString(R.string.status_saving));
        if (resetting) {
            resultText.setText(R.string.status_resetting);
        }
        ProjectLog.info("settings " + operation + " start");
        try {
            saveExecutor.execute(() -> {
                final ConfigurationSync.Result saveResult;
                final ConfigurationSync.LocalState localState;
                synchronized (PreferenceCommit.class) {
                    saveResult = ConfigurationSync.save(local, remote, snapshot);
                    localState = ConfigurationSync.loadLocal(local);
                    recordConfigurationResult(operation, saveResult, localState.snapshot);
                }
                final boolean localResult = saveResult.localCommitted;
                final boolean remoteResult = remote != null && saveResult.succeeded();
                final Throwable saveFailure = saveResult.failure;
                final PreferenceCommit.Result commitResult = saveResult.transaction;
                persistence.post(() -> {
                    if (!started || destroyed || local != prefs) {
                        return;
                    }
                    saveInProgress = false;
                    updateSaveButtonState();
                    if (!localResult) {
                        if (localState.poisoned) {
                            showInvalidSavedConfig();
                        }
                        String detail = saveFailure == null
                                ? getString(R.string.status_save_persist_failed)
                                : getString(R.string.status_save_failed,
                                        saveFailure.getClass().getSimpleName());
                        if (commitResult != null && commitResult.rollbackFailed) {
                            // Only fixed labels and exception classes, never values/messages.
                            detail += "\n" + (chineseUi ? "回滚：" : "Rollback: ")
                                    + (commitResult.rollbackFailure == null
                                            ? getString(R.string.status_save_persist_failed)
                                            : getString(R.string.status_save_failed,
                                                    commitResult.rollbackFailure.getClass()
                                                            .getSimpleName()));
                        }
                        showPersistenceFailure(resetting || persistedConfigInvalid, detail);
                    } else if (resetting) {
                        applyLocalState(localState,
                                !hasUnsavedChanges && savedFormGeneration == formGeneration);
                        String status = getString(remoteResult && remote == remotePrefs
                                && savedServiceGeneration == serviceGeneration
                                ? R.string.status_reset_saved_restart
                                : remote == null ? R.string.status_reset_sync_pending
                                        : R.string.status_reset_sync_failed);
                        resultText.setText(status);
                        updateStatus(status);
                    } else {
                        if (savedFormGeneration == formGeneration) {
                            baselineProtocol = snapshot.protocol;
                            baselineTargetLanguage = snapshot.targetLang;
                            hasUnsavedChanges = false;
                        }
                        toast(getString(R.string.toast_saved));
                        updateStatus(getString(remoteResult && remote == remotePrefs
                                && savedServiceGeneration == serviceGeneration
                                ? R.string.status_saved_restart : R.string.status_sync_pending));
                    }
                    // A new connection blocked by this save gets its one sync attempt;
                    // this is not a retry against the already attempted remote store.
                    if (service != null && savedServiceGeneration != serviceGeneration) {
                        startServiceSync();
                    }
                });
            });
        } catch (Throwable t) {
            ProjectLog.warn("settings " + operation + " end status=FAILED exception="
                    + t.getClass().getSimpleName());
            saveInProgress = false;
            updateSaveButtonState();
            showPersistenceFailure(resetting, getString(R.string.status_save_failed,
                    t.getClass().getSimpleName()));
        }
    }

    /** Called under the persistence monitor; only confirmed local commits may change the gate. */
    private static void recordConfigurationResult(String operation, ConfigurationSync.Result result,
                                                  Config.Snapshot confirmedLocal) {
        if (result.localCommitted) {
            ProjectLog.configure(confirmedLocal);
        }
        String message = "settings " + operation + " end status=" + result.status.name()
                + " direction=" + (result.direction == null ? "NONE" : result.direction.name())
                + " localCommitted=" + result.localCommitted;
        if (result.failure != null) {
            message += " exception=" + result.failure.getClass().getSimpleName();
        }
        if (result.transaction != null && result.transaction.rollbackFailed) {
            message += " rollbackFailed=true";
            if (result.transaction.rollbackFailure != null) {
                message += " rollbackException="
                        + result.transaction.rollbackFailure.getClass().getSimpleName();
            }
        }
        if (result.succeeded()) {
            ProjectLog.info(message);
        } else {
            ProjectLog.warn(message);
        }
    }

    private void showPersistenceFailure(boolean resetting, String detail) {
        if (resetting) {
            resultText.setText(getString(R.string.status_reset_failed, detail));
        }
        updateStatus(detail);
    }

    private void updateSaveButtonState() {
        boolean available = started && !destroyed && prefs != null && configurationLoaded
                && !persistence.isLoading() && !saveInProgress && !syncInProgress && !importingGlossary;
        boolean formAvailable = configurationLoaded && !persistedConfigInvalid;
        if (saveButton != null) {
            saveButton.setEnabled(available && !persistedConfigInvalid);
        }
        if (resetButton != null) {
            resetButton.setVisibility(persistedConfigInvalid ? View.VISIBLE : View.GONE);
            resetButton.setEnabled(available && persistedConfigInvalid);
        }
        enabledInput.setEnabled(formAvailable);
        scopeButton.setEnabled(canOpenScopeEditor());
        scopeSummary.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        loggingInput.setEnabled(formAvailable);
        updateToggleText(enabledInput, enabledHint, SettingsToggleText.Setting.TRANSLATION);
        updateToggleText(loggingInput, loggingHint, SettingsToggleText.Setting.LOGGING);
        protocolSpinner.setEnabled(formAvailable);
        protocolSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        targetLanguageSpinner.setEnabled(formAvailable);
        targetLanguageSpinner.setVisibility(persistedConfigInvalid ? View.INVISIBLE : View.VISIBLE);
        for (EditText field : configurationFields()) {
            field.setEnabled(formAvailable);
        }
        messageTarget.setEnabled(formAvailable);
        serverNameTarget.setEnabled(formAvailable);
        channelNameTarget.setEnabled(formAvailable);
        contextEnabledInput.setEnabled(formAvailable);
        boolean glossaryAvailable = canEditGlossary();
        glossarySpinner.setEnabled(glossaryAvailable && !glossaryDraft.packs().isEmpty());
        glossaryImportButton.setEnabled(glossaryAvailable);
        glossaryRemoveButton.setEnabled(glossaryAvailable && glossaryDraft.activeName() != null);
        updateStreamingAvailability();
        setTestButtonsEnabled(testFuture == null);
    }

    private static Object parseIntInput(EditText editText, int defaultValue) {
        String value = editText.getText().toString().trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String textOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString().trim();
        return value.isEmpty() ? defaultValue : value;
    }

    /** 值等于默认值时清空输入框，灰色占位符（hint）展示默认值；自定义值才写入。 */
    private static void setTextOrPlaceholder(EditText field, String value, String def) {
        field.setText(def.equals(value) ? "" : value);
    }

    private static String promptOrDefault(EditText editText, String defaultValue) {
        String value = editText.getText().toString();
        return value.trim().isEmpty() ? defaultValue : value;
    }


    private Config.Snapshot snapshotFromUi() {
        if (persistedConfigInvalid) {
            return Config.invalidSnapshot();
        }
        MemoryPrefs memoryPrefs = new MemoryPrefs();
        memoryPrefs.put(Config.KEY_ENABLED, enabledInput.isChecked());
        memoryPrefs.put(Config.KEY_TRANSLATION_SCOPE, scopeDraft.toJson());
        memoryPrefs.put(Config.KEY_TRANSLATE_MESSAGES, messageTarget.isChecked());
        memoryPrefs.put(Config.KEY_TRANSLATE_SERVER_NAMES, serverNameTarget.isChecked());
        memoryPrefs.put(Config.KEY_TRANSLATE_CHANNEL_NAMES, channelNameTarget.isChecked());
        memoryPrefs.put(Config.KEY_CONTEXT_ENABLED, contextEnabledInput.isChecked());
        Object context = parseIntInput(contextCharactersInput, Config.DEF_CONTEXT_CHARACTERS);
        if (!(context instanceof Integer) || (Integer) context < Config.MIN_CONTEXT_CHARACTERS
                || (Integer) context > Config.MAX_CONTEXT_CHARACTERS) {
            contextCharactersInput.setError(getString(R.string.error_context_characters,
                    Config.MIN_CONTEXT_CHARACTERS, Config.MAX_CONTEXT_CHARACTERS));
            return Config.invalidSnapshot();
        }
        memoryPrefs.put(Config.KEY_CONTEXT_CHARACTERS, context);
        memoryPrefs.put(Config.KEY_GLOSSARY_LIBRARY, glossaryDraft.toJson());
        memoryPrefs.put(Config.KEY_LOGGING_ENABLED, loggingInput.isChecked());
        memoryPrefs.put(Config.KEY_PROTOCOL,
                protocolAt(protocolSpinner.getSelectedItemPosition()));
        memoryPrefs.put(Config.KEY_STREAMING, streamingInput.isChecked());
        memoryPrefs.put(Config.KEY_API_URL, textOrDefault(urlInput, Config.DEF_API_URL));
        memoryPrefs.put(Config.KEY_API_KEY, keyInput.getText().toString().trim());
        memoryPrefs.put(Config.KEY_MODEL, textOrDefault(modelInput, Config.DEF_MODEL));
        memoryPrefs.put(Config.KEY_TARGET_LANG, selectedTargetLanguage());
        memoryPrefs.put(Config.KEY_EXTRA_HEADERS, headersInput.getText().toString());
        memoryPrefs.put(Config.KEY_SYSTEM_PROMPT,
                promptOrDefault(systemPromptInput, Config.DEF_SYSTEM_PROMPT));
        memoryPrefs.put(Config.KEY_TIMEOUT_MS, parseIntInput(timeoutInput, Config.DEF_TIMEOUT_MS));
        memoryPrefs.put(Config.KEY_MAX_CONCURRENCY,
                parseIntInput(concurrencyInput, Config.DEF_MAX_CONCURRENCY));
        return Config.read(memoryPrefs);
    }

    /** 离线配置检查：不发网络请求。 */
    private void runSelfCheck() {
        if (persistedConfigInvalid) {
            updateStatus(getString(R.string.status_invalid_saved_config));
            return;
        }
        if (!started || destroyed || !configurationLoaded || persistence.isLoading()
                || saveInProgress || syncInProgress || importingGlossary || testFuture != null) {
            return;
        }
        showResult(ApiTester.selfCheck(snapshotFromUi()));
    }

    /**
     * 真实请求测试，使用本页面独立的单任务 executor。
     *
     * @param connectionTest true=由 ApiTester 选择固定探针，false=翻译测试文本输入框
     */
    private void runTestTranslation(boolean connectionTest) {
        if (!started || destroyed || !configurationLoaded || persistence.isLoading()
                || persistedConfigInvalid || saveInProgress || syncInProgress
                || importingGlossary || testFuture != null) {
            return;
        }
        Config.Snapshot snapshot = snapshotFromUi();
        final String text = testTextInput.getText().toString();
        if (!connectionTest && text.trim().isEmpty()) {
            resultText.setText(R.string.status_enter_test_text);
            return;
        }

        final long generation = ++testGeneration;
        resultText.setText(R.string.status_requesting);
        setTestButtonsEnabled(false);

        final ApiTester.Call call = ApiTester.newCall(snapshot,
                connectionTest ? ApiTester.TestKind.CONNECTION : ApiTester.TestKind.TRANSLATION,
                text);
        testCall = call;
        FutureTask<ApiTester.Result> task = new FutureTask<ApiTester.Result>(call) {
            @Override
            protected void done() {
                final ApiTester.Result result;
                final Throwable failure;
                try {
                    result = get();
                    failure = null;
                } catch (CancellationException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordUnexpectedTestFailure(e);
                    mainHandler.post(() -> finishTest(generation, this, null, e));
                    return;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    recordUnexpectedTestFailure(cause);
                    mainHandler.post(() -> finishTest(generation, this, null, cause));
                    return;
                }
                mainHandler.post(() -> finishTest(generation, this, result, failure));
            }
        };
        testFuture = task;
        try {
            testExecutor.execute(task);
        } catch (Throwable t) {
            ProjectLog.warn("settings test submission end status=FAILED exception="
                    + t.getClass().getSimpleName());
            call.cancel();
            task.cancel(true);
            testCall = null;
            testFuture = null;
            setTestButtonsEnabled(true);
            resultText.setText(getString(R.string.status_test_not_started,
                    t.getClass().getSimpleName()));
        }
    }

    private void finishTest(long generation, Future<?> task,
                            @Nullable ApiTester.Result result, @Nullable Throwable failure) {
        if (!started || destroyed || generation != testGeneration || testFuture != task) {
            return;
        }
        testFuture = null;
        testCall = null;
        setTestButtonsEnabled(true);
        if (result != null) {
            showResult(result);
        } else {
            resultText.setText(getString(R.string.status_test_failed_detail,
                    failure == null ? getString(R.string.unknown_error)
                            : failure.getClass().getSimpleName()));
            updateStatus(getString(R.string.status_test_failed));
        }
    }

    /** Future boundary diagnostics remain available even when UI delivery is no longer owned. */
    private static void recordUnexpectedTestFailure(Throwable failure) {
        ProjectLog.warn("settings test future end status=UNKNOWN_FAILURE exception="
                + failure.getClass().getSimpleName());
    }

    private void cancelPendingTest() {
        testGeneration++;
        Future<?> pending = testFuture;
        ApiTester.Call call = testCall;
        testFuture = null;
        testCall = null;
        try {
            if (call != null) {
                call.cancel();
            }
        } finally {
            if (pending != null) {
                pending.cancel(true);
            }
        }
        setTestButtonsEnabled(true);
    }

    private void setTestButtonsEnabled(boolean enabled) {
        enabled = enabled && started && !destroyed && configurationLoaded
                && !persistence.isLoading() && !persistedConfigInvalid
                && !saveInProgress && !syncInProgress && !importingGlossary;
        if (configCheckButton != null) {
            configCheckButton.setEnabled(enabled);
        }
        if (testConnectionButton != null) {
            testConnectionButton.setEnabled(enabled);
        }
        if (testTranslationButton != null) {
            testTranslationButton.setEnabled(enabled);
        }
    }

    private void showResult(ApiTester.Result result) {
        resultText.setText(ApiTestResultFormatter.format(result, this::getString));
        updateStatus(getString(result.ok
                ? R.string.status_test_passed : R.string.status_test_failed));
    }

    private void updateStatus(String status) {
        if (!destroyed && statusText != null) {
            statusText.setText(persistedConfigInvalid
                    ? getString(R.string.status_invalid_saved_config) : status);
        }
    }

    private void toast(String message) {
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }
}
