/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.plaidapp.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import io.plaidapp.R;
import io.plaidapp.util.ViewOffsetHelper;

/**
 * A {@link FrameLayout} which can be dragged downward to be dismissed (either directly or via a
 * specified nested scrolling child).  It expects to contain a single child view and exposes a
 * listener interface to react to it's dismissal.
 */
public class BottomSheet extends FrameLayout {

    private final int FLING_VELOCITY;
    private ViewDragHelper viewDragHelper;

    // state
    private boolean isDismissing;
    private View dragView;
    private ViewOffsetHelper dragViewOffsetHelper;
    private boolean hasScrollingChild = false;
    private int scrollingChildId = -1;
    private View scrollingChild;
    private int dragViewTop;
    private int dragViewBottom;
    protected List<Listener> listeners;

    public BottomSheet(Context context) {
        this(context, null, 0);
    }

    public BottomSheet(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BottomSheet(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        FLING_VELOCITY = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
        final TypedArray a =
                getContext().obtainStyledAttributes(attrs, R.styleable.BottomSheet, 0, 0);

        if (a.hasValue(R.styleable.BottomSheet_scrollingChild)) {
            hasScrollingChild = true;
            scrollingChildId = a.getResourceId(R.styleable.BottomSheet_scrollingChild,
                    scrollingChildId);
        }
    }

    public void addListener(Listener listener) {
        if (listeners == null) {
            listeners = new ArrayList<>();
        }
        listeners.add(listener);
    }

    public interface Listener {
        void onDragDismissed();
        void onDrag(int top);
    }

    public void doDismiss() {
        viewDragHelper.settleCapturedViewAt(0, dragViewBottom);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (dragView != null) {
            throw new UnsupportedOperationException("BottomSheet must only have 1 child view");
        }
        dragView = child;
        dragViewOffsetHelper = new ViewOffsetHelper(dragView);
        if (hasScrollingChild) {
            scrollingChild = dragView.findViewById(scrollingChildId);
        }
        super.addView(child, index, params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        viewDragHelper = ViewDragHelper.create(this, dragHelperCallbacks);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (dragView != null && dragView.isLaidOut()) {
            dragViewTop = dragView.getTop();
            dragViewBottom = dragView.getBottom();
            dragViewOffsetHelper.onViewLayout();
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = MotionEventCompat.getActionMasked(ev);
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            viewDragHelper.cancel();
            return false;
        }
        return isDraggableViewUnder((int) ev.getX(), (int) ev.getY())
            && (viewDragHelper.shouldInterceptTouchEvent(ev) || super.onInterceptTouchEvent(ev));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        viewDragHelper.processTouchEvent(ev);
        if (viewDragHelper.getCapturedView() == null) {
            return super.onTouchEvent(ev);
        }
        return true;
    }

    @Override
    public void computeScroll() {
        if (viewDragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return (nestedScrollAxes & View.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed) {
        if (dyUnconsumed < 0) {
            dragView.offsetTopAndBottom(-dyUnconsumed);
        }
    }

    @Override
    public void onStopNestedScroll(View child) {
        if (dragView.getTop() == dragViewTop) return;

        ValueAnimator settleAnim = ValueAnimator.ofInt(dragView.getTop(), dragViewTop);
        settleAnim.setDuration(200L);
        settleAnim.setInterpolator(AnimationUtils.loadInterpolator(getContext(),
                android.R.interpolator.fast_out_slow_in));
        settleAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                dragViewOffsetHelper.setTopAndBottomOffset((int) animation.getAnimatedValue());
            }
        });
        settleAnim.start();
    }

    protected void dispatchDismissCallback() {
        if (listeners != null && listeners.size() > 0) {
            for (Listener listener : listeners) {
                listener.onDragDismissed();
            }
        }
    }

    protected void dispatchDragCallback() {
        if (listeners != null && listeners.size() > 0) {
            for (Listener listener : listeners) {
                listener.onDrag(getTop());
            }
        }
    }

    private boolean isDraggableViewUnder(int x, int y) {
        return getVisibility() == VISIBLE
                && viewDragHelper.isViewUnder(this, x, y);
    }

    private ViewDragHelper.Callback dragHelperCallbacks = new ViewDragHelper.Callback() {

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            boolean childCanScroll = scrollingChild != null &&
                    (scrollingChild.canScrollVertically(1)
                            || scrollingChild.canScrollVertically(-1));
            return !childCanScroll;
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            return Math.min(Math.max(top, dragViewTop), dragViewBottom);
        }

        @Override
        public int getViewVerticalDragRange(View child) {
            return dragViewBottom - dragViewTop;
        }

        @Override
        public void onViewPositionChanged(View child, int left, int top, int dx, int dy) {
            dispatchDragCallback();
        }

        @Override
        public void onViewReleased(View releasedChild, float velocityX, float velocityY) {
            if (velocityY >= FLING_VELOCITY) {
                isDismissing = true;
                doDismiss();
            } else {
                // settle back into position
                viewDragHelper.settleCapturedViewAt(0, dragViewTop);
            }
            ViewCompat.postInvalidateOnAnimation(BottomSheet.this);
        }

        @Override
        public void onViewDragStateChanged(int state) {
            if (isDismissing
                    && state == ViewDragHelper.STATE_IDLE) {
                isDismissing = false;
                dispatchDismissCallback();
            }
        }
    };
}
