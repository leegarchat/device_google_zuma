package org.pixel.customparts;

import android.os.Bundle;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import org.pixel.customparts.parts.DozePart;
import org.pixel.customparts.parts.ImsPart;
import org.pixel.customparts.parts.LauncherPart;
import org.pixel.customparts.parts.Part;
import org.pixel.customparts.parts.ThermalPart;

import java.util.ArrayList;
import java.util.List;

public class PixelPartsFragment extends PreferenceFragmentCompat 
        implements Preference.OnPreferenceChangeListener {

    private List<Part> mParts = new ArrayList<>();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.pixel_custom_parts_settings, rootKey);
        mParts.add(new ImsPart());
        mParts.add(new LauncherPart());
        mParts.add(new DozePart());
        mParts.add(new ThermalPart());
        for (Part part : mParts) {
            part.onCreate(this);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // Перебираем модули, пока один из них не обработает клик
        for (Part part : mParts) {
            if (part.onPreferenceChange(preference, newValue)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        for (Part part : mParts) part.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        for (Part part : mParts) part.onPause();
    }
}