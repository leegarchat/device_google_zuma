package org.pixel.customparts.parts;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public interface Part {
    void onCreate(PreferenceFragmentCompat fragment);
    void onResume();
    void onPause();
    boolean onPreferenceChange(Preference preference, Object newValue);
}