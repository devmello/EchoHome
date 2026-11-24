# Feature entry points are referenced directly from FeatureList, so nothing needs keeping by name yet.
# Feature modules add their own rules (JNI classes for AirPlay, etc.) in their consumer-rules.pro.

# SharedPreferences holds its change listeners weakly. PrefsSettingsStore keeps a strong reference
# in a field that is only written, so R8 removed the field, the listener was garbage collected and
# release builds stopped applying settings-page changes live (SettingsStore.changes went silent).
-keepclassmembers class app.vesper.core.data.PrefsSettingsStore {
    android.content.SharedPreferences$OnSharedPreferenceChangeListener listener;
}
