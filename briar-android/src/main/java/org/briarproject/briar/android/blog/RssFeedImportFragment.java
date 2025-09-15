package org.briarproject.briar.android.blog;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.fragment.ProgressFragment;
import org.briarproject.briar.android.util.ActivityLaunchers.GetContentAdvanced;
import org.briarproject.briar.android.util.ActivityLaunchers.OpenDocumentAdvanced;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
import static org.briarproject.briar.android.util.UiUtils.hideSoftKeyboard;
import static org.briarproject.briar.android.util.UiUtils.launchActivityToOpenFile;
import static org.briarproject.briar.android.util.UiUtils.showFragment;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RssFeedImportFragment extends BaseFragment {
	public static final String TAG = RssFeedImportFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private RssFeedViewModel viewModel;

	private EditText urlInput;
	private Button importButton;
	private ProgressBar progressBar;

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenDocumentAdvanced(),
					this::onFileChosen);

	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetContentAdvanced(),
					this::onFileChosen);

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(RssFeedViewModel.class);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(getString(R.string.blogs_rss_feeds_import));
		setHasOptionsMenu(true);
		View v = inflater.inflate(R.layout.fragment_rss_feed_import,
				container, false);

		urlInput = v.findViewById(R.id.urlInput);
		urlInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				enableOrDisableImportButton();
			}
		});
		urlInput.setOnEditorActionListener((view, actionId, event) -> {
			if (actionId == IME_ACTION_DONE && importButton.isEnabled() &&
					importButton.getVisibility() == VISIBLE) {
				publish();
				return true;
			}
			return false;
		});

		importButton = v.findViewById(R.id.importButton);
		importButton.setOnClickListener(view -> publish());

		progressBar = v.findViewById(R.id.progressBar);

		viewModel.getIsImporting().observe(getViewLifecycleOwner(),
				this::onIsImporting);

		return v;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.rss_feed_import_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_import_file) {
			launchActivityToOpenFile(requireContext(), docLauncher,
					contentLauncher, "*/*");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void onFileChosen(@Nullable Uri uri) {
		if (uri == null) return;
		// show progress fragment
		Fragment f = ProgressFragment.newInstance(
				getString(R.string.blogs_rss_feeds_import_progress));
		String tag = ProgressFragment.TAG;
		showFragment(getParentFragmentManager(), f, tag);
		// view model will import and change state that activity will react to
		viewModel.importFeed(uri);
	}

	private void enableOrDisableImportButton() {
		String url = urlInput.getText().toString();
		importButton.setEnabled(viewModel.validateAndNormaliseUrl(url) != null);
	}

	private void publish() {
		String url = viewModel
				.validateAndNormaliseUrl(urlInput.getText().toString());
		if (url == null) throw new AssertionError();
		viewModel.importFeed(url);
	}

	private void onIsImporting(Boolean importing) {
		if (importing) {
			// show progress bar, hide import button
			importButton.setVisibility(GONE);
			progressBar.setVisibility(VISIBLE);
			hideSoftKeyboard(urlInput);
		} else {
			// show publish button, hide progress bar
			importButton.setVisibility(VISIBLE);
			progressBar.setVisibility(GONE);
		}
	}
}
