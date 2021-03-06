package com.sampullman.pager;

/**
 * Callback interface for responding to changing state of the selected page in a StreamViewPager.
 */
public interface OnPageChangeListener<T> {
    /**
     * This method will be invoked when the current page is scrolled, either as part
     * of a programmatically initiated smooth scroll or a user initiated touch scroll.
     *
     * @param pageId Position index of the first page currently being displayed.
     *                 Page position+1 will be visible if positionOffset is nonzero.
     * @param positionOffset Value from [0, 1) indicating the offset from the page at position.
     * @param positionOffsetPixels Value in pixels indicating the offset from position.
     */
    public void onPageScrolled(T pageId, float positionOffset, int positionOffsetPixels);
    /**
     * This method will be invoked when a new page becomes selected. Animation is not
     * necessarily complete.
     *
     * @param pageId Id of the new selected page.
     */
    public void onPageSelected(T pageId);
    /**
     * Called when the scroll state changes. Useful for discovering when the user
     * begins dragging, when the pager is automatically settling to the current page,
     * or when it is fully stopped/idle.
     *
     * @param state The new scroll state.
     * @see StreamViewPager#SCROLL_STATE_IDLE
     * @see StreamViewPager#SCROLL_STATE_DRAGGING
     * @see StreamViewPager#SCROLL_STATE_SETTLING
     */
    public void onPageScrollStateChanged(int state);
}