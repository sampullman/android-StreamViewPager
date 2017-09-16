package com.sampullman.pagersample;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.View.OnClickListener;

public class SampleActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sample);

        findViewById(R.id.infinite).setOnClickListener(startActivity(InfinitePagerActivity.class));
        findViewById(R.id.long_load).setOnClickListener(startActivity(LongLoadPagerActivity.class));
    }

    OnClickListener startActivity(final Class<?> cls) {
        return new OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(SampleActivity.this, cls));
            }
        };
    }
}
