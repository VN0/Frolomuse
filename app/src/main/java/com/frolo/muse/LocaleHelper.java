package com.frolo.muse;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.Locale;


public final class LocaleHelper {

    @NonNull
    public static Context applyLanguage(@NonNull Context context, @NonNull String lang) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return applyLanguageApi24Impl(context, lang);
        } else {
            return applyLanguageImpl(context, lang);
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    @NonNull
    private static Context applyLanguageApi24Impl(@NonNull Context context, @NonNull String lang) {
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Configuration configuration = context.getResources().getConfiguration();
        configuration.setLocale(locale);
        configuration.setLayoutDirection(locale);

        Configuration newConfiguration = new Configuration(configuration);

        return context.createConfigurationContext(newConfiguration);
    }

    // TODO: not working on Android API 23
    // Legacy approach
    @NonNull
    private static Context applyLanguageImpl(@NonNull Context context, @NonNull String lang) {
        Locale locale = new Locale(lang);
        Locale.setDefault(locale);

        Resources resources = context.getResources();

        Configuration configuration = resources.getConfiguration();
        configuration.locale = locale;
        configuration.setLayoutDirection(locale);

        resources.updateConfiguration(configuration, resources.getDisplayMetrics());

        return context;
    }

    private LocaleHelper() {
    }
}
