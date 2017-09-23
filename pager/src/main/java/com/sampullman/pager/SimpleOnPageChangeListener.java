package com.sampullman.pager;

/**
 * Simple implementation of the {@link OnPageChangeListener} interface with stub
 * implementations of each method. Extend this if you do not intend to override
 * every method of {@link OnPageChangeListener}.
 */
public class SimpleOnPageChangeListener<T> implements OnPageChangeListener<T> {
    @Override
    public void onPageScrolled(T id, float positionOffset, int positionOffsetPixels) {
        // This space for rent
    }
    @Override
    public void onPageSelected(T id) {
        // This space for rent
    }
    @Override
    public void onPageScrollStateChanged(int state) {
        // This space for rent
    }
}
