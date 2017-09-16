/*
 * Copyright (C) 2011 The Android Open Source Project
 * Modifications Copyright (C) 2017 Samuel Pullman
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

package com.sampullman.pager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.support.v4.view.KeyEventCompat;
import android.support.v4.view.MotionEventCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.VelocityTrackerCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.ViewConfigurationCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;
import android.widget.Scroller;
import java.util.ArrayList;

public class StreamViewPager extends ViewGroup {
    private static final String TAG = "ViewPager";
    private static final boolean DEBUG = true;
    private static final boolean USE_CACHE = false;
    private static final int DEFAULT_OFFSCREEN_PAGES = 1;
    private static final int MAX_SETTLE_DURATION = 600; // ms
    private static final int MIN_DISTANCE_FOR_FLING = 25; // dips
    private static final int DEFAULT_GUTTER_SIZE = 16; // dips
    static class ItemInfo {
        Object object;
        int position;
        boolean scrolling;
        float widthFactor;
        float offset;
    }

    private static final Interpolator interpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };
    private final ArrayList<ItemInfo> items = new ArrayList<>();
    private final ItemInfo tempItem = new ItemInfo();
    private final Rect tempRect = new Rect();
    private PagerAdapter adapter;
    private int curItem;   // Index of currently displayed page.
    private Scroller scroller;
    private int pageMargin;
    private Drawable marginDrawable;
    private int topPageBounds;
    private int bottomPageBounds;
    // Offsets of the first and last items, if known.
    // Set during population, used to determine if we are at the beginning
    // or end of the pager data set during touch scrolling.
    private float firstOffset = -Float.MAX_VALUE;
    private float lastOffset = Float.MAX_VALUE;
    private boolean inLayout;
    private boolean scrollingCacheEnabled;
    private boolean populatePending;
    private int offscreenPageLimit = DEFAULT_OFFSCREEN_PAGES;
    private boolean isBeingDragged;
    private boolean isUnableToDrag;
    private int defaultGutterSize;
    private int gutterSize;
    private int touchSlop;
    private float initialMotionX;

    // Position of the last motion event.
    private float lastMotionX;
    private float lastMotionY;

    // ID of active pointer for consistency during drags/flings if multiple pointers are used.
    private int activePointerId = INVALID_POINTER;
    // Sentinel value for no current active pointer used by {@link #activePointerId}.
    private static final int INVALID_POINTER = -1;

    // Determines speed during touch scrolling
    private VelocityTracker velocityTracker;
    private int minimumVelocity;
    private int maximumVelocity;
    private int flingDistance;
    private int closeEnough;

    // If the pager is at least this close to its final position, complete the scroll
    // on touch down and let the user interact with the content inside instead of
    // "catching" the flinging pager.
    private static final int CLOSE_ENOUGH = 2; // dp
    private EdgeEffectCompat leftEdge;
    private EdgeEffectCompat rightEdge;
    private boolean firstLayout = true;
    private boolean calledSuper;
    private OnPageChangeListener pageChangeListener;

    // Indicates that the pager is fully in view and no animation is in progress.
    public static final int SCROLL_STATE_IDLE = 0;

    // Indicates that the pager is currently being dragged by the user.
    public static final int SCROLL_STATE_DRAGGING = 1;

    // Indicates that the pager is settling to a final position.
    public static final int SCROLL_STATE_SETTLING = 2;
    private int scrollState = SCROLL_STATE_IDLE;

    public StreamViewPager(Context context) {
        super(context);
        initViewPager();
    }
    public StreamViewPager(Context context, AttributeSet attrs) {
        super(context, attrs);
        initViewPager();
    }
    void initViewPager() {
        setWillNotDraw(false);
        setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        setFocusable(true);
        final Context context = getContext();
        scroller = new Scroller(context, interpolator);
        final ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = ViewConfigurationCompat.getScaledPagingTouchSlop(configuration);
        minimumVelocity = configuration.getScaledMinimumFlingVelocity();
        maximumVelocity = configuration.getScaledMaximumFlingVelocity();
        leftEdge = new EdgeEffectCompat(context);
        rightEdge = new EdgeEffectCompat(context);
        final float density = context.getResources().getDisplayMetrics().density;
        flingDistance = (int) (MIN_DISTANCE_FOR_FLING * density);
        closeEnough = (int) (CLOSE_ENOUGH * density);
        defaultGutterSize = (int) (DEFAULT_GUTTER_SIZE * density);
        ViewCompat.setAccessibilityDelegate(this, new MyAccessibilityDelegate(this));
        if (ViewCompat.getImportantForAccessibility(this)
                == ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            ViewCompat.setImportantForAccessibility(this,
                    ViewCompat.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }
    private void setScrollState(int newState) {
        if (this.scrollState == newState) {
            return;
        }
        this.scrollState = newState;
        if (pageChangeListener != null) {
            pageChangeListener.onPageScrollStateChanged(newState);
        }
    }

    /** Set a PagerAdapter that will supply views for this pager as needed. */
    public void setAdapter(PagerAdapter adapter) {
        if (this.adapter != null) {
            for (int i = 0; i < items.size(); i++) {
                final ItemInfo ii = items.get(i);
                this.adapter.destroyItem(this, ii.position, ii.object);
            }
            items.clear();
            removeAllViews();
            curItem = 0;
            scrollTo(0, 0);
        }
        this.adapter = adapter;
        if (this.adapter != null) {
            populatePending = false;
            firstLayout = true;
            populate();
        }
    }

    public PagerAdapter getAdapter() {
        return adapter;
    }

    public int getCurrentItem() {
        return curItem;
    }

    /** Set the currently selected page. If the first layout has already occured,
     * there will be a smooth animated transition between the current item and the new item.
     *
     * @param item Item index to select
     */
    public void setCurrentItem(int item) {
        setCurrentItem(item, !firstLayout);
    }

    /** Set the currently selected page.
     *
     * @param item Item index to select
     * @param smoothScroll True to smoothly scroll to the new item, false to transition immediately
     */
    public void setCurrentItem(int item, boolean smoothScroll) {
        populatePending = false;
        setCurrentItemInternal(item, smoothScroll, false);
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always) {
        setCurrentItemInternal(item, smoothScroll, always, 0);
    }

    void setCurrentItemInternal(int item, boolean smoothScroll, boolean always, int velocity) {
        if (adapter == null || adapter.getCount() <= 0) {
            setScrollingCacheEnabled(false);
            return;
        }
        if (!always && curItem == item && items.size() != 0) {
            setScrollingCacheEnabled(false);
            return;
        }
        if (item < 0) {
            item = 0;
        } else if (item >= adapter.getCount()) {
            item = adapter.getCount() - 1;
        }
        final int pageLimit = offscreenPageLimit;
        if (item > (curItem + pageLimit) || item < (curItem - pageLimit)) {
            // We are doing a jump by more than one page.  To avoid
            // glitches, we want to keep all current pages in the view
            // until the scroll ends.
            for (int i = 0; i< items.size(); i++) {
                items.get(i).scrolling = true;
            }
        }
        final boolean dispatchSelected = curItem != item;
        populate(item);
        final ItemInfo curInfo = infoForPosition(item);
        int destX = 0;
        if (curInfo != null) {
            final int width = getWidth();
            destX = (int) (width * Math.max(firstOffset,
                    Math.min(curInfo.offset, lastOffset)));
        }
        if (smoothScroll) {
            smoothScrollTo(destX, 0, velocity);
            if (dispatchSelected && pageChangeListener != null) {
                pageChangeListener.onPageSelected(item);
            }
        } else {
            if (dispatchSelected && pageChangeListener != null) {
                pageChangeListener.onPageSelected(item);
            }
            completeScroll();
            scrollTo(destX, 0);
        }
    }

    public void setOnPageChangeListener(OnPageChangeListener listener) {
        this.pageChangeListener = listener;
    }

    /** Returns the number of pages that will be retained to either side of the
     * current page in the view hierarchy in an idle state. Defaults to 1.
     */

    public int getOffscreenPageLimit() {
        return offscreenPageLimit;
    }

    /**
     * Set the number of pages that should be retained to either side of the
     * current page in the view hierarchy in an idle state. Pages beyond this
     * limit will be recreated from the adapter when needed.
     *
     * <p>This is offered as an optimization. If you know in advance the number
     * of pages you will need to support or have lazy-loading mechanisms in place
     * on your pages, tweaking this setting can have benefits in perceived smoothness
     * of paging animations and interaction. If you have a small number of pages (3-4)
     * that you can keep active all at once, less time will be spent in layout for
     * newly created view subtrees as the user pages back and forth.</p>
     *
     * <p>You should keep this limit low, especially if your pages have complex layouts.
     * This setting defaults to 1.</p>
     *
     * @param limit How many pages will be kept offscreen in an idle state.
     */
    public void setOffscreenPageLimit(int limit) {
        if (limit < DEFAULT_OFFSCREEN_PAGES) {
            limit = DEFAULT_OFFSCREEN_PAGES;
        }
        if (limit != offscreenPageLimit) {
            offscreenPageLimit = limit;
            populate();
        }
    }

    /** Set the margin between pages.
     *
     * @param marginPixels Distance between adjacent pages in pixels
     */
    public void setPageMargin(int marginPixels) {
        final int oldMargin = pageMargin;
        pageMargin = marginPixels;
        final int width = getWidth();
        recomputeScrollPosition(width, width, marginPixels, oldMargin);
        requestLayout();
    }

    /** Return the margin between pages. */
    public int getPageMargin() {
        return pageMargin;
    }

    /**
     * Set a drawable that will be used to fill the margin between pages.
     *
     * @param d Drawable to display between pages
     */
    public void setPageMarginDrawable(Drawable d) {
        marginDrawable = d;
        if (d != null) refreshDrawableState();
        setWillNotDraw(d == null);
        invalidate();
    }

    /** Set a drawable that will be used to fill the margin between pages.
     *
     * @param resId Resource ID of a drawable to display between pages
     */
    public void setPageMarginDrawable(int resId) {
        setPageMarginDrawable(getContext().getResources().getDrawable(resId));
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || who == marginDrawable;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        final Drawable d = marginDrawable;
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
    }

    // We want the duration of the page snap animation to be influenced by the distance that
    // the screen has to travel, however, we don't want this duration to be effected in a
    // purely linear fashion. Instead, we use this method to moderate the effect that the distance
    // of travel has on the overall snap duration.
    float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    /** Scroll smoothly by x/y pixels */
    void smoothScrollTo(int x, int y, int velocity) {
        if (getChildCount() == 0) {
            // Nothing to do.
            setScrollingCacheEnabled(false);
            return;
        }
        int sx = getScrollX();
        int sy = getScrollY();
        int dx = x - sx;
        int dy = y - sy;
        if (dx == 0 && dy == 0) {
            completeScroll();
            populate();
            setScrollState(SCROLL_STATE_IDLE);
            return;
        }
        setScrollingCacheEnabled(true);
        setScrollState(SCROLL_STATE_SETTLING);
        final int width = getWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, 1.0f * Math.abs(dx) / width);
        final float distance = halfWidth + halfWidth *
                distanceInfluenceForSnapDuration(distanceRatio);
        int duration = 0;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float pageWidth = width * adapter.getPageWidth(curItem);
            final float pageDelta = (float) Math.abs(dx) / (pageWidth + pageMargin);
            duration = (int) ((pageDelta + 1) * 100);
        }
        duration = Math.min(duration, MAX_SETTLE_DURATION);
        scroller.startScroll(sx, sy, dx, dy, duration);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    ItemInfo addNewItem(int position, int index) {
        ItemInfo ii = new ItemInfo();
        ii.position = position;
        ii.object = adapter.instantiateItem(this, position);
        ii.widthFactor = adapter.getPageWidth(position);
        if (index < 0 || index >= items.size()) {
            items.add(ii);
        } else {
            items.add(index, ii);
        }
        return ii;
    }

    void populate() {
        populate(curItem);
    }
    void populate(int newCurrentItem) {
        ItemInfo oldCurInfo = null;
        if (curItem != newCurrentItem) {
            oldCurInfo = infoForPosition(curItem);
            curItem = newCurrentItem;
        }
        if (adapter == null) {
            return;
        }
        // Bail now if waiting to populate.  This is to hold off on creating views from when
        // the user releases their finger to fling to a new position until we have
        // finished the scroll to that position, avoiding glitches from happening at that point.
        if (populatePending) {
            if (DEBUG) Log.i(TAG, "populate is pending, skipping for now...");
            return;
        }
        // Don't populate until we are attached to a window. This avoids populating
        // before restoring view hierarchy state and conflicting with what is restored.
        if (getWindowToken() == null) {
            return;
        }
        final int pageLimit = offscreenPageLimit;
        final int startPos = Math.max(0, curItem - pageLimit);
        final int N = adapter.getCount();
        final int endPos = Math.min(N-1, curItem + pageLimit);
        // Locate the currently focused item or add it if needed.
        int curIndex = -1;
        ItemInfo curItem = null;
        for (curIndex = 0; curIndex < items.size(); curIndex++) {
            final ItemInfo ii = items.get(curIndex);
            if (ii.position >= this.curItem) {
                if (ii.position == this.curItem) curItem = ii;
                break;
            }
        }
        if (curItem == null && N > 0) {
            curItem = addNewItem(this.curItem, curIndex);
        }
        // Fill 3x the available width or up to the number of offscreen
        // pages requested to either side, whichever is larger.
        // If we have no current item we have no work to do.
        if (curItem != null) {
            float extraWidthLeft = 0.f;
            int itemIndex = curIndex - 1;
            ItemInfo ii = itemIndex >= 0 ? items.get(itemIndex) : null;
            final float leftWidthNeeded = 2.f - curItem.widthFactor;
            for (int pos = this.curItem - 1; pos >= 0; pos--) {
                if (extraWidthLeft >= leftWidthNeeded && pos < startPos) {
                    if (ii == null) {
                        break;
                    }
                    if (pos == ii.position && !ii.scrolling) {
                        items.remove(itemIndex);
                        adapter.destroyItem(this, pos, ii.object);
                        itemIndex--;
                        curIndex--;
                        ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                    }
                } else if (ii != null && pos == ii.position) {
                    extraWidthLeft += ii.widthFactor;
                    itemIndex--;
                    ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                } else {
                    ii = addNewItem(pos, itemIndex + 1);
                    extraWidthLeft += ii.widthFactor;
                    curIndex++;
                    ii = itemIndex >= 0 ? items.get(itemIndex) : null;
                }
            }
            float extraWidthRight = curItem.widthFactor;
            itemIndex = curIndex + 1;
            if (extraWidthRight < 2.f) {
                ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                for (int pos = this.curItem + 1; pos < N; pos++) {
                    if (extraWidthRight >= 2.f && pos > endPos) {
                        if (ii == null) {
                            break;
                        }
                        if (pos == ii.position && !ii.scrolling) {
                            items.remove(itemIndex);
                            adapter.destroyItem(this, pos, ii.object);
                            ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                        }
                    } else if (ii != null && pos == ii.position) {
                        extraWidthRight += ii.widthFactor;
                        itemIndex++;
                        ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                    } else {
                        ii = addNewItem(pos, itemIndex);
                        itemIndex++;
                        extraWidthRight += ii.widthFactor;
                        ii = itemIndex < items.size() ? items.get(itemIndex) : null;
                    }
                }
            }
            calculatePageOffsets(curItem, curIndex, oldCurInfo);
        }
        if (DEBUG) {
            Log.i(TAG, "Current page list:");
            for (int i = 0; i< items.size(); i++) {
                Log.i(TAG, "#" + i + ": page " + items.get(i).position);
            }
        }
        // Check width measurement of current pages. Update StreamPagerLayoutParams as needed.
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            final StreamPagerLayoutParams lp = (StreamPagerLayoutParams) child.getLayoutParams();
            if (lp.widthFactor == 0.f) {
                // 0 means requery the adapter for this, it doesn't have a valid width.
                final ItemInfo ii = infoForChild(child);
                if (ii != null) {
                    lp.widthFactor = ii.widthFactor;
                }
            }
        }
        if (hasFocus()) {
            View currentFocused = findFocus();
            ItemInfo ii = currentFocused != null ? infoForAnyChild(currentFocused) : null;
            if (ii == null || ii.position != this.curItem) {
                for (int i=0; i<getChildCount(); i++) {
                    View child = getChildAt(i);
                    ii = infoForChild(child);
                    if (ii != null && ii.position == this.curItem) {
                        if (child.requestFocus(FOCUS_FORWARD)) {
                            break;
                        }
                    }
                }
            }
        }
    }
    private void calculatePageOffsets(ItemInfo curItem, int curIndex, ItemInfo oldCurInfo) {
        final int N = adapter.getCount();
        final int width = getWidth();
        final float marginOffset = width > 0 ? (float) pageMargin / width : 0;
        // Fix up offsets for later layout.
        if (oldCurInfo != null) {
            final int oldCurPosition = oldCurInfo.position;
            // Base offsets off of oldCurInfo.
            if (oldCurPosition < curItem.position) {
                int itemIndex = 0;
                ItemInfo ii;
                float offset = oldCurInfo.offset + oldCurInfo.widthFactor + marginOffset;
                for (int pos = oldCurPosition + 1;
                     pos <= curItem.position && itemIndex < items.size(); pos++) {
                    ii = items.get(itemIndex);
                    while (pos > ii.position && itemIndex < items.size() - 1) {
                        itemIndex++;
                        ii = items.get(itemIndex);
                    }
                    while (pos < ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset += adapter.getPageWidth(pos) + marginOffset;
                        pos++;
                    }
                    ii.offset = offset;
                    offset += ii.widthFactor + marginOffset;
                }
            } else if (oldCurPosition > curItem.position) {
                int itemIndex = items.size() - 1;
                ItemInfo ii;
                float offset = oldCurInfo.offset;
                for (int pos = oldCurPosition - 1;
                     pos >= curItem.position && itemIndex >= 0; pos--) {
                    ii = items.get(itemIndex);
                    while (pos < ii.position && itemIndex > 0) {
                        itemIndex--;
                        ii = items.get(itemIndex);
                    }
                    while (pos > ii.position) {
                        // We don't have an item populated for this,
                        // ask the adapter for an offset.
                        offset -= adapter.getPageWidth(pos) + marginOffset;
                        pos--;
                    }
                    offset -= ii.widthFactor + marginOffset;
                    ii.offset = offset;
                }
            }
        }
        // Base all offsets off of curItem.
        final int itemCount = items.size();
        float offset = curItem.offset;
        int pos = curItem.position - 1;
        firstOffset = curItem.position == 0 ? curItem.offset : -Float.MAX_VALUE;
        lastOffset = curItem.position == N - 1 ?
                curItem.offset + curItem.widthFactor - 1 : Float.MAX_VALUE;
        // Previous pages
        for (int i = curIndex - 1; i >= 0; i--, pos--) {
            final ItemInfo ii = items.get(i);
            while (pos > ii.position) {
                offset -= adapter.getPageWidth(pos--) + marginOffset;
            }
            offset -= ii.widthFactor + marginOffset;
            ii.offset = offset;
            if (ii.position == 0) firstOffset = offset;
        }
        offset = curItem.offset + curItem.widthFactor + marginOffset;
        pos = curItem.position + 1;
        // Next pages
        for (int i = curIndex + 1; i < itemCount; i++, pos++) {
            final ItemInfo ii = items.get(i);
            while (pos < ii.position) {
                offset += adapter.getPageWidth(pos++) + marginOffset;
            }
            if (ii.position == N - 1) {
                lastOffset = offset + ii.widthFactor - 1;
            }
            ii.offset = offset;
            offset += ii.widthFactor + marginOffset;
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (!checkLayoutParams(params)) {
            params = generateLayoutParams(params);
        }
        final StreamPagerLayoutParams lp = (StreamPagerLayoutParams) params;
        if (inLayout) {
            lp.needsMeasure = true;
            addViewInLayout(child, index, params);
        } else {
            super.addView(child, index, params);
        }
        if (USE_CACHE) {
            if (child.getVisibility() != GONE) {
                child.setDrawingCacheEnabled(scrollingCacheEnabled);
            } else {
                child.setDrawingCacheEnabled(false);
            }
        }
    }

    ItemInfo infoForChild(View child) {
        for (int i = 0; i< items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (adapter.isViewFromObject(child, ii.object)) {
                return ii;
            }
        }
        return null;
    }

    ItemInfo infoForAnyChild(View child) {
        ViewParent parent;
        while ((parent=child.getParent()) != this) {
            if (parent == null || !(parent instanceof View)) {
                return null;
            }
            child = (View)parent;
        }
        return infoForChild(child);
    }
    ItemInfo infoForPosition(int position) {
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (ii.position == position) {
                return ii;
            }
        }
        return null;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        firstLayout = true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // For simple implementation, or internal size is always 0.
        // We depend on the container to specify the layout size of
        // our view.  We can't really know what it is since we will be
        // adding and removing different arbitrary views and do not
        // want the layout to change as this happens.
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec),
                getDefaultSize(0, heightMeasureSpec));
        final int measuredWidth = getMeasuredWidth();
        final int maxGutterSize = measuredWidth / 10;
        gutterSize = Math.min(maxGutterSize, defaultGutterSize);
        // Children are just made to fill our space.
        int childWidthSize = measuredWidth - getPaddingLeft() - getPaddingRight();
        int childHeightSize = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();

        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(childWidthSize, MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(childHeightSize, MeasureSpec.EXACTLY);
        // Make sure we have created all fragments that we need to have shown.
        inLayout = true;
        populate();
        inLayout = false;
        // Page views next.
        int size = getChildCount();
        for (int i = 0; i < size; ++i) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                if (DEBUG) Log.v(TAG, "Measuring #" + i + " " + child + " " + childWidthMeasureSpec);
                final StreamPagerLayoutParams lp = (StreamPagerLayoutParams) child.getLayoutParams();
                final int widthSpec = MeasureSpec.makeMeasureSpec(
                        (int) (childWidthSize * lp.widthFactor), MeasureSpec.EXACTLY);
                child.measure(widthSpec, childHeightMeasureSpec);
            }
        }
    }
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Make sure scroll position is set correctly.
        if (w != oldw) {
            recomputeScrollPosition(w, oldw, pageMargin, pageMargin);
        }
    }
    private void recomputeScrollPosition(int width, int oldWidth, int margin, int oldMargin) {
        if (oldWidth > 0 && !items.isEmpty()) {
            final int widthWithMargin = width + margin;
            final int oldWidthWithMargin = oldWidth + oldMargin;
            final int xpos = getScrollX();
            final float pageOffset = (float) xpos / oldWidthWithMargin;
            final int newOffsetPixels = (int) (pageOffset * widthWithMargin);
            scrollTo(newOffsetPixels, getScrollY());
            if (!scroller.isFinished()) {
                // We now return to your regularly scheduled scroll, already in progress.
                final int newDuration = scroller.getDuration() - scroller.timePassed();
                ItemInfo targetInfo = infoForPosition(curItem);
                scroller.startScroll(newOffsetPixels, 0,
                        (int) (targetInfo.offset * width), 0, newDuration);
            }
        } else {
            final ItemInfo ii = infoForPosition(curItem);
            final float scrollOffset = ii != null ? Math.min(ii.offset, lastOffset) : 0;
            final int scrollPos = (int) (scrollOffset * width);
            if (scrollPos != getScrollX()) {
                completeScroll();
                scrollTo(scrollPos, getScrollY());
            }
        }
    }
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        inLayout = true;
        populate();
        inLayout = false;
        final int count = getChildCount();
        int width = r - l;
        int height = b - t;
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();
        int paddingRight = getPaddingRight();
        int paddingBottom = getPaddingBottom();

        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                final StreamPagerLayoutParams lp = (StreamPagerLayoutParams) child.getLayoutParams();
                ItemInfo ii;
                if ((ii = infoForChild(child)) != null) {
                    int loff = (int) (width * ii.offset);
                    int childLeft = paddingLeft + loff;
                    int childTop = paddingTop;
                    if (lp.needsMeasure) {
                        // This was added during layout and needs measurement.
                        // Do it now that we know what we're working with.
                        lp.needsMeasure = false;
                        final int widthSpec = MeasureSpec.makeMeasureSpec(
                                (int) ((width - paddingLeft - paddingRight) * lp.widthFactor),
                                MeasureSpec.EXACTLY);
                        final int heightSpec = MeasureSpec.makeMeasureSpec(
                                (int) (height - paddingTop - paddingBottom),
                                MeasureSpec.EXACTLY);
                        child.measure(widthSpec, heightSpec);
                    }
                    if (DEBUG) Log.v(TAG, "Positioning #" + i + " " + child + " f=" + ii.object
                            + ":" + childLeft + "," + childTop + " " + child.getMeasuredWidth()
                            + "x" + child.getMeasuredHeight());
                    child.layout(childLeft, childTop,
                            childLeft + child.getMeasuredWidth(),
                            childTop + child.getMeasuredHeight());
                }
            }
        }
        topPageBounds = paddingTop;
        bottomPageBounds = height - paddingBottom;
        firstLayout = false;
    }
    @Override
    public void computeScroll() {
        if (!scroller.isFinished() && scroller.computeScrollOffset()) {
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = scroller.getCurrX();
            int y = scroller.getCurrY();
            if (oldX != x || oldY != y) {
                scrollTo(x, y);
                if (!pageScrolled(x)) {
                    scroller.abortAnimation();
                    scrollTo(0, y);
                }
            }
            // Keep on drawing until the animation has finished.
            ViewCompat.postInvalidateOnAnimation(this);
            return;
        }
        // Done with scroll, clean up state.
        completeScroll();
    }
    private boolean pageScrolled(int xpos) {
        if (items.size() == 0) {
            calledSuper = false;
            onPageScrolled(0, 0, 0);
            if (!calledSuper) {
                throw new IllegalStateException(
                        "onPageScrolled did not call superclass implementation");
            }
            return false;
        }
        final ItemInfo ii = infoForCurrentScrollPosition();
        final int width = getWidth();
        final int widthWithMargin = width + pageMargin;
        final float marginOffset = (float) pageMargin / width;
        final int currentPage = ii.position;
        final float pageOffset = (((float) xpos / width) - ii.offset) /
                (ii.widthFactor + marginOffset);
        final int offsetPixels = (int) (pageOffset * widthWithMargin);
        calledSuper = false;
        onPageScrolled(currentPage, pageOffset, offsetPixels);
        if (!calledSuper) {
            throw new IllegalStateException(
                    "onPageScrolled did not call superclass implementation");
        }
        return true;
    }

    /**
     * This method will be invoked when the current page is scrolled, either as part
     * of a programmatically initiated smooth scroll or a user initiated touch scroll.
     * If you override this method you must call through to the superclass implementation
     * (e.g. super.onPageScrolled(position, offset, offsetPixels)) before onPageScrolled
     * returns.
     *
     * @param position Position index of the first page currently being displayed.
     *                 Page position+1 will be visible if positionOffset is nonzero.
     * @param offset Value from [0, 1) indicating the offset from the page at position.
     * @param offsetPixels Value in pixels indicating the offset from position.
     */
    protected void onPageScrolled(int position, float offset, int offsetPixels) {
        if (pageChangeListener != null) {
            pageChangeListener.onPageScrolled(position, offset, offsetPixels);
        }
        calledSuper = true;
    }

    private void completeScroll() {
        boolean needPopulate = scrollState == SCROLL_STATE_SETTLING;
        if (needPopulate) {
            // Done with scroll, no longer want to cache view drawing.
            setScrollingCacheEnabled(false);
            scroller.abortAnimation();
            int oldX = getScrollX();
            int oldY = getScrollY();
            int x = scroller.getCurrX();
            int y = scroller.getCurrY();
            if (oldX != x || oldY != y) {
                scrollTo(x, y);
            }
            setScrollState(SCROLL_STATE_IDLE);
        }
        populatePending = false;
        for (int i = 0; i< items.size(); i++) {
            ItemInfo ii = items.get(i);
            if (ii.scrolling) {
                needPopulate = true;
                ii.scrolling = false;
            }
        }
        if (needPopulate) {
            populate();
        }
    }
    private boolean isGutterDrag(float x, float dx) {
        return (x < gutterSize && dx > 0) || (x > getWidth() - gutterSize && dx < 0);
    }
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        /*
         * This method JUST determines whether we want to intercept the motion.
         * If we return true, onMotionEvent will be called and we do the actual
         * scrolling there.
         */
        final int action = ev.getAction() & MotionEventCompat.ACTION_MASK;
        // Always take care of the touch gesture being complete.
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
            // Release the drag.
            if (DEBUG) Log.v(TAG, "Intercept done!");
            isBeingDragged = false;
            isUnableToDrag = false;
            activePointerId = INVALID_POINTER;
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            return false;
        }
        // Nothing more to do here if we have decided whether or not we
        // are dragging.
        if (action != MotionEvent.ACTION_DOWN) {
            if (isBeingDragged) {
                if (DEBUG) Log.v(TAG, "Intercept returning true!");
                return true;
            }
            if (isUnableToDrag) {
                if (DEBUG) Log.v(TAG, "Intercept returning false!");
                return false;
            }
        }
        switch (action) {
            case MotionEvent.ACTION_MOVE: {
                /*
                 * isBeingDragged == false, otherwise the shortcut would have caught it. Check
                 * whether the user has moved far enough from his original down touch.
                 */
                /*
                * Locally do absolute value. lastMotionY is set to the y value
                * of the down event.
                */
                final int activePointerId = this.activePointerId;
                if (activePointerId == INVALID_POINTER) {
                    // If we don't have a valid id, the touch down wasn't on content.
                    break;
                }
                final int pointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                final float x = MotionEventCompat.getX(ev, pointerIndex);
                final float dx = x - lastMotionX;
                final float xDiff = Math.abs(dx);
                final float y = MotionEventCompat.getY(ev, pointerIndex);
                final float yDiff = Math.abs(y - lastMotionY);
                if (DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);
                if (dx != 0 && !isGutterDrag(lastMotionX, dx) &&
                        canScroll(this, false, (int) dx, (int) x, (int) y)) {
                    // Nested view has scrollable area under this point. Let it be handled there.
                    initialMotionX = lastMotionX = x;
                    lastMotionY = y;
                    isUnableToDrag = true;
                    return false;
                }
                if (xDiff > touchSlop && xDiff > yDiff) {
                    if (DEBUG) Log.v(TAG, "Starting drag!");
                    isBeingDragged = true;
                    setScrollState(SCROLL_STATE_DRAGGING);
                    lastMotionX = dx > 0 ? initialMotionX + touchSlop :
                            initialMotionX - touchSlop;
                    setScrollingCacheEnabled(true);
                } else {
                    if (yDiff > touchSlop) {
                        // The finger has moved enough in the vertical
                        // direction to be counted as a drag...  abort
                        // any attempt to drag horizontally, to work correctly
                        // with children that have scrolling containers.
                        if (DEBUG) Log.v(TAG, "Starting unable to drag!");
                        isUnableToDrag = true;
                    }
                }
                if (isBeingDragged) {
                    // Scroll to follow the motion event
                    if (performDrag(x)) {
                        ViewCompat.postInvalidateOnAnimation(this);
                    }
                }
                break;
            }
            case MotionEvent.ACTION_DOWN: {
                /*
                 * Remember location of down touch.
                 * ACTION_DOWN always refers to pointer index 0.
                 */
                lastMotionX = initialMotionX = ev.getX();
                lastMotionY = ev.getY();
                activePointerId = MotionEventCompat.getPointerId(ev, 0);
                isUnableToDrag = false;
                scroller.computeScrollOffset();
                if (scrollState == SCROLL_STATE_SETTLING &&
                        Math.abs(scroller.getFinalX() - scroller.getCurrX()) > closeEnough) {
                    // Let the user 'catch' the pager as it animates.
                    scroller.abortAnimation();
                    populatePending = false;
                    populate();
                    isBeingDragged = true;
                    setScrollState(SCROLL_STATE_DRAGGING);
                } else {
                    completeScroll();
                    isBeingDragged = false;
                }
                if (DEBUG) Log.v(TAG, "Down at " + lastMotionX + "," + lastMotionY
                        + " isBeingDragged=" + isBeingDragged
                        + "isUnableToDrag=" + isUnableToDrag);
                break;
            }
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return isBeingDragged;
    }
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && ev.getEdgeFlags() != 0) {
            // Don't handle edge touches immediately -- they may actually belong to one of our
            // descendants.
            return false;
        }
        if (adapter == null || adapter.getCount() == 0) {
            // Nothing to present or scroll; nothing to touch.
            return false;
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(ev);
        final int action = ev.getAction();
        boolean needsInvalidate = false;
        switch (action & MotionEventCompat.ACTION_MASK) {
            case MotionEvent.ACTION_DOWN: {
                scroller.abortAnimation();
                populatePending = false;
                populate();
                isBeingDragged = true;
                setScrollState(SCROLL_STATE_DRAGGING);
                // Remember where the motion event started
                lastMotionX = initialMotionX = ev.getX();
                activePointerId = MotionEventCompat.getPointerId(ev, 0);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (!isBeingDragged) {
                    final int pointerIndex = MotionEventCompat.findPointerIndex(ev, activePointerId);
                    final float x = MotionEventCompat.getX(ev, pointerIndex);
                    final float xDiff = Math.abs(x - lastMotionX);
                    final float y = MotionEventCompat.getY(ev, pointerIndex);
                    final float yDiff = Math.abs(y - lastMotionY);
                    if (DEBUG) Log.v(TAG, "Moved x to " + x + "," + y + " diff=" + xDiff + "," + yDiff);
                    if (xDiff > touchSlop && xDiff > yDiff) {
                        if (DEBUG) Log.v(TAG, "Starting drag!");
                        isBeingDragged = true;
                        lastMotionX = x - initialMotionX > 0 ? initialMotionX + touchSlop :
                                initialMotionX - touchSlop;
                        setScrollState(SCROLL_STATE_DRAGGING);
                        setScrollingCacheEnabled(true);
                    }
                }
                // Not else! Note that isBeingDragged can be set above.
                if (isBeingDragged) {
                    // Scroll to follow the motion event
                    final int activePointerIndex = MotionEventCompat.findPointerIndex(
                            ev, activePointerId);
                    final float x = MotionEventCompat.getX(ev, activePointerIndex);
                    needsInvalidate |= performDrag(x);
                }
                break;
            case MotionEvent.ACTION_UP:
                if (isBeingDragged) {
                    final VelocityTracker velocityTracker = this.velocityTracker;
                    velocityTracker.computeCurrentVelocity(1000, maximumVelocity);
                    int initialVelocity = (int) VelocityTrackerCompat.getXVelocity(
                            velocityTracker, activePointerId);
                    populatePending = true;
                    final int width = getWidth();
                    final int scrollX = getScrollX();
                    final ItemInfo ii = infoForCurrentScrollPosition();
                    final int currentPage = ii.position;
                    final float pageOffset = (((float) scrollX / width) - ii.offset) / ii.widthFactor;
                    final int activePointerIndex =
                            MotionEventCompat.findPointerIndex(ev, activePointerId);
                    final float x = MotionEventCompat.getX(ev, activePointerIndex);
                    final int totalDelta = (int) (x - initialMotionX);
                    int nextPage = determineTargetPage(currentPage, pageOffset, initialVelocity,
                            totalDelta);
                    setCurrentItemInternal(nextPage, true, true, initialVelocity);
                    activePointerId = INVALID_POINTER;
                    endDrag();
                    needsInvalidate = leftEdge.onRelease() | rightEdge.onRelease();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                if (isBeingDragged) {
                    setCurrentItemInternal(curItem, true, true);
                    activePointerId = INVALID_POINTER;
                    endDrag();
                    needsInvalidate = leftEdge.onRelease() | rightEdge.onRelease();
                }
                break;
            case MotionEventCompat.ACTION_POINTER_DOWN: {
                final int index = MotionEventCompat.getActionIndex(ev);
                final float x = MotionEventCompat.getX(ev, index);
                lastMotionX = x;
                activePointerId = MotionEventCompat.getPointerId(ev, index);
                break;
            }
            case MotionEventCompat.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                lastMotionX = MotionEventCompat.getX(ev,
                        MotionEventCompat.findPointerIndex(ev, activePointerId));
                break;
        }
        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
        return true;
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = MotionEventCompat.getActionIndex(ev);
        final int pointerId = MotionEventCompat.getPointerId(ev, pointerIndex);
        if (pointerId == activePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            lastMotionX = MotionEventCompat.getX(ev, newPointerIndex);
            activePointerId = MotionEventCompat.getPointerId(ev, newPointerIndex);
            if (velocityTracker != null) {
                velocityTracker.clear();
            }
        }
    }

    private boolean performDrag(float x) {
        boolean needsInvalidate = false;
        final float deltaX = lastMotionX - x;
        lastMotionX = x;
        float oldScrollX = getScrollX();
        float scrollX = oldScrollX + deltaX;
        final int width = getWidth();
        float leftBound = width * firstOffset;
        float rightBound = width * lastOffset;
        boolean leftAbsolute = true;
        boolean rightAbsolute = true;
        final ItemInfo firstItem = items.get(0);
        final ItemInfo lastItem = items.get(items.size() - 1);
        if (firstItem.position != 0) {
            leftAbsolute = false;
            leftBound = firstItem.offset * width;
        }
        if (lastItem.position != adapter.getCount() - 1) {
            rightAbsolute = false;
            rightBound = lastItem.offset * width;
        }
        if (scrollX < leftBound) {
            if (leftAbsolute) {
                float over = leftBound - scrollX;
                needsInvalidate = leftEdge.onPull(Math.abs(over) / width);
            }
            scrollX = leftBound;
        } else if (scrollX > rightBound) {
            if (rightAbsolute) {
                float over = scrollX - rightBound;
                needsInvalidate = rightEdge.onPull(Math.abs(over) / width);
            }
            scrollX = rightBound;
        }
        // Don't lose the rounded component
        lastMotionX += scrollX - (int) scrollX;
        scrollTo((int) scrollX, getScrollY());
        pageScrolled((int) scrollX);
        return needsInvalidate;
    }
    /**
     * @return Info about the page at the current scroll position.
     *         This can be synthetic for a missing middle page; the 'object' field can be null.
     */
    private ItemInfo infoForCurrentScrollPosition() {
        final int width = getWidth();
        final float scrollOffset = width > 0 ? (float) getScrollX() / width : 0;
        final float marginOffset = width > 0 ? (float) pageMargin / width : 0;
        int lastPos = -1;
        float lastOffset = 0.f;
        float lastWidth = 0.f;
        boolean first = true;
        ItemInfo lastItem = null;
        for (int i = 0; i < items.size(); i++) {
            ItemInfo ii = items.get(i);
            float offset;
            if (!first && ii.position != lastPos + 1) {
                // Create a synthetic item for a missing page.
                ii = tempItem;
                ii.offset = lastOffset + lastWidth + marginOffset;
                ii.position = lastPos + 1;
                ii.widthFactor = adapter.getPageWidth(ii.position);
                i--;
            }
            offset = ii.offset;
            final float leftBound = offset;
            final float rightBound = offset + ii.widthFactor + marginOffset;
            if (first || scrollOffset >= leftBound) {
                if (scrollOffset < rightBound || i == items.size() - 1) {
                    return ii;
                }
            } else {
                return lastItem;
            }
            first = false;
            lastPos = ii.position;
            lastOffset = offset;
            lastWidth = ii.widthFactor;
            lastItem = ii;
        }
        return lastItem;
    }
    private int determineTargetPage(int currentPage, float pageOffset, int velocity, int deltaX) {
        int targetPage;
        if (Math.abs(deltaX) > flingDistance && Math.abs(velocity) > minimumVelocity) {
            targetPage = velocity > 0 ? currentPage : currentPage + 1;
        } else {
            targetPage = (int) (currentPage + pageOffset + 0.5f);
        }
        if (items.size() > 0) {
            final ItemInfo firstItem = items.get(0);
            final ItemInfo lastItem = items.get(items.size() - 1);
            // Only let the user target pages we have items for
            targetPage = Math.max(firstItem.position, Math.min(targetPage, lastItem.position));
        }
        return targetPage;
    }
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        boolean needsInvalidate = false;
        final int overScrollMode = ViewCompat.getOverScrollMode(this);
        if (overScrollMode == ViewCompat.OVER_SCROLL_ALWAYS ||
                (overScrollMode == ViewCompat.OVER_SCROLL_IF_CONTENT_SCROLLS &&
                        adapter != null && adapter.getCount() > 1)) {
            if (!leftEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int height = getHeight() - getPaddingTop() - getPaddingBottom();
                final int width = getWidth();
                canvas.rotate(270);
                canvas.translate(-height + getPaddingTop(), firstOffset * width);
                leftEdge.setSize(height, width);
                needsInvalidate |= leftEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
            if (!rightEdge.isFinished()) {
                final int restoreCount = canvas.save();
                final int width = getWidth();
                final int height = getHeight() - getPaddingTop() - getPaddingBottom();
                canvas.rotate(90);
                canvas.translate(-getPaddingTop(), -(lastOffset + 1) * width);
                rightEdge.setSize(height, width);
                needsInvalidate |= rightEdge.draw(canvas);
                canvas.restoreToCount(restoreCount);
            }
        } else {
            leftEdge.finish();
            rightEdge.finish();
        }
        if (needsInvalidate) {
            // Keep animating
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Draw the margin drawable between pages if needed.
        if (pageMargin > 0 && marginDrawable != null && items.size() > 0 && adapter != null) {
            final int scrollX = getScrollX();
            final int width = getWidth();
            final float marginOffset = (float) pageMargin / width;
            int itemIndex = 0;
            ItemInfo ii = items.get(0);
            float offset = ii.offset;
            final int itemCount = items.size();
            final int firstPos = ii.position;
            final int lastPos = items.get(itemCount - 1).position;
            for (int pos = firstPos; pos < lastPos; pos++) {
                while (pos > ii.position && itemIndex < itemCount) {
                    ii = items.get(++itemIndex);
                }
                float drawAt;
                if (pos == ii.position) {
                    drawAt = (ii.offset + ii.widthFactor) * width;
                    offset = ii.offset + ii.widthFactor + marginOffset;
                } else {
                    float widthFactor = adapter.getPageWidth(pos);
                    drawAt = (offset + widthFactor) * width;
                    offset += widthFactor + marginOffset;
                }
                if (drawAt + pageMargin > scrollX) {
                    marginDrawable.setBounds((int) drawAt, topPageBounds,
                            (int) (drawAt + pageMargin + 0.5f), bottomPageBounds);
                    marginDrawable.draw(canvas);
                }
                if (drawAt > scrollX + width) {
                    break; // No more visible, no sense in continuing
                }
            }
        }
    }

    private void endDrag() {
        isBeingDragged = false;
        isUnableToDrag = false;
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
    private void setScrollingCacheEnabled(boolean enabled) {
        if (scrollingCacheEnabled != enabled) {
            scrollingCacheEnabled = enabled;
            if (USE_CACHE) {
                final int size = getChildCount();
                for (int i = 0; i < size; ++i) {
                    final View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        child.setDrawingCacheEnabled(enabled);
                    }
                }
            }
        }
    }
    /**
     * Tests scrollability within child views of v given a delta of dx.
     *
     * @param v View to test for horizontal scrollability
     * @param checkV Whether the view v passed should itself be checked for scrollability (true),
     *               or just its children (false).
     * @param dx Delta scrolled in pixels
     * @param x X coordinate of the active touch point
     * @param y Y coordinate of the active touch point
     * @return true if child views of v can be scrolled by delta of dx.
     */
    protected boolean canScroll(View v, boolean checkV, int dx, int x, int y) {
        if (v instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) v;
            final int scrollX = v.getScrollX();
            final int scrollY = v.getScrollY();
            final int count = group.getChildCount();
            // Count backwards - let topmost views consume scroll distance first.
            for (int i = count - 1; i >= 0; i--) {
                // TODO: Add versioned support here for transformed views.
                // This will not work for transformed views in Honeycomb+
                final View child = group.getChildAt(i);
                if (x + scrollX >= child.getLeft() && x + scrollX < child.getRight() &&
                        y + scrollY >= child.getTop() && y + scrollY < child.getBottom() &&
                        canScroll(child, true, dx, x + scrollX - child.getLeft(),
                                y + scrollY - child.getTop())) {
                    return true;
                }
            }
        }
        return checkV && ViewCompat.canScrollHorizontally(v, -dx);
    }
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Let the focused view and/or our descendants get the key first
        return super.dispatchKeyEvent(event) || executeKeyEvent(event);
    }
    /**
     * You can call this function yourself to have the scroll view perform
     * scrolling from a key event, just as if the event had been dispatched to
     * it by the view hierarchy.
     *
     * @param event The key event to execute.
     * @return Return true if the event was handled, else false.
     */
    public boolean executeKeyEvent(KeyEvent event) {
        boolean handled = false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    handled = arrowScroll(FOCUS_LEFT);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    handled = arrowScroll(FOCUS_RIGHT);
                    break;
                case KeyEvent.KEYCODE_TAB:
                    // The focus finder had a bug handling FOCUS_FORWARD and FOCUS_BACKWARD
                    // before Android 3.0. Ignore the tab key on those devices.
                    if (KeyEventCompat.hasNoModifiers(event)) {
                        handled = arrowScroll(FOCUS_FORWARD);
                    } else if (KeyEventCompat.hasModifiers(event, KeyEvent.META_SHIFT_ON)) {
                        handled = arrowScroll(FOCUS_BACKWARD);
                    }
                    break;
            }
        }
        return handled;
    }
    public boolean arrowScroll(int direction) {
        View currentFocused = findFocus();
        if (currentFocused == this) currentFocused = null;
        boolean handled = false;
        View nextFocused = FocusFinder.getInstance().findNextFocus(this, currentFocused,
                direction);
        if (nextFocused != null && nextFocused != currentFocused) {
            if (direction == View.FOCUS_LEFT) {
                // If there is nothing to the left, or this is causing us to
                // jump to the right, then what we really want to do is page left.
                final int nextLeft = getChildRectInPagerCoordinates(tempRect, nextFocused).left;
                final int curLeft = getChildRectInPagerCoordinates(tempRect, currentFocused).left;
                if (currentFocused != null && nextLeft >= curLeft) {
                    handled = pageLeft();
                } else {
                    handled = nextFocused.requestFocus();
                }
            } else if (direction == View.FOCUS_RIGHT) {
                // If there is nothing to the right, or this is causing us to
                // jump to the left, then what we really want to do is page right.
                final int nextLeft = getChildRectInPagerCoordinates(tempRect, nextFocused).left;
                final int currLeft = getChildRectInPagerCoordinates(tempRect, currentFocused).left;
                if (currentFocused != null && nextLeft <= currLeft) {
                    handled = pageRight();
                } else {
                    handled = nextFocused.requestFocus();
                }
            }
        } else if (direction == FOCUS_LEFT || direction == FOCUS_BACKWARD) {
            // Trying to move left and nothing there; try to page.
            handled = pageLeft();
        } else if (direction == FOCUS_RIGHT || direction == FOCUS_FORWARD) {
            // Trying to move right and nothing there; try to page.
            handled = pageRight();
        }
        if (handled) {
            playSoundEffect(SoundEffectConstants.getContantForFocusDirection(direction));
        }
        return handled;
    }
    private Rect getChildRectInPagerCoordinates(Rect outRect, View child) {
        if (outRect == null) {
            outRect = new Rect();
        }
        if (child == null) {
            outRect.set(0, 0, 0, 0);
            return outRect;
        }
        outRect.left = child.getLeft();
        outRect.right = child.getRight();
        outRect.top = child.getTop();
        outRect.bottom = child.getBottom();

        ViewParent parent = child.getParent();
        while (parent instanceof ViewGroup && parent != this) {
            final ViewGroup group = (ViewGroup) parent;
            outRect.left += group.getLeft();
            outRect.right += group.getRight();
            outRect.top += group.getTop();
            outRect.bottom += group.getBottom();
            parent = group.getParent();
        }
        return outRect;
    }
    boolean pageLeft() {
        if (curItem > 0) {
            setCurrentItem(curItem -1, true);
            return true;
        }
        return false;
    }
    boolean pageRight() {
        if (adapter != null && curItem < (adapter.getCount()-1)) {
            setCurrentItem(curItem +1, true);
            return true;
        }
        return false;
    }
    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        final int focusableCount = views.size();
        final int descendantFocusability = getDescendantFocusability();
        if (descendantFocusability != FOCUS_BLOCK_DESCENDANTS) {
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() == VISIBLE) {
                    ItemInfo ii = infoForChild(child);
                    if (ii != null && ii.position == curItem) {
                        child.addFocusables(views, direction, focusableMode);
                    }
                }
            }
        }
        // we add ourselves (if focusable) in all cases except for when we are
        // FOCUS_AFTER_DESCENDANTS and there are some descendants focusable.  this is
        // to avoid the focus search finding layouts when a more precise search
        // among the focusable children would be more interesting.
        if (descendantFocusability != FOCUS_AFTER_DESCENDANTS ||
                        // No focusable descendants
                        (focusableCount == views.size())) {
            // Note that we can't call the superclass here, because it will
            // add all views in.  So we need to do the same thing View does.
            if (!isFocusable()) {
                return;
            }
            if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE &&
                    isInTouchMode() && !isFocusableInTouchMode()) {
                return;
            }
            views.add(this);
        }
    }
    /**
     * We only want the current page that is being shown to be touchable.
     */
    @Override
    public void addTouchables(ArrayList<View> views) {
        // Note that we don't call super.addTouchables(), which means that
        // we don't call View.addTouchables().  This is okay because a ViewPager
        // is itself not touchable.
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == curItem) {
                    child.addTouchables(views);
                }
            }
        }
    }
    /**
     * We only want the current page that is being shown to be focusable.
     */
    @Override
    protected boolean onRequestFocusInDescendants(int direction,
                                                  Rect previouslyFocusedRect) {
        int index;
        int increment;
        int end;
        int count = getChildCount();
        if ((direction & FOCUS_FORWARD) != 0) {
            index = 0;
            increment = 1;
            end = count;
        } else {
            index = count - 1;
            increment = -1;
            end = -1;
        }
        for (int i = index; i != end; i += increment) {
            View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == curItem) {
                    if (child.requestFocus(direction, previouslyFocusedRect)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        // ViewPagers should only report accessibility info for the current page,
        // otherwise things get very confusing.
        // TODO: Should this note something about the paging container?
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE) {
                final ItemInfo ii = infoForChild(child);
                if (ii != null && ii.position == curItem &&
                        child.dispatchPopulateAccessibilityEvent(event)) {
                    return true;
                }
            }
        }
        return false;
    }
    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        return new StreamPagerLayoutParams();
    }
    @Override
    protected ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams p) {
        return generateDefaultLayoutParams();
    }
    @Override
    protected boolean checkLayoutParams(ViewGroup.LayoutParams p) {
        return p instanceof StreamPagerLayoutParams && super.checkLayoutParams(p);
    }
    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new StreamPagerLayoutParams(getContext(), attrs);
    }
}