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

    public static final float intensity = 1.35f;
    public static final float flingIntensity = 1.30f;
    public static final float springOmega = 19.5f;

    private static final Map<ViewGroup, OverScrollController> controllers =
            Collections.synchronizedMap(new WeakHashMap<ViewGroup, OverScrollController>());

    private static final List<XC_MethodHook.Unhook> unhooks = new ArrayList<>();

    public static synchronized OverScrollController getOrCreateController(ViewGroup listView) {
        OverScrollController controller = controllers.get(listView);
        if (controller == null) {
            controller = new OverScrollController(listView);
            controllers.put(listView, controller);
        }
        return controller;
    }

    public static synchronized void start() {
        stop();

        try {
            ClassLoader cl = IOSOverScroll.class.getClassLoader();
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }

            Class<?> recyclerListViewClass = cl.loadClass("org.telegram.ui.Components.RecyclerListView");
            Class<?> recyclerViewClass = cl.loadClass("androidx.recyclerview.widget.RecyclerView");

            Method dispatchTouchEventMethod = recyclerListViewClass.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            dispatchTouchEventMethod.setAccessible(true);
            unhooks.add(XposedBridge.hookMethod(dispatchTouchEventMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    ViewGroup listView = (ViewGroup) param.thisObject;
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev == null) return;

                    OverScrollController controller = getOrCreateController(listView);
                    if (controller.onBeforeDispatchTouchEvent(ev)) {
                        param.setResult(Boolean.TRUE);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    ViewGroup listView = (ViewGroup) param.thisObject;
                    MotionEvent ev = (MotionEvent) param.args[0];
                    if (ev == null) return;

                    OverScrollController controller = getOrCreateController(listView);
                    controller.onAfterDispatchTouchEvent(ev);
                }
            }));

            try {
                Method absorbGlowsMethod = recyclerViewClass.getDeclaredMethod("absorbGlows", int.class, int.class);
                absorbGlowsMethod.setAccessible(true);
                unhooks.add(XposedBridge.hookMethod(absorbGlowsMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!recyclerListViewClass.isInstance(param.thisObject)) return;

                        ViewGroup listView = (ViewGroup) param.thisObject;
                        int velY = (Integer) param.args[1];
                        if (velY != 0) {
                            OverScrollController controller = getOrCreateController(listView);
                            controller.onFlingHitEdge(-velY);
                            param.setResult(null);
                        }
                    }
                }));
            } catch (Throwable ignored) {
            }

            try {
                Method reqDisallowMethod = recyclerViewClass.getDeclaredMethod("requestDisallowInterceptTouchEvent", boolean.class);
                reqDisallowMethod.setAccessible(true);
                unhooks.add(XposedBridge.hookMethod(reqDisallowMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (recyclerListViewClass.isInstance(param.thisObject)) {
                            ViewGroup listView = (ViewGroup) param.thisObject;
                            boolean disallow = (Boolean) param.args[0];
                            OverScrollController controller = getOrCreateController(listView);
                            controller.setDisallowIntercept(disallow);
                        }
                    }
                }));
            } catch (Throwable ignored) {
            }

            try {
                Method setOverScrollModeMethod = View.class.getDeclaredMethod("setOverScrollMode", int.class);
                setOverScrollModeMethod.setAccessible(true);
                unhooks.add(XposedBridge.hookMethod(setOverScrollModeMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (recyclerListViewClass.isInstance(param.thisObject)) {
                            int mode = (Integer) param.args[0];
                            if (mode == View.OVER_SCROLL_NEVER) {
                                param.args[0] = View.OVER_SCROLL_ALWAYS;
                            }
                        }
                    }
                }));
            } catch (Throwable ignored) {
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static synchronized void stop() {
        for (XC_MethodHook.Unhook unhook : unhooks) {
            try {
                unhook.unhook();
            } catch (Throwable ignored) {
            }
        }
        unhooks.clear();

        synchronized (controllers) {
            for (OverScrollController controller : controllers.values()) {
                if (controller != null) {
                    controller.stopAnimation();
                    controller.resetOffset();
                }
            }
            controllers.clear();
        }
    }
}