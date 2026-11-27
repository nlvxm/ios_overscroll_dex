package org.nlvxm.iosoverscroll;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EdgeEffect;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public class IOSOverScroll {

    public static float intensity = 1.35f;
    public static float flingIntensity = 1.30f;
    public static float springOmega = 19.5f;
    public static float springZeta = 0.72f;
    public static float zetaOmega = 14.04f;
    public static float omegaD = 13.5325f;
    public static float zoomIntensity = 1.0f;
    public static float stretchIntensity = 1.0f;

    public static void setConfig(double i, double f, double o, double z, double zm, double str) {
        intensity = (float) i;
        flingIntensity = (float) f;
        springOmega = (float) o;
        springZeta = (float) z;
        zetaOmega = springZeta * springOmega;
        omegaD = (float) (springOmega * Math.sqrt(Math.max(0.001, 1.0 - springZeta * springZeta)));
        zoomIntensity = (float) zm;
        stretchIntensity = (float) str;
    }

    public static void setConfig(double i, double f, double o, double z, double zm) {
        setConfig(i, f, o, z, zm, 0.0);
    }

    private static final Map<ViewGroup, WeakReference<OverScrollController>> controllers =
            Collections.synchronizedMap(new WeakHashMap<ViewGroup, WeakReference<OverScrollController>>());
    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();

    public static synchronized OverScrollController getOrCreateController(ViewGroup listView) {
        WeakReference<OverScrollController> ref = controllers.get(listView);
        OverScrollController controller = ref != null ? ref.get() : null;
        if (controller == null) {
            controller = new OverScrollController(listView);
            controllers.put(listView, new WeakReference<>(controller));
        }
        return controller;
    }

    private static XC_MethodHook constantHook(final Object result) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setResult(result);
            }
        };
    }

    private static void hook(Class<?> cls, String name, Class<?>[] params, XC_MethodHook hook) {
        try {
            Method m = cls.getDeclaredMethod(name, params);
            m.setAccessible(true);
            unhooks.add(XposedBridge.hookMethod(m, hook));
        } catch (Throwable ignored) {}
    }

    public static synchronized void start() {
        stop();
        try {
            ClassLoader cl = IOSOverScroll.class.getClassLoader();
            if (cl == null) cl = ClassLoader.getSystemClassLoader();

            final Class<?> rlv = cl.loadClass("org.telegram.ui.Components.RecyclerListView");
            Class<?> rv = cl.loadClass("androidx.recyclerview.widget.RecyclerView");

            hook(rlv, "dispatchTouchEvent", new Class<?>[]{MotionEvent.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev != null && getOrCreateController((ViewGroup) param.thisObject).onBeforeDispatchTouchEvent(ev)) {
                        param.setResult(Boolean.TRUE);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev != null) {
                        getOrCreateController((ViewGroup) param.thisObject).onAfterDispatchTouchEvent(ev);
                    }
                }
            });

            hook(rv, "absorbGlows", new Class<?>[]{int.class, int.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (rlv.isInstance(param.thisObject)) {
                        OverScrollController controller = getOrCreateController((ViewGroup) param.thisObject);
                        if (controller.isExcludedScreen()) return;
                        int velY = (Integer) param.args[1];
                        if (velY != 0) {
                            controller.onFlingHitEdge(-velY);
                            param.setResult(null);
                        }
                    }
                }
            });

            hook(rv, "requestDisallowInterceptTouchEvent", new Class<?>[]{boolean.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (rlv.isInstance(param.thisObject)) {
                        getOrCreateController((ViewGroup) param.thisObject).setDisallowIntercept((Boolean) param.args[0]);
                    }
                }
            });

            hook(View.class, "setOverScrollMode", new Class<?>[]{int.class}, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (rlv.isInstance(param.thisObject) && (Integer) param.args[0] == View.OVER_SCROLL_NEVER) {
                        if (getOrCreateController((ViewGroup) param.thisObject).isExcludedScreen()) return;
                        param.args[0] = View.OVER_SCROLL_ALWAYS;
                    }
                }
            });

            XC_MethodHook noop = constantHook(null);
            XC_MethodHook zero = constantHook(0f);
            hook(EdgeEffect.class, "draw", new Class<?>[]{Canvas.class}, constantHook(Boolean.FALSE));
            hook(EdgeEffect.class, "onPull", new Class<?>[]{float.class}, noop);
            hook(EdgeEffect.class, "onPull", new Class<?>[]{float.class, float.class}, noop);
            hook(EdgeEffect.class, "onPullDistance", new Class<?>[]{float.class, float.class}, zero);
            hook(EdgeEffect.class, "onAbsorb", new Class<?>[]{int.class}, noop);
            hook(EdgeEffect.class, "isFinished", new Class<?>[0], constantHook(Boolean.TRUE));
            hook(EdgeEffect.class, "getDistance", new Class<?>[0], zero);
        } catch (Throwable ignored) {}
    }

    public static synchronized void stop() {
        for (XC_MethodHook.Unhook unhook : unhooks) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {}
        }
        unhooks.clear();

        synchronized (controllers) {
            for (WeakReference<OverScrollController> ref : controllers.values()) {
                OverScrollController controller = ref != null ? ref.get() : null;
                if (controller != null) controller.reset();
            }
            controllers.clear();
        }
    }
}