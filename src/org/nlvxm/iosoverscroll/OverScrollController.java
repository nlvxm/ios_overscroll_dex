package org.nlvxm.iosoverscroll;

import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class OverScrollController {

    public static final int STATE_IDLE = 0;
    public static final int STATE_DRAGGING = 1;

    private static final float RUBBER_C = 0.55f;

    private static Field groupFlagsField, itemTouchListenersField, itemTouchHelperField, itemTouchHelperSelectedField;
    private static int draggingId = 0;
    private static Method multiselectMethod, actionModeMethod, cancelClickMethod;

    static {
        try {
            (groupFlagsField = ViewGroup.class.getDeclaredField("mGroupFlags")).setAccessible(true);
        } catch (Throwable ignored) {}
        try {
            draggingId = Class.forName("org.telegram.messenger.R$id").getField("dragging").getInt(null);
        } catch (Throwable ignored) {}
        try {
            (itemTouchListenersField = Class.forName("androidx.recyclerview.widget.RecyclerView").getDeclaredField("mOnItemTouchListeners")).setAccessible(true);
        } catch (Throwable ignored) {}
    }

    private final ViewGroup listView;
    private final float touchSlop;

    public float offset = 0f;
    private float pull = 0f;
    public int state = STATE_IDLE;
    private float baseTranslationY = 0f;

    private float downTouchX, downTouchY, lastTouchX, lastTouchY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private long downTime;
    private boolean isLongPressDrag, disallowInterceptHooked, animating;
    private VelocityTracker velocityTracker;

    private static class FloatingView {
        View view;
        float initY;
        boolean bottom;
    }
    private final List<FloatingView> floatingViews = new ArrayList<>();

    private long animStartTime = 0;
    private float c1 = 0f, c2 = 0f;
    private float zo = 14.04f, od = 13.5325f;

    private Boolean isExcluded;
    private boolean swipingReflectInit = false;
    private WeakReference<Object> enclosingActivityRef;
    private Field pdoField, pboField, trackField;
    private Method getViewOffsetMethod;

    public OverScrollController(ViewGroup listView) {
        this.listView = listView;
        this.touchSlop = ViewConfiguration.get(listView.getContext()).getScaledTouchSlop();
        this.baseTranslationY = listView.getTranslationY();
        try {
            Method m = listView.getClass().getMethod("setGlowColor", int.class);
            m.invoke(listView, 0);
        } catch (Throwable ignored) {}
        listView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}
            @Override
            public void onViewDetachedFromWindow(View v) {
                reset();
            }
        });
    }

    private float rubberMax() {
        float h = listView.getHeight();
        return (h > 0f ? h : 1000f) * 0.45f * IOSOverScroll.intensity;
    }

    private float rubberC() {
        return RUBBER_C * IOSOverScroll.intensity;
    }

    private float pullToOffset(float p) {
        float d = rubberMax(), c = rubberC();
        return p * c * d / (Math.abs(p) * c + d);
    }

    private float offsetToPull(float o) {
        float d = rubberMax(), c = rubberC();
        float a = Math.min(Math.abs(o), d * 0.999f);
        return Math.signum(o) * a * d / (c * (d - a));
    }

    private float rubberSlope() {
        float k = 1f - Math.min(Math.abs(offset) / rubberMax(), 1f);
        return rubberC() * k * k;
    }

    public boolean isExcludedScreen() {
        if (isExcluded == null) {
            for (ViewParent p = listView; p instanceof View; p = p.getParent()) {
                String n = p.getClass().getName();
                if (n.contains("SharedMedia") || n.contains("MediaPage") || n.contains("ProfileActivity")) {
                    return isExcluded = true;
                }
            }
            if (listView.getParent() != null) isExcluded = false;
            else return false;
        }
        return isExcluded;
    }

    private boolean isSelectionActive() {
        try {
            if (multiselectMethod == null) multiselectMethod = listView.getClass().getMethod("isMultiselect");
            if (Boolean.TRUE.equals(multiselectMethod.invoke(listView))) return true;
        } catch (Throwable ignored) {}

        ViewParent p = listView.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) p;
            for (int i = 0, count = vg.getChildCount(); i < count; i++) {
                View child = vg.getChildAt(i);
                if (child != null && child.getClass().getSimpleName().contains("ActionBar")) {
                    try {
                        if (actionModeMethod == null) actionModeMethod = child.getClass().getMethod("isActionModeShowed");
                        if (Boolean.TRUE.equals(actionModeMethod.invoke(child))) return true;
                    } catch (Throwable ignored) {}
                }
            }
        }
        return false;
    }

    public void setDisallowIntercept(boolean disallow) {
        this.disallowInterceptHooked = disallow;
        if (disallow && state == STATE_DRAGGING) reset();
    }

    private boolean isDisallowIntercept() {
        if (disallowInterceptHooked) return true;
        try {
            if (groupFlagsField != null && (groupFlagsField.getInt(listView) & 0x80000) != 0) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isItemDragging() {
        try {
            for (int i = 0, count = listView.getChildCount(); i < count; i++) {
                View child = listView.getChildAt(i);
                if (child != null && ((draggingId != 0 && child.getTag(draggingId) != null) || Boolean.TRUE.equals(child.getTag()))) {
                    return true;
                }
            }
            if (itemTouchListenersField != null) {
                List<?> listeners = (List<?>) itemTouchListenersField.get(listView);
                if (listeners != null) {
                    for (int i = 0, size = listeners.size(); i < size; i++) {
                        Object listener = listeners.get(i);
                        if (listener != null && listener.getClass().getName().contains("ItemTouchHelper")) {
                            if (itemTouchHelperField == null) {
                                (itemTouchHelperField = listener.getClass().getDeclaredField("this$0")).setAccessible(true);
                            }
                            Object helper = itemTouchHelperField.get(listener);
                            if (helper != null) {
                                if (itemTouchHelperSelectedField == null) {
                                    (itemTouchHelperSelectedField = helper.getClass().getDeclaredField("mSelected")).setAccessible(true);
                                }
                                if (itemTouchHelperSelectedField.get(helper) != null) return true;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void initSwipingReflect() {
        if (swipingReflectInit) return;
        swipingReflectInit = true;
        try {
            for (Class<?> c = listView.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField("this$0");
                    f.setAccessible(true);
                    Object owner = f.get(listView);
                    if (owner != null) enclosingActivityRef = new WeakReference<>(owner);
                    break;
                } catch (Throwable ignored) {}
            }
            Object enclosingActivity = enclosingActivityRef != null ? enclosingActivityRef.get() : null;
            if (enclosingActivity != null) {
                for (Class<?> c = enclosingActivity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    try { (pdoField = c.getDeclaredField("pullingDownOffset")).setAccessible(true); } catch (Throwable ignored) {}
                    try { (pboField = c.getDeclaredField("pullingBottomOffset")).setAccessible(true); } catch (Throwable ignored) {}
                    try { (trackField = c.getDeclaredField("startedTrackingSlidingView")).setAccessible(true); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try {
            (getViewOffsetMethod = listView.getClass().getMethod("getViewOffset")).setAccessible(true);
        } catch (Throwable ignored) {}
    }

    private boolean isTelegramSwiping() {
        initSwipingReflect();
        try {
            Object enclosingActivity = enclosingActivityRef != null ? enclosingActivityRef.get() : null;
            if (enclosingActivity != null) {
                if (pdoField != null && Math.abs(pdoField.getFloat(enclosingActivity)) > 0.001f) return true;
                if (pboField != null && Math.abs(pboField.getFloat(enclosingActivity)) > 0.001f) return true;
                if (trackField != null && trackField.getBoolean(enclosingActivity)) return true;
            }
            if (getViewOffsetMethod != null && Math.abs(((Number) getViewOffsetMethod.invoke(listView)).floatValue()) > 0.001f) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean isGestureBlocked() {
        return isLongPressDrag || isDisallowIntercept() || isItemDragging() || isTelegramSwiping();
    }

    private void prepareFloatingViews() {
        floatingViews.clear();
        ViewParent parent = listView.getParent();
        if (!(parent instanceof ViewGroup)) return;

        ViewGroup vg = (ViewGroup) parent;
        float density = listView.getContext().getResources().getDisplayMetrics().density;

        for (int i = 0, count = vg.getChildCount(); i < count; i++) {
            View child = vg.getChildAt(i);
            if (child == null || child == listView) continue;

            String name = child.getClass().getName();
            String simple = child.getClass().getSimpleName();
            if (simple.contains("ActionBar") || simple.contains("EnterView") ||
                simple.contains("ChatInput") || simple.contains("TopPanel") ||
                simple.contains("MentionsContainer") || simple.contains("SuggestEmoji") ||
                simple.contains("BotCommandsMenu") ||
                name.contains("roundVideoRecordBackground")) {
                continue;
            }

            boolean isFloating = false, isBottom = false;
            if (simple.contains("ChatActionCell") || simple.contains("TopicSeparator") ||
                simple.contains("FloatingDate") || simple.contains("infoTopView") ||
                simple.contains("HintView") || simple.contains("hint")) {
                isFloating = true;
            } else if (simple.contains("SideControls") || simple.contains("sideControls")) {
                isFloating = isBottom = true;
            } else if (child.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                int g = ((FrameLayout.LayoutParams) child.getLayoutParams()).gravity;
                boolean centerH = (g & Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL;
                int h = child.getHeight();
                if (centerH && (g & Gravity.TOP) == Gravity.TOP && h > 0 && h < 140 * density) {
                    isFloating = true;
                } else if ((centerH || (g & Gravity.RIGHT) == Gravity.RIGHT) && (g & Gravity.BOTTOM) == Gravity.BOTTOM && h > 0 && h < 100 * density) {
                    isFloating = isBottom = true;
                }
            }

            if (isFloating) {
                FloatingView fv = new FloatingView();
                fv.view = child;
                fv.initY = child.getTranslationY();
                fv.bottom = isBottom;
                floatingViews.add(fv);
            }
        }
    }

    public void updateOffset(float newOffset) {
        this.offset = newOffset;
        listView.setTranslationY(baseTranslationY + newOffset);
        for (int i = 0, size = floatingViews.size(); i < size; i++) {
            FloatingView fv = floatingViews.get(i);
            fv.view.setTranslationY(fv.initY + (fv.bottom ? Math.min(0f, newOffset) : newOffset));
        }
        applyZoom(newOffset);
        invalidateViews();
    }

    public void resetOffset() {
        this.offset = 0f;
        this.pull = 0f;
        listView.setTranslationY(baseTranslationY);
        for (int i = 0, size = floatingViews.size(); i < size; i++) {
            FloatingView fv = floatingViews.get(i);
            fv.view.setTranslationY(fv.initY);
        }
        resetZoom();
        floatingViews.clear();
        invalidateViews();
    }

    private void applyZoom(float off) {
        if ((IOSOverScroll.zoomIntensity <= 0f && IOSOverScroll.stretchIntensity <= 0f) || Math.abs(off) < 1.0f) {
            resetZoom();
            return;
        }
        float h = listView.getHeight();
        if (h <= 0f) h = 1000f;
        float progress = Math.min(1.0f, Math.abs(off) / (h * 0.45f));
        if (progress <= 0f) {
            resetZoom();
            return;
        }

        float zm = IOSOverScroll.zoomIntensity;
        float str = IOSOverScroll.stretchIntensity;
        float density = listView.getResources().getDisplayMetrics().density;
        float camDist = density * 8000f;
        float pivotY = off > 0f ? 0f : h;
        float pivotX = listView.getWidth() * 0.5f;

        float rot = zm > 0f ? Math.max(-6.5f, Math.min(6.5f, -(off / (h * 0.45f)) * zm * 4.5f)) : 0f;
        float containerScale = zm > 0f ? Math.max(0.85f, 1.0f - progress * zm * 0.04f) : 1.0f;
        float containerStretchY = str > 0f ? (1.0f + progress * str * 0.05f) : 1.0f;

        listView.setCameraDistance(camDist);
        listView.setPivotX(pivotX);
        listView.setPivotY(pivotY);
        listView.setScaleX(containerScale);
        listView.setScaleY(containerScale * containerStretchY);
        listView.setRotationX(rot);

        float k = progress * zm * 0.06f;
        for (int i = 0, count = listView.getChildCount(); i < count; i++) {
            View v = listView.getChildAt(i);
            if (v != null) {
                float dist = off > 0f ? (v.getTop() / h) : ((h - v.getBottom()) / h);
                dist = Math.max(0f, Math.min(1f, dist));

                float sx = 1.0f;
                float sy = 1.0f;
                if (zm > 0f) {
                    float s = Math.max(0.75f, 1.0f - k * (0.25f + 0.75f * dist));
                    sx = s;
                    sy = s;
                }

                float extraY = 0f;
                if (str > 0f) {
                    extraY = off * (0.30f * str) * (dist * dist * 0.7f + dist * 0.3f);
                    sy *= (1.0f + progress * str * 0.04f * (1.0f - 0.3f * dist));
                }

                v.setPivotX(v.getWidth() * 0.5f);
                v.setPivotY(off > 0f ? 0f : v.getHeight());
                v.setScaleX(sx);
                v.setScaleY(sy);
                v.setTranslationY(extraY);
            }
        }

        for (int i = 0, size = floatingViews.size(); i < size; i++) {
            View v = floatingViews.get(i).view;
            if (v != null) {
                float dist = off > 0f ? (v.getTop() / h) : ((h - v.getBottom()) / h);
                dist = Math.max(0f, Math.min(1f, dist));

                float sx = 1.0f;
                float sy = 1.0f;
                if (zm > 0f) {
                    float s = Math.max(0.75f, 1.0f - k * (0.25f + 0.75f * dist));
                    sx = s * containerScale;
                    sy = s * containerScale;
                }

                if (str > 0f) {
                    sy *= containerStretchY;
                }

                v.setCameraDistance(camDist);
                v.setPivotX(v.getWidth() * 0.5f);
                v.setPivotY(pivotY);
                v.setScaleX(sx);
                v.setScaleY(sy);
                v.setRotationX(rot);
            }
        }
    }

    private void resetZoom() {
        if (listView.getScaleX() != 1.0f || listView.getScaleY() != 1.0f || listView.getRotationX() != 0f) {
            listView.setScaleX(1.0f);
            listView.setScaleY(1.0f);
            listView.setRotationX(0f);
        }
        for (int i = 0, count = listView.getChildCount(); i < count; i++) {
            View v = listView.getChildAt(i);
            if (v != null) {
                if (v.getScaleX() != 1.0f || v.getScaleY() != 1.0f) {
                    v.setScaleX(1.0f);
                    v.setScaleY(1.0f);
                }
                if (v.getTranslationY() != 0f) {
                    v.setTranslationY(0f);
                }
            }
        }
        for (int i = 0, size = floatingViews.size(); i < size; i++) {
            View v = floatingViews.get(i).view;
            if (v != null && (v.getScaleX() != 1.0f || v.getScaleY() != 1.0f || v.getRotationX() != 0f)) {
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setRotationX(0f);
            }
        }
    }

    private void invalidateViews() {
        ViewParent p = listView.getParent();
        if (p instanceof View) ((View) p).invalidate();
        listView.invalidate();
    }

    public void reset() {
        stopAnimation();
        resetOffset();
        state = STATE_IDLE;
    }

    public boolean onBeforeDispatchTouchEvent(MotionEvent ev) {
        if (isExcludedScreen()) return false;
        if (isSelectionActive()) {
            if (state != STATE_IDLE) reset();
            return false;
        }

        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                boolean caught = animating && Math.abs(offset) > touchSlop;
                if (animating) {
                    stopAnimation();
                    if (!caught) reset();
                }
                state = caught ? STATE_DRAGGING : STATE_IDLE;
                if (!caught) {
                    baseTranslationY = listView.getTranslationY();
                }

                activePointerId = ev.getPointerId(0);
                downTouchX = lastTouchX = ev.getX();
                downTouchY = lastTouchY = ev.getY();
                downTime = SystemClock.uptimeMillis();
                isLongPressDrag = disallowInterceptHooked = false;

                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(ev);

                if (caught) {
                    pull = offsetToPull(offset);
                    ViewParent p = listView.getParent();
                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                return false;

            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = ev.getActionIndex();
                activePointerId = ev.getPointerId(index);
                lastTouchX = ev.getX(index);
                lastTouchY = ev.getY(index);
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return state == STATE_DRAGGING;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                int index = ev.getActionIndex();
                if (ev.getPointerId(index) == activePointerId) {
                    int newIndex = (index == 0) ? 1 : 0;
                    if (newIndex < ev.getPointerCount()) {
                        activePointerId = ev.getPointerId(newIndex);
                        lastTouchX = ev.getX(newIndex);
                        lastTouchY = ev.getY(newIndex);
                    }
                }
                if (velocityTracker != null) velocityTracker.addMovement(ev);
                return state == STATE_DRAGGING;
            }

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex < 0) break;
                if (velocityTracker != null) velocityTracker.addMovement(ev);

                float curX = ev.getX(pointerIndex);
                float curY = ev.getY(pointerIndex);
                float dy = curY - lastTouchY;

                if (state == STATE_IDLE && !isLongPressDrag) {
                    if (Math.abs(curY - downTouchY) <= touchSlop && Math.abs(curX - downTouchX) <= touchSlop &&
                        SystemClock.uptimeMillis() - downTime >= ViewConfiguration.getLongPressTimeout()) {
                        isLongPressDrag = true;
                    }
                }

                if (isGestureBlocked()) {
                    if (state != STATE_IDLE) reset();
                    lastTouchX = curX;
                    lastTouchY = curY;
                    return false;
                }

                if (state == STATE_DRAGGING) {
                    lastTouchX = curX;
                    lastTouchY = curY;

                    float prevOffset = offset;
                    if ((prevOffset > 0f && dy < 0f) || (prevOffset < 0f && dy > 0f)) {
                        offset += dy;
                        if ((prevOffset > 0f && offset <= 0f) || (prevOffset < 0f && offset >= 0f)) {
                            reset();
                            ViewParent p = listView.getParent();
                            if (p != null) p.requestDisallowInterceptTouchEvent(false);
                            MotionEvent down = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), MotionEvent.ACTION_DOWN, curX, curY, 0);
                            listView.dispatchTouchEvent(down);
                            down.recycle();
                            return false;
                        }
                        pull = offsetToPull(offset);
                        updateOffset(offset);
                        return true;
                    }

                    pull += dy;
                    updateOffset(pullToOffset(pull));
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float vy = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000);
                    vy = velocityTracker.getYVelocity(activePointerId);
                    velocityTracker.recycle();
                    velocityTracker = null;
                }

                ViewParent p = listView.getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(false);

                if (isGestureBlocked()) {
                    if (state != STATE_IDLE) reset();
                    isLongPressDrag = disallowInterceptHooked = false;
                    return false;
                }

                isLongPressDrag = disallowInterceptHooked = false;

                if ((offset > 0.5f && vy < -150f && listView.canScrollVertically(1)) ||
                    (offset < -0.5f && vy > 150f && listView.canScrollVertically(-1))) {
                    reset();
                    try {
                        Method flingMethod = listView.getClass().getMethod("fling", int.class, int.class);
                        flingMethod.invoke(listView, 0, (int) -vy);
                    } catch (Throwable ignored) {}
                    state = STATE_IDLE;
                    return true;
                }

                if (state == STATE_DRAGGING || Math.abs(offset) > 0.5f) {
                    float v = Math.max(-5000f, Math.min(vy * rubberSlope(), 5000f));
                    startSpring(offset, v);
                    state = STATE_IDLE;
                    return true;
                }
                state = STATE_IDLE;
                break;
        }
        return false;
    }

    public void onAfterDispatchTouchEvent(MotionEvent ev) {
        if (state != STATE_IDLE || isExcludedScreen() || isSelectionActive()) return;
        if (ev.getActionMasked() != MotionEvent.ACTION_MOVE || isGestureBlocked()) return;

        int pointerIndex = ev.findPointerIndex(activePointerId);
        if (pointerIndex < 0) return;

        float curX = ev.getX(pointerIndex);
        float curY = ev.getY(pointerIndex);
        float totalDx = Math.abs(curX - downTouchX);
        float totalDy = Math.abs(curY - downTouchY);

        if (totalDx > totalDy && totalDx > touchSlop) {
            lastTouchX = curX;
            lastTouchY = curY;
            return;
        }

        if (SystemClock.uptimeMillis() - downTime >= ViewConfiguration.getLongPressTimeout() && totalDy <= touchSlop && totalDx <= touchSlop) {
            isLongPressDrag = true;
            lastTouchX = curX;
            lastTouchY = curY;
            return;
        }

        boolean atTop = !listView.canScrollVertically(-1);
        boolean atBottom = !listView.canScrollVertically(1);
        float dy = curY - lastTouchY;

        if (((atTop && dy > 0) || (atBottom && dy < 0)) && totalDy > touchSlop) {
            state = STATE_DRAGGING;
            baseTranslationY = listView.getTranslationY() - offset;
            prepareFloatingViews();
            cancelChildPress(ev);
            ViewParent p = listView.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);

            pull = dy;
            updateOffset(pullToOffset(pull));
        }

        lastTouchX = curX;
        lastTouchY = curY;
    }

    public void onFlingHitEdge(float velocityY) {
        if (isExcludedScreen() || isGestureBlocked()) return;

        prepareFloatingViews();
        baseTranslationY = listView.getTranslationY() - offset;

        float scaledV0 = velocityY * (0.25f * IOSOverScroll.flingIntensity);
        float maxV0 = 6500.0f * IOSOverScroll.flingIntensity;
        scaledV0 = Math.max(-maxV0, Math.min(scaledV0, maxV0));

        startSpring(offset, scaledV0);
    }

    public void startSpring(float startOffset, float initialVelocity) {
        if (Math.abs(startOffset) < 0.1f && Math.abs(initialVelocity) < 15f) {
            reset();
            return;
        }

        animating = true;
        animStartTime = SystemClock.uptimeMillis();
        this.c1 = startOffset;
        this.zo = IOSOverScroll.zetaOmega;
        this.od = IOSOverScroll.omegaD;
        this.c2 = (initialVelocity + zo * startOffset) / od;

        listView.removeCallbacks(animationRunnable);
        listView.postOnAnimation(animationRunnable);
    }

    public void stopAnimation() {
        if (animating) {
            animating = false;
            listView.removeCallbacks(animationRunnable);
        }
    }

    private final Runnable animationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;
            float t = (SystemClock.uptimeMillis() - animStartTime) / 1000.0f;
            float decay = (float) Math.exp(-zo * t);
            float cos = (float) Math.cos(od * t), sin = (float) Math.sin(od * t);
            float x = decay * (c1 * cos + c2 * sin);
            float v = decay * ((od * c2 - zo * c1) * cos - (od * c1 + zo * c2) * sin);

            if (Math.abs(x) < 0.8f && Math.abs(v) < 25f) {
                reset();
                return;
            }

            updateOffset(x);
            listView.postOnAnimation(this);
        }
    };

    private void cancelChildPress(MotionEvent ev) {
        try {
            if (cancelClickMethod == null) {
                cancelClickMethod = listView.getClass().getMethod("cancelClickRunnables", boolean.class);
            }
            cancelClickMethod.invoke(listView, true);
        } catch (Throwable ignored) {}
        try {
            listView.cancelLongPress();
        } catch (Throwable ignored) {}
        if (ev != null) {
            MotionEvent cancel = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), MotionEvent.ACTION_CANCEL, ev.getX(), ev.getY(), 0);
            for (int i = 0, count = listView.getChildCount(); i < count; i++) {
                View child = listView.getChildAt(i);
                if (child != null) {
                    try { child.dispatchTouchEvent(cancel); } catch (Throwable ignored) {}
                    try { child.cancelLongPress(); } catch (Throwable ignored) {}
                }
            }
            cancel.recycle();
        }
    }
}