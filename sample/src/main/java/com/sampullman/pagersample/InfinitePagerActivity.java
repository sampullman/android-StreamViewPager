package com.sampullman.pagersample;

import android.content.Context;
import android.os.Bundle;
import android.support.v4.view.PagerAdapter;
import android.support.v7.app.AppCompatActivity;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

import com.sampullman.pager.StreamViewPager;

public class InfinitePagerActivity extends AppCompatActivity {
    StreamViewPager pager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        pager = new StreamViewPager(this);
        pager.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        pager.setAdapter(new InfinitePagerAdapter(this));
        setContentView(pager);
    }

    OnClickListener pageListener(final int direction) {
        return new OnClickListener() {
            @Override
            public void onClick(View view) {
                pager.setCurrentItem(pager.getCurrentItem()+direction);
            }
        };
    }

    Button pagerButton(String text, int direction) {
        Button b = new Button(this);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
        b.setGravity(Gravity.CENTER);
        b.setText(text);
        b.setOnClickListener(pageListener(direction));
        b.setBackgroundResource(0);
        return b;
    }

    public class InfinitePagerAdapter extends PagerAdapter {

        private Context context;

        public InfinitePagerAdapter(Context context) {
            this.context = context;
        }

        @Override
        public Object instantiateItem(ViewGroup collection, int position) {
            LinearLayout layout = new LinearLayout(context);
            layout.setLayoutParams(new ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT));
            collection.addView(layout);

            layout.addView(pagerButton("<", -1));

            TextView tv = new TextView(context);
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, MATCH_PARENT, 1));
            tv.setText(String.format("Page %d", position+1));
            tv.setTextSize(24);
            tv.setGravity(Gravity.CENTER);
            layout.addView(tv);

            layout.addView(pagerButton(">", 1));
            return layout;
        }

        @Override
        public void destroyItem(ViewGroup collection, int position, Object view) {
            collection.removeView((View) view);
        }

        @Override
        public int getCount() {
            return 10;
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            return String.format("Page %d", position+1);
        }

    }

}
