package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.blog.BaseViewModel.ListUpdate;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.sharing.BlogSharingStatusActivity;
import org.briarproject.briar.android.sharing.ShareBlogActivity;
import org.briarproject.briar.android.util.BriarSnackbarBuilder;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;

import static android.app.Activity.RESULT_OK;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.widget.Toast.LENGTH_SHORT;
import static com.google.android.material.snackbar.Snackbar.LENGTH_LONG;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.activity.RequestCodes.REQUEST_SHARE_BLOG;

@UiThread
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class BlogFragment extends BaseFragment
		implements OnBlogPostClickListener {

	private final static String TAG = BlogFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private GroupId groupId;
	private BlogViewModel viewModel;
	private final BlogPostAdapter adapter = new BlogPostAdapter(false, this);
	private BriarRecyclerView list;

	static BlogFragment newInstance(GroupId groupId) {
		BlogFragment f = new BlogFragment();

		Bundle bundle = new Bundle();
		bundle.putByteArray(GROUP_ID, groupId.getBytes());

		f.setArguments(bundle);
		return f;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(BlogViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		Bundle args = requireArguments();
		byte[] b = args.getByteArray(GROUP_ID);
		if (b == null) throw new IllegalStateException("No group ID in args");
		groupId = new GroupId(b);

		View v = inflater.inflate(R.layout.fragment_blog, container, false);

		list = v.findViewById(R.id.postList);
		LayoutManager layoutManager = new LinearLayoutManager(getActivity());
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.showProgressBar();
		list.setEmptyText(getString(R.string.blogs_other_blog_empty_state));

		viewModel.getBlogPosts().observe(getViewLifecycleOwner(), result ->
				result.onError(this::handleException)
						.onSuccess(this::onBlogPostsLoaded)
		);
		viewModel.getBlogRemoved().observe(getViewLifecycleOwner(), removed -> {
			if (removed) finish();
		});
		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		viewModel.blockAndClearNotifications();
		list.startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		viewModel.unblockNotifications();
		list.stopPeriodicUpdate();
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.blogs_blog_actions, menu);
		MenuItem writeButton = menu.findItem(R.id.action_write_blog_post);
		MenuItem deleteButton = menu.findItem(R.id.action_blog_delete);
		viewModel.getBlog().observe(getViewLifecycleOwner(), blog -> {
			if (blog.isOurs()) writeButton.setVisible(true);
			if (blog.canBeRemoved()) deleteButton.setEnabled(true);
		});
		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == R.id.action_write_blog_post) {
			Intent i = new Intent(getActivity(), WriteBlogPostActivity.class);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_blog_share) {
			Intent i = new Intent(getActivity(), ShareBlogActivity.class);
			i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivityForResult(i, REQUEST_SHARE_BLOG);
			return true;
		} else if (itemId == R.id.action_blog_sharing_status) {
			Intent i =
					new Intent(getActivity(), BlogSharingStatusActivity.class);
			i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
			i.putExtra(GROUP_ID, groupId.getBytes());
			startActivity(i);
			return true;
		} else if (itemId == R.id.action_blog_delete) {
			showDeleteDialog();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_SHARE_BLOG && result == RESULT_OK) {
			displaySnackbar(R.string.blogs_sharing_snackbar, false);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	private void onBlogPostsLoaded(ListUpdate update) {
		adapter.submitList(update.getItems(), () -> {
			Boolean wasLocal = update.getPostAddedWasLocal();
			if (wasLocal != null && wasLocal) {
				list.scrollToPosition(0);
				displaySnackbar(R.string.blogs_blog_post_created,
						false);
			} else if (wasLocal != null) {
				displaySnackbar(R.string.blogs_blog_post_received,
						true);
			}
			viewModel.resetLocalUpdate();
			list.showData();
		});
	}

	@Override
	public void onBlogPostClick(BlogPostItem post) {
		BlogPostFragment f =
				BlogPostFragment.newInstance(groupId, post.getId());
		showNextFragment(f);
	}

	@Override
	public void onAuthorClick(BlogPostItem post) {
		if (post.getGroupId().equals(groupId) || getContext() == null) {
			// We're already there
			return;
		}
		Intent i = new Intent(getContext(), BlogActivity.class);
		i.putExtra(GROUP_ID, post.getGroupId().getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		getContext().startActivity(i);
	}

	@Override
	public void onLinkClick(String url) {
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getParentFragmentManager(), f.getUniqueTag());
	}

	private void displaySnackbar(int stringId, boolean scroll) {
		BriarSnackbarBuilder sb = new BriarSnackbarBuilder();
		if (scroll) {
			sb.setAction(R.string.blogs_blog_post_scroll_to,
					v -> list.smoothScrollToPosition(0));
		}
		sb.make(list, stringId, LENGTH_LONG).show();
	}

	private void showDeleteDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
				requireContext(), R.style.BriarDialogTheme);
		builder.setTitle(getString(R.string.blogs_remove_blog));
		builder.setMessage(
				getString(R.string.blogs_remove_blog_dialog_message));
		builder.setPositiveButton(R.string.cancel, null);
		builder.setNegativeButton(R.string.blogs_remove_blog_ok,
				(dialog, which) -> deleteBlog());
		builder.show();
	}

	private void deleteBlog() {
		viewModel.deleteBlog();
		Toast.makeText(getActivity(), R.string.blogs_blog_removed, LENGTH_SHORT)
				.show();
		finish();
	}

}
