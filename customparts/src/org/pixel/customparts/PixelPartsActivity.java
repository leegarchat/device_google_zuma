package org.pixel.customparts;

import android.os.Bundle;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class PixelPartsActivity extends CollapsingToolbarBaseActivity {

    private static final String TAG_PARTS = "pixel_parts";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        getSupportFragmentManager().beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                        new PixelPartsFragment(), TAG_PARTS)
                .commit();
    }
}