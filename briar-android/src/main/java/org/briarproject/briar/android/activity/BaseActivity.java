package org.briarproject.briar.android.activity;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import org.briarproject.briar.R;
import org.briarproject.briar.android.AndroidComponent;
import org.briarproject.briar.android.BriarApplication;
import org.briarproject.briar.android.DestroyableContext;
import org.briarproject.briar.android.Localizer;
import org.briarproject.briar.android.controller.ActivityLifecycleController;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.fragment.ScreenFilterDialogFragment;
import org.briarproject.briar.android.util.UiUtils;
import org.briarproject.briar.android.widget.TapSafeFrameLayout;
import org.briarproject.briar.android.widget.TapSafeFrameLayout.OnTapFilteredListener;
import org.briarproject.briar.api.android.ScreenFilterMonitor;
import org.briarproject.briar.api.android.ScreenFilterMonitor.AppDetails;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import static android.os.Build.VERSION.SDK_INT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static androidx.lifecycle.Lifecycle.State.STARTED;
import static java.util.Collections.emptyList;
import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.TestingConstants.PREVENT_SCREENSHOTS;
import static org.briarproject.briar.android.util.UiUtils.hideSoftKeyboard;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

/**
 * Warning: Some activities don't extend {@link BaseActivity}.
 */
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class BaseActivity extends AppCompatActivity
		implements DestroyableContext, OnTapFilteredListener {

	private final static Logger LOG = getLogger(BaseActivity.class.getName());

	@Inject
	protected ScreenFilterMonitor screenFilterMonitor;

	protected ActivityComponent activityComponent;

	private final List<ActivityLifecycleController> lifecycleControllers =
			new ArrayList<>();
	private boolean destroyed = false;

	@Nullable
	private Toolbar toolbar = null;
	private boolean searchedForToolbar = false;

	public abstract void injectActivity(ActivityComponent component);

	public void addLifecycleController(ActivityLifecycleController alc) {
		lifecycleControllers.add(alc);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		// create the ActivityComponent *before* calling super.onCreate()
		// because it already attaches fragments which need access
		// to the component for their own injection
		AndroidComponent applicationComponent =
				((BriarApplication) getApplication()).getApplicationComponent();
		activityComponent = DaggerActivityComponent.builder()
				.androidComponent(applicationComponent)
				.activityModule(getActivityModule())
				.build();
		injectActivity(activityComponent);
		super.onCreate(state);
		if (LOG.isLoggable(INFO)) {
			LOG.info("Creating " + getClass().getSimpleName());
		}

		// WARNING: When removing this or making it possible to turn it off,
		//          we need a solution for the app lock feature.
		//          When the app is locked by a timeout and FLAG_SECURE is not
		//          set, the app content becomes visible briefly before the
		//          unlock screen is shown.
		if (PREVENT_SCREENSHOTS) getWindow().addFlags(FLAG_SECURE);

		if (SDK_INT >= 31) getWindow().setHideOverlayWindows(true);

		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityCreate(this);
		}
	}

	@Override
	protected void attachBaseContext(Context base) {
		super.attachBaseContext(
				Localizer.getInstance().setLocale(base));
		Localizer.getInstance().setLocale(this);
	}

	public ActivityComponent getActivityComponent() {
		return activityComponent;
	}

	// This exists to make test overrides easier
	protected ActivityModule getActivityModule() {
		return new ActivityModule(this);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (LOG.isLoggable(INFO)) {
			LOG.info("Starting " + getClass().getSimpleName());
		}
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityStart();
		}
		protectToolbar();
		ScreenFilterDialogFragment f = findDialogFragment();
		if (f != null) f.setDismissListener(this::protectToolbar);
	}

	@Nullable
	private ScreenFilterDialogFragment findDialogFragment() {
		Fragment f = getSupportFragmentManager().findFragmentByTag(
				ScreenFilterDialogFragment.TAG);
		return (ScreenFilterDialogFragment) f;
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (LOG.isLoggable(INFO)) {
			LOG.info("Resuming " + getClass().getSimpleName());
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (LOG.isLoggable(INFO)) {
			LOG.info("Pausing " + getClass().getSimpleName());
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (LOG.isLoggable(INFO)) {
			LOG.info("Stopping " + getClass().getSimpleName());
		}
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityStop();
		}
	}

	protected void showInitialFragment(BaseFragment f) {
		getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, f, f.getUniqueTag())
				.commit();
	}

	public void showNextFragment(BaseFragment f) {
		if (!getLifecycle().getCurrentState().isAtLeast(STARTED)) return;
		showFragment(getSupportFragmentManager(), f, f.getUniqueTag());
	}

	private boolean showScreenFilterWarning() {
		if (((BriarApplication) getApplication()).isInstrumentationTest()) {
			return false;
		}
		// If the dialog is already visible, filter the tap
		ScreenFilterDialogFragment f = findDialogFragment();
		if (f != null && f.isVisible()) return false;
		Collection<AppDetails> apps;
		// querying all apps is only possible at API 29 and below
		if (SDK_INT <= 29) {
			apps = screenFilterMonitor.getApps();
			// If all overlay apps have been allowed, allow the tap
			if (apps.isEmpty()) return true;
		} else {
			apps = emptyList();
		}
		// Show dialog unless onSaveInstanceState() has been called, see #1112
		FragmentManager fm = getSupportFragmentManager();
		if (!fm.isStateSaved()) {
			// Create dialog
			f = ScreenFilterDialogFragment.newInstance(apps);
			// When dialog is dismissed, update protection of toolbar
			f.setDismissListener(this::protectToolbar);
			// Hide soft keyboard when (re)showing dialog
			View focus = getCurrentFocus();
			if (focus != null) hideSoftKeyboard(focus);
			f.show(fm, ScreenFilterDialogFragment.TAG);
		}
		// Filter the tap
		return false;
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		if (LOG.isLoggable(INFO)) {
			LOG.info("Destroying " + getClass().getSimpleName());
		}
		destroyed = true;
		for (ActivityLifecycleController alc : lifecycleControllers) {
			alc.onActivityDestroy();
		}
	}

	@Override
	public void runOnUiThreadUnlessDestroyed(Runnable r) {
		runOnUiThread(() -> {
			if (!destroyed && !isFinishing()) r.run();
		});
	}

	@UiThread
	public void handleException(Exception e) {
		supportFinishAfterTransition();
	}

	/*
	 * Wraps the given view in a wrapper that notifies this activity when an
	 * obscured touch has been filtered, and returns the wrapper.
	 */
	private View makeTapSafeWrapper(View v) {
		TapSafeFrameLayout wrapper = new TapSafeFrameLayout(this);
		wrapper.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		wrapper.setOnTapFilteredListener(this);
		wrapper.addView(v);
		return wrapper;
	}

	/*
	 * Finds the AppCompat toolbar, if any, and configures it to filter
	 * obscured touches. If a custom toolbar is used, it will be part of the
	 * content view and thus protected by the wrapper. But the default toolbar
	 * is outside the wrapper.
	 */
	private void protectToolbar() {
		findToolbar();
		if (toolbar != null) {
			boolean filter;
			if (SDK_INT <= 29) {
				filter = !screenFilterMonitor.getApps().isEmpty();
			} else {
				filter = true;
			}
			UiUtils.setFilterTouchesWhenObscured(toolbar, filter);
		}
	}

	private void findToolbar() {
		if (searchedForToolbar) return;
		View decorView = getWindow().getDecorView();
		if (decorView instanceof ViewGroup)
			toolbar = findToolbar((ViewGroup) decorView);
		searchedForToolbar = true;
	}

	@Nullable
	private Toolbar findToolbar(ViewGroup vg) {
		// Views inside tap-safe layouts are already protected
		if (vg instanceof TapSafeFrameLayout) return null;
		for (int i = 0, len = vg.getChildCount(); i < len; i++) {
			View child = vg.getChildAt(i);
			if (child instanceof Toolbar) return (Toolbar) child;
			if (child instanceof ViewGroup) {
				Toolbar toolbar = findToolbar((ViewGroup) child);
				if (toolbar != null) return toolbar;
			}
		}
		return null;
	}

	@Override
	public void setContentView(@LayoutRes int layoutRes) {
		setContentView(getLayoutInflater().inflate(layoutRes, null));
	}

	@Override
	public void setContentView(View v) {
		super.setContentView(makeTapSafeWrapper(v));
	}

	@Override
	public void setContentView(View v, LayoutParams layoutParams) {
		super.setContentView(makeTapSafeWrapper(v), layoutParams);
	}

	@Override
	public void addContentView(View v, LayoutParams layoutParams) {
		super.addContentView(makeTapSafeWrapper(v), layoutParams);
	}

	@Override
	public boolean shouldAllowTap() {
		return showScreenFilterWarning();
	}
}
