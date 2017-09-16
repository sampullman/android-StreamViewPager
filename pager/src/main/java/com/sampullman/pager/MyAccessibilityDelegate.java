package com.sampullman.pager;

import android.os.Bundle;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityDelegate extends AccessibilityDelegateCompat {
    private StreamViewPager pager;

    public MyAccessibilityDelegate(StreamViewPager pager) {
        this.pager = pager;
    }

    @Override
    public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(host, event);
        event.setClassName(StreamViewPager.class.getName());
    }
    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfoCompat info) {
        super.onInitializeAccessibilityNodeInfo(host, info);

        info.setClassName(StreamViewPager.class.getName());
        PagerAdapter adapter = pager.getAdapter();
        info.setScrollable(adapter != null && adapter.getCount() > 1);

        int curItem = pager.getCurrentItem();
        if (adapter != null && curItem >= 0 && curItem < adapter.getCount() - 1) {
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
        }
        if (adapter != null && curItem > 0 && curItem < adapter.getCount()) {
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
        }
    }
    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (super.performAccessibilityAction(host, action, args)) {
            return true;
        }
        PagerAdapter adapter = pager.getAdapter();
        int curItem = pager.getCurrentItem();
        switch (action) {
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD: {
                if (adapter != null && curItem >= 0 && curItem < adapter.getCount() - 1) {
                    pager.setCurrentItem(curItem + 1);
                    return true;
                }
            } return false;
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD: {
                if (adapter != null && curItem > 0 && curItem < adapter.getCount()) {
                    pager.setCurrentItem(curItem - 1);
                    return true;
                }
            } return false;
        }
        return false;
    }
}
