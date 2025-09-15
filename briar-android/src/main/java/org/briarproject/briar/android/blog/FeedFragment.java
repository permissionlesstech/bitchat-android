package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.blog.BaseViewModel.ListUpdate;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.briar.api.blog.Blog;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.blog.BlogPostFragment.POST_ID;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class FeedFragment extends BaseFragment
		implements OnBlogPostClickListener {

	public final static String TAG = FeedFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private FeedViewModel viewModel;
	private final BlogPostAdapter adapter = new BlogPostAdapter(true, this);
	private LinearLayoutManager layoutManager;
	private BriarRecyclerView list;

	public static FeedFragment newInstance() {
		FeedFragment f = new FeedFragment();

		Bundle args = new Bundle();
		f.setArguments(args);

		return f;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(FeedViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		requireActivity().setTitle(R.string.blogs_button);

		View v = inflater.inflate(R.layout.fragment_blog, container, false);

		layoutManager = new LinearLayoutManager(getActivity());
		list = v.findViewById(R.id.postList);
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.il_empty_state_blogs);
		list.setEmptyText(R.string.blogs_feed_empty_state);
		list.setEmptyAction(R.string.blogs_feed_empty_state_action);

		viewModel.getBlogPosts().observe(getViewLifecycleOwner(), result ->
				result.onError(this::handleException)
						.onSuccess(this::onBlogPostsLoaded)
		);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.blockAndClearAllBlogPostNotifications();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		viewModel.unblockAllBlogPostNotifications();
		list.stopPeriodicUpdate();
	}

	private void onBlogPostsLoaded(ListUpdate update) {
		adapter.submitList(update.getItems(), () -> {
			Boolean wasLocal = update.getPostAddedWasLocal();
			if (wasLocal != null && wasLocal) {
				showSnackBar(R.string.blogs_blog_post_created, true);
				// automatically scroll to our new post
				list.smoothScrollToPosition(0);
			} else if (wasLocal != null) {
				showSnackBar(R.string.blogs_blog_post_received, false);
			}
			viewModel.resetLocalUpdate();
			list.showData();
		});
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.blogs_feed_actions, menu);
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_write_blog_post) {
			Blog personalBlog = viewModel.getPersonalBlog().getValue();
			if (personalBlog == null) return false;
			Intent i = new Intent(getActivity(), WriteBlogPostActivity.class);
			i.putExtra(GROUP_ID, personalBlog.getId().getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_rss_feeds) {
			Intent i = new Intent(getActivity(), RssFeedActivity.class);
			startActivity(i);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onBlogPostClick(BlogPostItem post) {
		Intent i = getBlogActivityIntent(post.getGroupId());
		i.putExtra(POST_ID, post.getId().getBytes());
		requireContext().startActivity(i);
	}

	@Override
	public void onAuthorClick(BlogPostItem post) {
		Intent i = getBlogActivityIntent(post.getGroupId());
		requireContext().startActivity(i);
	}

	@Override
	public void onLinkClick(String url) {
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getParentFragmentManager(), f.getUniqueTag());
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private Intent getBlogActivityIntent(GroupId groupId) {
		Intent i = new Intent(requireContext(), BlogActivity.class);
		i.putExtra(GROUP_ID, groupId.getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		return i;
	}

	private void showSnackBar(int stringRes, boolean isLocal) {
		int firstVisible =
				layoutManager.findFirstCompletelyVisibleItemPosition();
		int lastVisible = layoutManager.findLastCompletelyVisibleItemPosition();
		int count = adapter.getItemCount();
		boolean scroll = !isLocal && count > (lastVisible - firstVisible + 1);

		BriarSnackbarBuilder sb = new BriarSnackbarBuilder();
		if (scroll) {
			sb.setAction(R.string.blogs_blog_post_scroll_to,
					v -> list.smoothScrollToPosition(0));
		}
		sb.make(list, stringRes, LENGTH_LONG).show();
	}

}
