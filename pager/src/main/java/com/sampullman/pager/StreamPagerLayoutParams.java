package com.sampullman.pager;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.ViewGroup;

/**
 * Layout parameters supplied for views added to a StreamViewPager.
 */
public class StreamPagerLayoutParams extends ViewGroup.LayoutParams {

    private static final int[] LAYOUT_ATTRS = new int[] {
            android.R.attr.layout_gravity
    };

    /**
     * Gravity setting for use on decor views only:
     * Where to position the view page within the overall StreamViewPager
     * container; constants are defined in {@link android.view.Gravity}.
     */
    public int gravity;

    // Width as a 0-1 multiplier of the measured pager width
    public float widthFactor = 0.f;

    // true if this view was added during layout and must be measured before being positioned.
    public boolean needsMeasure;

    public StreamPagerLayoutParams() {
        super(FILL_PARENT, FILL_PARENT);
    }

    public StreamPagerLayoutParams(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(attrs, LAYOUT_ATTRS);
        gravity = a.getInteger(0, Gravity.TOP);
        a.recycle();
    }
}