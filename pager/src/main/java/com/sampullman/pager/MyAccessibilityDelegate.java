package com.sampullman.pager;

import android.os.Bundle;
import android.support.v4.view.AccessibilityDelegateCompat;
import android.support.v4.view.PagerAdapter;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

public class MyAccessibilityDelegate<T> extends AccessibilityDelegateCompat {
    private StreamViewPager<T> pager;

    public MyAccessibilityDelegate(StreamViewPager<T> pager) {
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
        StreamViewAdapter<T> adapter = pager.getAdapter();
        info.setScrollable(adapter != null && adapter.hasAtLeastOneItem());

        T curItem = pager.getCurrentViewId();

        if (adapter != null && adapter.hasNext(curItem)) {
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
        }
        if (adapter != null && adapter.hasPrev(curItem)) {
            info.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
        }
    }
    @Override
    public boolean performAccessibilityAction(View host, int action, Bundle args) {
        if (super.performAccessibilityAction(host, action, args)) {
            return true;
        }
        StreamViewAdapter<T> adapter = pager.getAdapter();
        T curItem = pager.getCurrentViewId();
        switch (action) {
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD: {
                if(adapter != null) {
                    T nextId = adapter.nextId(curItem);
                    if (nextId != null) {
                        pager.setCurrentItem(nextId);
                        return true;
                    }
                }
            } return false;
            case AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD: {
                if(adapter != null) {
                    T prevId = adapter.prevId(curItem);
                    if (prevId != null) {
                        pager.setCurrentItem(prevId);
                        return true;
                    }
                }
            } return false;
        }
        return false;
    }
}
