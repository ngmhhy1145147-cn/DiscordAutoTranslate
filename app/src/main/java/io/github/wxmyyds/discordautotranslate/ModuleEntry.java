package io.github.wxmyyds.discordautotranslate;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.github.wxmyyds.discordautotranslate.core.Config;
import io.github.wxmyyds.discordautotranslate.core.HookCleanup;
import io.github.wxmyyds.discordautotranslate.core.HookConfiguration;
import io.github.wxmyyds.discordautotranslate.core.HookConfigurationLoader;
import io.github.wxmyyds.discordautotranslate.core.ModuleResources;
import io.github.wxmyyds.discordautotranslate.core.ProjectLog;
import io.github.wxmyyds.discordautotranslate.core.Scheduler;
import io.github.wxmyyds.discordautotranslate.core.StateStore;
import io.github.wxmyyds.discordautotranslate.core.TranslationCache;
import io.github.wxmyyds.discordautotranslate.hooks.EmbedHooks;
import io.github.wxmyyds.discordautotranslate.hooks.HookBridge;
import io.github.wxmyyds.discordautotranslate.hooks.InlineIconSpan;
import io.github.wxmyyds.discordautotranslate.hooks.RerenderRegistry;
import io.github.wxmyyds.discordautotranslate.hooks.StructurableTextFactory;
import io.github.wxmyyds.discordautotranslate.hooks.MessageHooks;
import io.github.wxmyyds.discordautotranslate.hooks.NameHooks;
import io.github.wxmyyds.discordautotranslate.hooks.PollHooks;
import io.github.wxmyyds.discordautotranslate.hooks.RenderDispatcher;
import io.github.wxmyyds.discordautotranslate.hooks.HookRuntime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 模块唯一入口类。
 *
 * <p>约束（依据 libxposed API 102.0.0 源码）：
 * <ul>
 *   <li>必须 {@code extends XposedModule} 且提供公开无参构造，框架按此实例化。</li>
 *   <li>热重载仅支持恰好一个 Java 入口类。因此所有 Hook 都由本类统一注册，
 *       不允许再有第二个入口。</li>
 *   <li>不应在 {@code onModuleLoaded} 之前做初始化工作。</li>
 *   <li>不需要（也不应）自行调用 {@code attachFramework()}。</li>
 * </ul>
 *
 * <p>入口声明不在 Manifest 中，而在 {@code META-INF/xposed/java_init.list}；
 * 模块元数据在 {@code META-INF/xposed/module.prop}；作用域在
 * {@code META-INF/xposed/scope.list}。</p>
 */
public class ModuleEntry extends XposedModule {

    /** 固定包名，scope.list 已限定，仍做二次校验（框架可能回传同进程内的其他包）。 */
    private static final String TARGET_PACKAGE = "com.discord";

    /** 当前代次持有的 hook 句柄，热重载时用于清理。 */
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();

    private volatile boolean installed;
    /** Incomplete lifecycle operations require a host restart, never automatic reinstallation. */
    private volatile boolean restartRequired;
    private volatile boolean bootstrapInstalled;
    private volatile HookConfiguration hookConfiguration;

    // ------------------------------------------------------------ 生命周期

    @Override
    public void onModuleLoaded(@NonNull XposedModuleInterface.ModuleLoadedParam param) {
        ProjectLog.attach(this::log);
        configureLogging();
        ProjectLog.info("onModuleLoaded lifecycle callback received");
        ModuleResources.attach(this);
        // 目标进程才需要初始化；其他进程立即停止接收回调，减少无谓开销。
        if (param.isSystemServer()) {
            detach();
        }
    }

