package com.aurora.lsp;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point. Hooks AudioService inside system_server, then drives the software equalizer from a
 * private background thread.
 *
 * <p>The audio effect calls block in the HAL. Running them on the system_server main looper makes
 * the watchdog kill the system process, so nothing here ever touches the main thread.
 */
public final class AuroraXposed implements IXposedHookLoadPackage {

    private static final String TAG = "AURORA-LSP";
    private static final long TICK_MS = 2000L;
    /** Let a batch of setting writes land before reacting to it. */
    private static final long SETTLE_MS = 150L;
    private static final AtomicBoolean HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile Context systemContext;
    private static volatile Handler engineHandler;

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            Handler handler = engineHandler;
            Context context = systemContext;
            if (handler == null || context == null) return;
            try {
                AuroraEngine.get(context).tick();
            } catch (Throwable error) {
                log("tick failed: " + describe(error));
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam params) {
        if (!"android".equals(params.packageName)) return;
        hookAudioService(params.classLoader);
    }

    private static void hookAudioService(ClassLoader loader) {
        if (!HOOKED.compareAndSet(false, true)) return;
        try {
            Class<?> service = XposedHelpers.findClass("com.android.server.audio.AudioService", loader);
            boolean attached = false;
            for (Method method : service.getDeclaredMethods()) {
                if (!"systemReady".equals(method.getName()) || method.getParameterTypes().length != 0) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        attach(param.thisObject);
                    }
                });
                attached = true;
                break;
            }
            if (!attached) {
                HOOKED.set(false);
                log("AudioService.systemReady not found on this build");
            }
        } catch (Throwable error) {
            HOOKED.set(false);
            log("AudioService hook failed: " + describe(error));
        }
    }

    private static void attach(Object audioService) {
        try {
            Object value = XposedHelpers.getObjectField(audioService, "mContext");
            if (!(value instanceof Context)) {
                log("AudioService context unavailable");
                return;
            }
            systemContext = (Context) value;
            start();
        } catch (Throwable error) {
            log("attach failed: " + describe(error));
        }
    }

    private static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        HandlerThread thread = new HandlerThread("aurora-engine",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        engineHandler = handler;
        if (systemContext != null) {
            // Wires the playback callback so a new session is attached within milliseconds instead
            // of waiting for the next poll.
            AuroraEngine.get(systemContext).attach(handler);
        }
        observe(systemContext, handler);
        // Probe runs on the engine thread too: it touches AudioManager, which must never block
        // the system_server main looper.
        handler.post(new Runnable() {
            @Override
            public void run() {
                Context context = systemContext;
                if (context == null) return;
                AuroraProbe.run(context);
            }
        });
        handler.post(TICK);
    }

    private static void observe(Context context, Handler handler) {
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    Handler target = engineHandler;
                    if (target == null) return;
                    target.removeCallbacks(TICK);
                    // One save writes several keys in a row. Reacting to each write pushed a
                    // string of half-applied curves into the effect, which is audible as a short
                    // warble right after a preset change. Wait for the batch instead.
                    target.postDelayed(TICK, SETTLE_MS);
                }
            };
            String[] keys = {
                    AuroraEngine.K_ENABLED, AuroraEngine.K_PANIC, AuroraEngine.K_PRESET,
                    AuroraEngine.K_STRENGTH, AuroraEngine.K_GEQ, AuroraEngine.K_SELFTEST,
                    AuroraEngine.K_USER_PRESETS, AuroraEngine.K_APP_MAP,
                    // Saved-but-unwatched: moving the spread or loudness slider used to wait for
                    // the next 2 s tick, so the change landed audibly late.
                    AuroraEngine.K_SPREAD, AuroraEngine.K_LOUDNESS, AuroraEngine.K_GEQ_SAVED,
                    AuroraEngine.K_PACK
            };
            for (String key : keys) {
                resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            }
        } catch (Throwable error) {
            log("observer registration failed: " + describe(error));
        }
    }

    static void logExternal(String message) {
        log(message);
    }

    private static void log(String message) {
        try {
            XposedBridge.log("[" + TAG + "] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }
}