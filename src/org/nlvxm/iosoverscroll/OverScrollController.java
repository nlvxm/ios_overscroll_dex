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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class OverScrollController {

    public static final int STATE_IDLE = 0;
    public static final int STATE_DRAGGING = 1;
    public static final int STATE_BOUNCING = 2;

    private final ViewGroup listView;
    private final float touchSlop;

    public float offset = 0f;
    public int state = STATE_IDLE;
    public boolean isOverScrolling = false;

    private float downTouchX = 0f;
    private float downTouchY = 0f;
    private float lastTouchY = 0f;
    private float lastTouchX = 0f;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    private VelocityTracker velocityTracker;

    private final List<View> trackedFloatingViews = new ArrayList<>();
    private final List<Float> initialFloatingTranslations = new ArrayList<>();
    private final List<Boolean> isBottomAnchoredList = new ArrayList<>();

    private boolean animating = false;
    private long animStartTime = 0;
    private float x0 = 0f;
    private float v0 = 0f;
    private float c1 = 0f;
    private float c2 = 0f;
    private float omega_d = 0f;

    private long downTime = 0L;
    private boolean isLongPressDrag = false;
    private boolean disallowInterceptHooked = false;

    private static Field groupFlagsField;
    private static Field itemTouchListenersField;
    private static int draggingId = 0;

    static {
        try {
            groupFlagsField = ViewGroup.class.getDeclaredField("mGroupFlags");
            groupFlagsField.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> rId = Class.forName("org.telegram.messenger.R$id");
            Field f = rId.getField("dragging");
            draggingId = f.getInt(null);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> rvClass = Class.forName("androidx.recyclerview.widget.RecyclerView");
            itemTouchListenersField = rvClass.getDeclaredField("mOnItemTouchListeners");
            itemTouchListenersField.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    public OverScrollController(ViewGroup listView) {
        this.listView = listView;
        this.touchSlop = ViewConfiguration.get(listView.getContext()).getScaledTouchSlop();
        listView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {}

            @Override
            public void onViewDetachedFromWindow(View v) {
                stopAnimation();
                resetOffset();
                state = STATE_IDLE;
                isOverScrolling = false;
            }
        });
    }

    private boolean isExcludedScreen(ViewGroup view) {
        if (view == null) return true;
        View v = view;
        while (v != null) {
            String name = v.getClass().getName();
            String simpleName = v.getClass().getSimpleName();
            if (simpleName.contains("SharedMedia") ||
                simpleName.contains("MediaPage") ||
                simpleName.contains("ProfileActivity") ||
                name.contains("ProfileActivity")) {
                return true;
            }
            ViewParent p = v.getParent();
            if (p instanceof View) {
                v = (View) p;
            } else {
                break;
            }
        }
        return false;
    }

    private boolean isMultiselectActive(ViewGroup view) {
        try {
            Method m = view.getClass().getMethod("isMultiselect");
            Object res = m.invoke(view);
            if (Boolean.TRUE.equals(res)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean isActionModeActive(ViewGroup view) {
        ViewParent p = view.getParent();
        if (p instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) p;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child.getClass().getSimpleName().contains("ActionBar")) {
                    try {
                        Method m = child.getClass().getMethod("isActionModeShowed");
                        Object res = m.invoke(child);
                        if (Boolean.TRUE.equals(res)) {
                            return true;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    public void setDisallowIntercept(boolean disallow) {
        this.disallowInterceptHooked = disallow;
        if (disallow && state == STATE_DRAGGING) {
            resetOffset();
            state = STATE_IDLE;
            isOverScrolling = false;
        }
    }

    private boolean isDisallowIntercept() {
        if (disallowInterceptHooked) {
            return true;
        }
        if (groupFlagsField != null) {
            try {
                int flags = groupFlagsField.getInt(listView);
                if ((flags & 0x80000) != 0) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean isItemDragging() {
        try {
            int count = listView.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = listView.getChildAt(i);
                if (child != null) {
                    if (draggingId != 0 && child.getTag(draggingId) != null) {
                        return true;
                    }
                    Object tag = child.getTag();
                    if (tag != null && Boolean.TRUE.equals(tag)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (itemTouchListenersField != null) {
                List<?> listeners = (List<?>) itemTouchListenersField.get(listView);
                if (listeners != null) {
                    for (int i = 0; i < listeners.size(); i++) {
                        Object listener = listeners.get(i);
                        if (listener != null && listener.getClass().getName().contains("ItemTouchHelper")) {
                            Class<?> lCls = listener.getClass();
                            Field this0 = null;
                            try {
                                this0 = lCls.getDeclaredField("this$0");
                                this0.setAccessible(true);
                                Object helper = this0.get(listener);
                                if (helper != null) {
                                    Field sel = helper.getClass().getDeclaredField("mSelected");
                                    sel.setAccessible(true);
                                    if (sel.get(helper) != null) {
                                        return true;
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean isTelegramSwiping() {
        try {
            Class<?> cls = listView.getClass();
            Field this0Field = null;
            while (cls != null && cls != Object.class) {
                try {
                    this0Field = cls.getDeclaredField("this$0");
                    this0Field.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                    cls = cls.getSuperclass();
                }
            }
            if (this0Field != null) {
                Object activity = this0Field.get(listView);
                if (activity != null) {
                    Class<?> actCls = activity.getClass();
                    while (actCls != null && actCls != Object.class) {
                        try {
                            Field pdo = actCls.getDeclaredField("pullingDownOffset");
                            pdo.setAccessible(true);
                            if (Math.abs(pdo.getFloat(activity)) > 0.001f) {
                                return true;
                            }
                        } catch (NoSuchFieldException ignored) {
                        }
                        try {
                            Field pbo = actCls.getDeclaredField("pullingBottomOffset");
                            pbo.setAccessible(true);
                            if (Math.abs(pbo.getFloat(activity)) > 0.001f) {
                                return true;
                            }
                        } catch (NoSuchFieldException ignored) {
                        }
                        try {
                            Field track = actCls.getDeclaredField("startedTrackingSlidingView");
                            track.setAccessible(true);
                            if (track.getBoolean(activity)) {
                                return true;
                            }
                        } catch (NoSuchFieldException ignored) {
                        }
                        actCls = actCls.getSuperclass();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Method m = listView.getClass().getMethod("getViewOffset");
            float vo = ((Number) m.invoke(listView)).floatValue();
            if (Math.abs(vo) > 0.001f) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private void prepareFloatingViews() {
        trackedFloatingViews.clear();
        initialFloatingTranslations.clear();
        isBottomAnchoredList.clear();

        ViewParent parent = listView.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }

        ViewGroup parentVg = (ViewGroup) parent;
        int childCount = parentVg.getChildCount();
        float density = listView.getContext().getResources().getDisplayMetrics().density;

        for (int i = 0; i < childCount; i++) {
            View child = parentVg.getChildAt(i);
            if (child == null || child == listView) {
                continue;
            }

            String simpleName = child.getClass().getSimpleName();
            String fullName = child.getClass().getName();

            if (simpleName.contains("ActionBar") ||
                simpleName.contains("EnterView") ||
                simpleName.contains("ChatInput") ||
                simpleName.contains("TopPanel") ||
                fullName.contains("roundVideoRecordBackground")) {
                continue;
            }

            boolean isFloating = false;
            boolean isBottom = false;

            if (simpleName.contains("ChatActionCell") ||
                simpleName.contains("TopicSeparator") ||
                simpleName.contains("SuggestEmoji") ||
                simpleName.contains("MentionsContainer") ||
                simpleName.contains("FloatingDate") ||
                simpleName.contains("infoTopView") ||
                simpleName.contains("HintView") ||
                simpleName.contains("hint")) {
                isFloating = true;
            } else if (simpleName.contains("SideControls") || simpleName.contains("sideControls")) {
                isFloating = true;
                isBottom = true;
            } else if (child.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
                int gravity = lp.gravity;
                boolean isHorizCenter = (gravity & Gravity.CENTER_HORIZONTAL) == Gravity.CENTER_HORIZONTAL;
                boolean isTop = (gravity & Gravity.TOP) == Gravity.TOP;
                boolean isBottomGravity = (gravity & Gravity.BOTTOM) == Gravity.BOTTOM;

                if (isHorizCenter && isTop && child.getHeight() > 0 && child.getHeight() < 140 * density) {
                    isFloating = true;
                } else if ((isHorizCenter || (gravity & Gravity.RIGHT) == Gravity.RIGHT) && isBottomGravity && child.getHeight() > 0 && child.getHeight() < 100 * density) {
                    isFloating = true;
                    isBottom = true;
                }
            }

            if (isFloating) {
                trackedFloatingViews.add(child);
                initialFloatingTranslations.add(child.getTranslationY());
                isBottomAnchoredList.add(isBottom);
            }
        }
    }

    public void updateOffset(float newOffset) {
        this.offset = newOffset;
        listView.setTranslationY(newOffset);

        for (int i = 0; i < trackedFloatingViews.size(); i++) {
            View fv = trackedFloatingViews.get(i);
            if (fv != null) {
                float initTy = initialFloatingTranslations.get(i);
                boolean isBottom = isBottomAnchoredList.get(i);
                if (isBottom) {
                    if (newOffset < 0f) {
                        fv.setTranslationY(initTy + newOffset);
                    } else {
                        fv.setTranslationY(initTy);
                    }
                } else {
                    fv.setTranslationY(initTy + newOffset);
                }
            }
        }

        ViewParent parent = listView.getParent();
        if (parent instanceof View) {
            ((View) parent).invalidate();
        }
        listView.invalidate();
    }

    public void resetOffset() {
        this.offset = 0f;
        listView.setTranslationY(0f);

        for (int i = 0; i < trackedFloatingViews.size(); i++) {
            View fv = trackedFloatingViews.get(i);
            if (fv != null) {
                float initTy = initialFloatingTranslations.get(i);
                fv.setTranslationY(initTy);
            }
        }
        trackedFloatingViews.clear();
        initialFloatingTranslations.clear();
        isBottomAnchoredList.clear();

        ViewParent parent = listView.getParent();
        if (parent instanceof View) {
            ((View) parent).invalidate();
        }
        listView.invalidate();
    }

    public boolean onBeforeDispatchTouchEvent(MotionEvent ev) {
        if (isExcludedScreen(listView)) {
            return false;
        }

        if (isMultiselectActive(listView) || isActionModeActive(listView)) {
            if (state != STATE_IDLE) {
                resetOffset();
                state = STATE_IDLE;
                isOverScrolling = false;
            }
            return false;
        }

        int action = ev.getActionMasked();

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                if (animating) {
                    stopAnimation();
                    if (Math.abs(offset) > 1.0f) {
                        state = STATE_DRAGGING;
                        isOverScrolling = true;
                        ViewParent p = listView.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        return true;
                    } else {
                        resetOffset();
                        state = STATE_IDLE;
                        isOverScrolling = false;
                    }
                } else {
                    state = STATE_IDLE;
                    isOverScrolling = false;
                }

                activePointerId = ev.getPointerId(0);
                downTouchX = ev.getX();
                downTouchY = ev.getY();
                lastTouchX = downTouchX;
                lastTouchY = downTouchY;
                downTime = SystemClock.uptimeMillis();
                isLongPressDrag = false;
                disallowInterceptHooked = false;

                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                } else {
                    velocityTracker.clear();
                }
                velocityTracker.addMovement(ev);
                return false;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = ev.getActionIndex();
                activePointerId = ev.getPointerId(index);
                lastTouchX = ev.getX(index);
                lastTouchY = ev.getY(index);
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                }
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
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                }
                return state == STATE_DRAGGING;
            }

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = ev.findPointerIndex(activePointerId);
                if (pointerIndex < 0) {
                    break;
                }

                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                }

                float curX = ev.getX(pointerIndex);
                float curY = ev.getY(pointerIndex);
                float dy = curY - lastTouchY;
                float dx = curX - lastTouchX;

                long timeSinceDown = SystemClock.uptimeMillis() - downTime;
                int longPressTimeout = ViewConfiguration.getLongPressTimeout();
                float totalDx = Math.abs(curX - downTouchX);
                float totalDy = Math.abs(curY - downTouchY);

                if (state == STATE_IDLE) {
                    if (!isLongPressDrag && totalDy <= touchSlop && totalDx <= touchSlop && timeSinceDown >= longPressTimeout) {
                        isLongPressDrag = true;
                    }
                }

                if (isLongPressDrag || isDisallowIntercept() || isItemDragging() || isTelegramSwiping()) {
                    if (state != STATE_IDLE) {
                        resetOffset();
                        state = STATE_IDLE;
                        isOverScrolling = false;
                    }
                    lastTouchX = curX;
                    lastTouchY = curY;
                    return false;
                }

                if (state == STATE_DRAGGING) {
                    lastTouchX = curX;
                    lastTouchY = curY;

                    float H = listView.getHeight();
                    if (H <= 0f) H = 1000f;
                    float curIntensity = IOSOverScroll.intensity;

                    boolean returning = (offset > 0f && dy < 0f) || (offset < 0f && dy > 0f);
                    if (returning) {
                        offset += dy * 0.85f;
                        if ((dy < 0f && offset <= 0f) || (dy > 0f && offset >= 0f)) {
                            resetOffset();
                            state = STATE_IDLE;
                            isOverScrolling = false;
                            ViewParent p = listView.getParent();
                            if (p != null) p.requestDisallowInterceptTouchEvent(false);
                            return true;
                        }
                    } else {
                        float resistance = 1.0f / (1.0f + (Math.abs(offset) / (H * 0.42f * curIntensity)));
                        float delta = dy * 0.48f * curIntensity * resistance;
                        offset += delta;

                        float maxOffset = H * 0.45f * curIntensity;
                        if (offset > maxOffset) offset = maxOffset;
                        if (offset < -maxOffset) offset = -maxOffset;
                    }

                    updateOffset(offset);
                    return true;
                }
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
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

                if (isLongPressDrag || isDisallowIntercept() || isItemDragging() || isTelegramSwiping()) {
                    if (state != STATE_IDLE) {
                        resetOffset();
                        state = STATE_IDLE;
                        isOverScrolling = false;
                    }
                    isLongPressDrag = false;
                    disallowInterceptHooked = false;
                    return false;
                }

                isLongPressDrag = false;
                disallowInterceptHooked = false;

                if (state == STATE_DRAGGING || Math.abs(offset) > 0.5f) {
                    startSpring(offset, 0f);
                    isOverScrolling = false;
                    state = STATE_IDLE;
                    return true;
                } else if (state == STATE_IDLE && action == MotionEvent.ACTION_UP) {
                    if (vy > 750f && !listView.canScrollVertically(-1)) {
                        onFlingHitEdge(vy);
                    } else if (vy < -750f && !listView.canScrollVertically(1)) {
                        onFlingHitEdge(vy);
                    }
                }

                state = STATE_IDLE;
                isOverScrolling = false;
                break;
            }
        }

        return false;
    }

    public void onAfterDispatchTouchEvent(MotionEvent ev) {
        if (state != STATE_IDLE) {
            return;
        }

        if (isExcludedScreen(listView)) {
            return;
        }

        if (isMultiselectActive(listView) || isActionModeActive(listView)) {
            return;
        }

        if (ev.getActionMasked() != MotionEvent.ACTION_MOVE) {
            return;
        }

        if (isLongPressDrag || isDisallowIntercept() || isItemDragging() || isTelegramSwiping()) {
            return;
        }

        int pointerIndex = ev.findPointerIndex(activePointerId);
        if (pointerIndex < 0) {
            return;
        }

        float curX = ev.getX(pointerIndex);
        float curY = ev.getY(pointerIndex);
        float totalDx = Math.abs(curX - downTouchX);
        float totalDy = Math.abs(curY - downTouchY);

        if (totalDx > totalDy && totalDx > touchSlop) {
            lastTouchX = curX;
            lastTouchY = curY;
            return;
        }

        long timeSinceDown = SystemClock.uptimeMillis() - downTime;
        int longPressTimeout = ViewConfiguration.getLongPressTimeout();
        if (timeSinceDown >= longPressTimeout && totalDy <= touchSlop && totalDx <= touchSlop) {
            isLongPressDrag = true;
            lastTouchX = curX;
            lastTouchY = curY;
            return;
        }

        if (isLongPressDrag) {
            return;
        }

        boolean atTop = !listView.canScrollVertically(-1);
        boolean atBottom = !listView.canScrollVertically(1);
        float dy = curY - lastTouchY;

        if (atTop && dy > 0 && totalDy > touchSlop) {
            state = STATE_DRAGGING;
            isOverScrolling = true;
            prepareFloatingViews();
            cancelChildPress(ev);
            ViewParent p = listView.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);

            float H = listView.getHeight();
            if (H <= 0f) H = 1000f;
            float curIntensity = IOSOverScroll.intensity;
            float delta = dy * 0.48f * curIntensity;
            offset += delta;
            updateOffset(offset);
        } else if (atBottom && dy < 0 && totalDy > touchSlop) {
            state = STATE_DRAGGING;
            isOverScrolling = true;
            prepareFloatingViews();
            cancelChildPress(ev);
            ViewParent p = listView.getParent();
            if (p != null) p.requestDisallowInterceptTouchEvent(true);

            float H = listView.getHeight();
            if (H <= 0f) H = 1000f;
            float curIntensity = IOSOverScroll.intensity;
            float delta = dy * 0.48f * curIntensity;
            offset += delta;
            updateOffset(offset);
        }

        lastTouchX = curX;
        lastTouchY = curY;
    }

    public void onFlingHitEdge(float velocityY) {
        if (isExcludedScreen(listView)) {
            return;
        }
        if (isLongPressDrag || isDisallowIntercept() || isItemDragging() || isTelegramSwiping()) {
            return;
        }

        prepareFloatingViews();

        float flingFactor = 0.25f * IOSOverScroll.flingIntensity;
        float scaledV0 = velocityY * flingFactor;

        float maxV0 = 6500.0f * IOSOverScroll.flingIntensity;
        if (scaledV0 > maxV0) scaledV0 = maxV0;
        if (scaledV0 < -maxV0) scaledV0 = -maxV0;

        startSpring(offset, scaledV0);
    }

    public void startSpring(float startOffset, float initialVelocity) {
        if (Math.abs(startOffset) < 0.1f && Math.abs(initialVelocity) < 15f) {
            resetOffset();
            animating = false;
            state = STATE_IDLE;
            isOverScrolling = false;
            return;
        }

        animating = true;
        state = STATE_BOUNCING;
        animStartTime = SystemClock.uptimeMillis();
        this.x0 = startOffset;
        this.v0 = initialVelocity;

        float omega0 = IOSOverScroll.springOmega;
        float zeta = 0.85f;
        this.omega_d = (float) (omega0 * Math.sqrt(1.0 - (zeta * zeta)));
        this.c1 = x0;
        this.c2 = (v0 + zeta * omega0 * x0) / omega_d;

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
            long now = SystemClock.uptimeMillis();
            float t = (now - animStartTime) / 1000.0f;

            float omega0 = IOSOverScroll.springOmega;
            float zeta = 0.85f;
            float decay = (float) Math.exp(-zeta * omega0 * t);
            float x = decay * (c1 * (float) Math.cos(omega_d * t) + c2 * (float) Math.sin(omega_d * t));

            if (t > 0.08f && Math.abs(x) < 0.5f && decay < 0.08f) {
                resetOffset();
                animating = false;
                state = STATE_IDLE;
                isOverScrolling = false;
                return;
            }

            updateOffset(x);
            listView.postOnAnimation(this);
        }
    };

    private void cancelChildPress(MotionEvent ev) {
        try {
            Method m = listView.getClass().getMethod("cancelClickRunnables", boolean.class);
            m.invoke(listView, true);
        } catch (Throwable ignored) {
        }
        try {
            listView.cancelLongPress();
        } catch (Throwable ignored) {
        }
        if (ev != null) {
            MotionEvent cancelEvent = MotionEvent.obtain(ev.getDownTime(), ev.getEventTime(), MotionEvent.ACTION_CANCEL, ev.getX(), ev.getY(), 0);
            int count = listView.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = listView.getChildAt(i);
                if (child != null) {
                    try {
                        child.dispatchTouchEvent(cancelEvent);
                    } catch (Throwable ignored) {
                    }
                    try {
                        child.cancelLongPress();
                    } catch (Throwable ignored) {
                    }
                }
            }
            cancelEvent.recycle();
        }
    }
}