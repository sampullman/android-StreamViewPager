package com.sampullman.pager;

import android.view.View;
import android.view.ViewGroup;

import static com.sampullman.pager.StreamViewAdapter.CountIndicator.*;

public abstract class StreamViewAdapter<T> {

    public enum CountIndicator {
        FINITE, INFINITE, UNKNOWN
    }

    public CountIndicator countIndicator() {
        return INFINITE;
    }

    // Hacky function used in StreamViewPager draw method
    boolean hasAtLeastOneItem() {
        CountIndicator count = countIndicator();
        return (count == INFINITE) || (count == UNKNOWN);
    }

    public abstract T nextId(T fromId);
    public abstract T prevId(T fromId);

    // Convenience functions for checking for next/prev
    public boolean hasNext(T fromId) {
        return nextId(fromId) != null;
    }
    public boolean hasPrev(T fromId) {
        return prevId(fromId) != null;
    }

    public abstract T initialViewId();

    public abstract Object instantiateItem(ViewGroup container, T id);

    public void destroyItem(ViewGroup container, T id, Object object) {
        container.removeView((View)object);
    }

    public boolean isViewFromObject(View view, Object object) {
        return view == object;
    }

    /**
     * Returns the proportional width of a given page as a percentage of the
     * ViewPager's measured width from (0.f-1.f]
     *
     * @param id The id of the page requested
     * @return Proportional width for the given page position
     */
    public float getPageWidth(T id) {
        return 1.f;
    }
}