    @Override
    public void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        ProjectLog.info("onPackageReady received for Discord");
        if (installed || bootstrapInstalled || restartRequired) {
            // 防止重复注册启动钩子。
            return;
        }
        installBootstrapHook();
    }

    /**
     * 热重载：在旧代码中执行。返回 true 前必须停止本模块拥有的线程、
     * 释放引用，否则旧代次会被新代次长期持有（需求 §5.1、§风险 6）。
     */
    @Override
    public boolean onHotReloading(@NonNull XposedModuleInterface.HotReloadingParam param) {
        try {
            if (!installed) {
                ProjectLog.info("hot reload rejected: no successfully installed runtime; restart Discord required");
                return false;
            }
            Bundle state = new Bundle();
            if (hookConfiguration != null) {
                state.putInt("hookVersionCode", hookConfiguration.versionCode());
            }
            param.setSavedInstanceState(state);
            release();
            return true;
        } catch (Throwable failure) {
            ProjectLog.info("hot reload teardown incomplete error="
                    + failure.getClass().getSimpleName() + "; restart Discord required");
            return false;
        }
    }

    /**
     * 热重载：在新代码中执行。包生命周期回调不会被自动重放，
     * 因此必须在这里主动重建状态并重新注册 Hook。
     */
    @Override
    public void onHotReloaded(@NonNull XposedModuleInterface.HotReloadedParam param) {
        ProjectLog.attach(this::log);
        configureLogging();
        HookRuntime.suspend();
        // Recover profile and loader while the old handles still describe their targets.
        // Snapshot once; removal mutates only the sole ownership list, never this metadata view.
        List<XposedInterface.HookHandle> oldHandles = new ArrayList<>(param.getOldHookHandles());
        hooks.addAll(oldHandles);
        Object savedState = param.getSavedInstanceState();
        HookConfiguration restored = null;
        int restoredVersionCode = -1;
        if (savedState instanceof Bundle) {
            Bundle bundle = (Bundle) savedState;
            restoredVersionCode = bundle.getInt("hookVersionCode", -1);
            if (restoredVersionCode >= 0) {
                try {
                    // Reload from the new APK, never reactivate the old generation's
                    // serialized profile after selectors/constructor metadata changed.
                    restored = HookConfigurationLoader.load(
                            getModuleApplicationInfo().sourceDir, restoredVersionCode);
                } catch (Throwable t) {
                    ProjectLog.info("hot reload profile restoration failed error="
                            + t.getClass().getSimpleName());
                }
            }
        }

        ClassLoader recovered = restored == null ? null
                : classLoaderFromOldHooks(oldHandles, restored);
        try {
            clearRuntime();
        } catch (Throwable failure) {
            ProjectLog.info("hot reload old runtime cleanup failed error="
                    + failure.getClass().getSimpleName() + "; restart Discord required");
            return;
        }
        ModuleResources.attach(this);
        reloadConfig();
        if (restored != null && recovered != null) {
            try {
                install(recovered, restored);
                installed = true;
            } catch (Throwable t) {
                installed = false;
                restartRequired = true;
                ProjectLog.info("hot reload hook installation failed error="
                        + t.getClass().getSimpleName() + "; restart Discord required");
            }
        } else {
            restartRequired = true;
            ProjectLog.info("hot reload restoration unavailable; restart Discord required");
        }
    }

    // ------------------------------------------------------------ 安装与卸载

    private void installBootstrapHook() {
        if (bootstrapInstalled || restartRequired) {
            return;
        }
        try {
            java.lang.reflect.Method attach = Application.class.getDeclaredMethod(
                    "attach", Context.class);
            attach.setAccessible(true);
            XposedInterface.HookHandle handle = hook(attach)
                    .setId("dat.applicationAttach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        try {
                            Object contextObject = chain.getArg(0);
                            if (contextObject instanceof Context) {
                                Context context = (Context) contextObject;
                                if (TARGET_PACKAGE.equals(context.getPackageName())) {
                                    ProjectLog.info("Application.attach reached for Discord");
                                    installForApplication(context, context.getClassLoader());
                                }
                            }
                        } catch (Throwable t) {
                            ProjectLog.info("hook profile selection failed errorClass="
                                    + t.getClass().getSimpleName());
                        }
                        return result;
                    });
            hooks.add(handle);
            bootstrapInstalled = true;
            ProjectLog.info("Application.attach bootstrap hook installed");
        } catch (Throwable t) {
            ProjectLog.info("bootstrap hook installation failed errorClass="
                    + t.getClass().getSimpleName());
            installed = false;
        }
    }

    private void installForApplication(Context context, ClassLoader cl) {
        if (installed || restartRequired) {
            return;
        }
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    TARGET_PACKAGE, 0);
            int versionCode = packageInfo.versionCode;
            ProjectLog.info("selecting hook profile for versionCode=" + versionCode);
            HookConfiguration config = HookConfigurationLoader.load(
                    getModuleApplicationInfo().sourceDir, versionCode);
            if (config == null) {
                ProjectLog.warn("no hook profile for Discord versionCode=" + versionCode);
                return;
            }
            install(cl, config);
            installed = true;
        } catch (Throwable t) {
            ProjectLog.info("configured hook installation failed errorClass="
                    + t.getClass().getSimpleName() + "; restart Discord required");
            installed = false;
            restartRequired = true;
        }
    }

    private void install(ClassLoader cl, HookConfiguration config) {
        if (restartRequired) {
            throw new IllegalStateException("Runtime lifecycle incomplete; restart Discord required");
        }
        if (!config.hasAllRequiredTargets(cl)) {
            throw new IllegalStateException("configured required hook target is unavailable");
        }
        HookRuntime.suspend();
        Set<String> installedHookIds = new HashSet<>();
        try {
            ModuleResources.attach(this);
            ProjectLog.attach(this::log);
            HookConfiguration.activate(config);
            StructurableTextFactory.init(cl, config);
            Scheduler.MainThread.install();
            RenderDispatcher.install();
            reloadConfig();
            MessageHooks.install(this, cl, config, installedHookIds, hooks);
            EmbedHooks.install(this, cl, config, installedHookIds, hooks);
            PollHooks.install(this, cl, config, installedHookIds, hooks);
            NameHooks.install(this, cl, config, installedHookIds, hooks);
            if (!config.hasAllRequiredHooks(installedHookIds)) {
                throw new IllegalStateException("incomplete configured hook installation");
            }
            hookConfiguration = config;
            HookRuntime.activate();
        } catch (Throwable failure) {
            restartRequired = true;
            try {
                clearRuntime();
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                ProjectLog.info("hook installation cleanup incomplete error="
                        + cleanupFailure.getClass().getSimpleName() + "; restart Discord required");
            }
            throw new IllegalStateException("configured hook installation failed", failure);
        }
        ProjectLog.info("installed hook profile versionCode=" + config.versionCode()
                + " requiredHookIds=" + installedHookIds);
    }

    /** 从旧 Hook 的目标方法反推宿主 ClassLoader（热重载后包回调不会重放）。 */
    @Nullable
    private ClassLoader classLoaderFromOldHooks(
            @NonNull List<XposedInterface.HookHandle> oldHandles, HookConfiguration profile) {
        for (XposedInterface.HookHandle handle : oldHandles) {
            try {
                java.lang.reflect.Executable target = handle.getExecutable();
                Class<?> declaring = target == null ? null : target.getDeclaringClass();
                ClassLoader loader = profile.bindingClassLoader(declaring);
                if (loader != null) {
                    return loader;
                }
            } catch (Throwable t) {
                ProjectLog.info("hot reload class loader inspection failed error="
                        + t.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** Drain calls, workers and UI before removing hooks and clearing their supporting state. */
    private void release() {
        clearRuntime();
        ProjectLog.attach(null);
    }

    /** Successful teardown and partial installation cleanup use the same ordered lifecycle. */
    private void clearRuntime() {
        try {
            if (!HookRuntime.suspendAndAwait(2000L)) {
                throw new IllegalStateException("Runtime calls have not drained; restart Discord required");
            }
            // No admitted caller can now recreate a scheduler after this termination check.
            if (!Scheduler.resetAndAwait(2000L)) {
                throw new IllegalStateException("Translation workers have not stopped; restart Discord required");
            }
            // Native rebind needs inactive hooks, captured registrations and the owned UI handler.
            // A fresh generation with no spans must confirm success even before handler installation.
            if (!InlineIconSpan.resetAll(2000L)) {
                throw new IllegalStateException("Inline icon cleanup incomplete; restart Discord required");
            }
            if (!NameHooks.reset(2000L)) {
                throw new IllegalStateException("Name translation cleanup incomplete; restart Discord required");
            }
            int failures = HookCleanup.remove(hooks, XposedInterface.HookHandle::unhook);
            if (failures != 0) {
                throw new IllegalStateException("Hook removal incomplete count=" + failures
                        + "; restart Discord required");
            }
            Scheduler.MainThread.clear();
            RenderDispatcher.reset();
            StateStore.get().onNewGeneration();
            HookBridge.get().reset();
            TranslationCache.reset();
            RerenderRegistry.clear();
            StructurableTextFactory.reset();
            ModuleResources.reset();
            bootstrapInstalled = false;
            hookConfiguration = null;
            HookConfiguration.activate(null);
        } catch (Throwable failure) {
            restartRequired = true;
            throw new IllegalStateException("Runtime cleanup incomplete; restart Discord required", failure);
        } finally {
            installed = false;
        }
    }

    /** Read the log gate before any startup diagnostics, including unsupported versions. */
    private void configureLogging() {
        try {
            ProjectLog.configure(Config.read(getRemotePreferences(Config.GROUP)));
        } catch (Throwable failure) {
            ProjectLog.configure(Config.invalidSnapshot());
        }
    }

    private void reloadConfig() {
        try {
            final SharedPreferences prefs = getRemotePreferences(Config.GROUP);
            Config.Snapshot snapshot = Config.read(prefs);
            ProjectLog.configure(snapshot);
            HookBridge.get().updateConfig(snapshot);
        } catch (Throwable t) {
            ProjectLog.configure(Config.invalidSnapshot());
            HookBridge.get().updateConfig(Config.invalidSnapshot());
        }
    }
}
