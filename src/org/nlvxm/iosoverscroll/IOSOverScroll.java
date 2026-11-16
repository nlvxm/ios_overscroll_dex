package org.nlvxm.iosoverscroll;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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

    public static void setConfig(double i, double f, double o, double z, double zm) {
        intensity = (float) i;
        flingIntensity = (float) f;
        springOmega = (float) o;
        springZeta = (float) z;
        zetaOmega = springZeta * springOmega;
        omegaD = (float) (springOmega * Math.sqrt(Math.max(0.001, 1.0 - springZeta * springZeta)));
        zoomIntensity = (float) zm;
    }

    private static final Map<ViewGroup, OverScrollController> controllers =
            Collections.synchronizedMap(new WeakHashMap<ViewGroup, OverScrollController>());
    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();

    public static synchronized OverScrollController getOrCreateController(ViewGroup listView) {
        OverScrollController controller = controllers.get(listView);
        if (controller == null) {
            controllers.put(listView, controller = new OverScrollController(listView));
        }
        return controller;
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
                        int velY = (Integer) param.args[1];
                        if (velY != 0) {
                            getOrCreateController((ViewGroup) param.thisObject).onFlingHitEdge(-velY);
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
                        param.args[0] = View.OVER_SCROLL_ALWAYS;
                    }
                }
            });
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
            for (OverScrollController controller : controllers.values()) {
                if (controller != null) controller.reset();
            }
            controllers.clear();
        }
    }
}
