package org.briarproject.briar.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import javax.annotation.Nullable;

import static android.os.Build.VERSION.SDK_INT;
import static org.briarproject.briar.android.settings.DisplayFragment.PREF_LANGUAGE;

@NotNullByDefault
public class Localizer {

	// Locking: class
	@Nullable
	private static Localizer INSTANCE;
	private final Locale systemLocale;
	private final Locale locale;

	private Localizer(SharedPreferences sharedPreferences) {
		this(Locale.getDefault(), getLocaleFromTag(
				sharedPreferences.getString(PREF_LANGUAGE, "default")));
	}

	private Localizer(Locale systemLocale, @Nullable Locale userLocale) {
		this.systemLocale = systemLocale;
		if (userLocale == null) locale = systemLocale;
		else locale = userLocale;
	}

	// Instantiate the Localizer.
	public static synchronized void initialize(SharedPreferences prefs) {
		if (INSTANCE == null)
			INSTANCE = new Localizer(prefs);
	}

	// Reinstantiate the Localizer with the system locale
	public static synchronized void reinitialize() {
		if (INSTANCE != null)
			INSTANCE = new Localizer(INSTANCE.systemLocale, null);
	}

	// Get the current instance.
	public static synchronized Localizer getInstance() {
		if (INSTANCE == null)
			throw new IllegalStateException("Localizer not initialized");
		return INSTANCE;
	}

	// Get Locale from BCP-47 tag
	@Nullable
	public static Locale getLocaleFromTag(String tag) {
		if (tag.equals("default")) return null;
		return Locale.forLanguageTag(tag);
	}

	/*
	 * Apply localization to the specified context.
	 *
	 * It updates the configuration of the context's resources object but can
	 * also return a new context derived from the context parameter. Hence
	 * make sure to work with the return value of this method instead of
	 * the context you passed as a parameter.
	 *
	 * This method also has side-effects as it calls Locale#setDefault().
	 *
	 * When using this in attachBaseContext() of Application, Service or
	 * Activity subclasses, it is important to not only apply this method to the
	 * base Context parameter received in that method, but also apply it on the
	 * class itself which also extends Context.
	 */
	public Context setLocale(Context context) {
		Resources res = context.getResources();
		Configuration conf = res.getConfiguration();
		Locale currentLocale;
		if (SDK_INT >= 24) {
			currentLocale = conf.getLocales().get(0);
		} else
			currentLocale = conf.locale;
		if (locale.equals(currentLocale))
			return context;
		Locale.setDefault(locale);
		conf.setLocale(locale);
		context = context.createConfigurationContext(conf);
		res.updateConfiguration(conf, res.getDisplayMetrics());
		return context;
	}
}
