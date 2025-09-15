package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.feed.Feed;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.blog.RssFeedAdapter.RssFeedListener;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class RssFeedManageFragment extends BaseFragment
		implements RssFeedListener {
	public static final String TAG = RssFeedManageFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private RssFeedViewModel viewModel;

	private BriarRecyclerView list;
	private final RssFeedAdapter adapter = new RssFeedAdapter(this);

	public static RssFeedManageFragment newInstance() {
		return new RssFeedManageFragment();
	}

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
		requireActivity().setTitle(R.string.blogs_rss_feeds);
		View v = inflater.inflate(R.layout.fragment_rss_feed_manage,
				container, false);

		list = v.findViewById(R.id.feedList);
		list.setLayoutManager(new LinearLayoutManager(getActivity()));
		list.setAdapter(adapter);

		viewModel.getFeeds().observe(getViewLifecycleOwner(), result -> result
				.onError(e -> {
					list.setEmptyText(R.string.blogs_rss_feeds_manage_error);
					list.showData();
				})
				.onSuccess(feeds -> {
					adapter.submitList(feeds);
					if (requireNonNull(feeds).size() == 0) {
						list.showData();
					}
				})
		);

		return v;
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.rss_feed_manage_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			requireActivity().onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_rss_feeds_import) {
			showNextFragment(new RssFeedImportFragment());
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onFeedClick(Feed feed) {
		Intent i = new Intent(getActivity(), BlogActivity.class);
		i.putExtra(GROUP_ID, feed.getBlogId().getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(i);
	}

	@Override
	public void onDeleteClick(Feed feed) {
		RssFeedDeleteFeedDialogFragment dialog =
				RssFeedDeleteFeedDialogFragment.newInstance(feed.getBlogId());
		dialog.show(getParentFragmentManager(),
				RssFeedDeleteFeedDialogFragment.TAG);
	}
}
