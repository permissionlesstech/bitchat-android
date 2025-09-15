package org.briarproject.briar.android.blog;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.fragment.BaseFragment;
import org.briarproject.briar.android.widget.LinkDialogFragment;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;
import static java.util.Objects.requireNonNull;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.briar.android.activity.BriarActivity.GROUP_ID;
import static org.briarproject.briar.android.util.UiUtils.MIN_DATE_RESOLUTION;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class BlogPostFragment extends BaseFragment
		implements OnBlogPostClickListener {

	private static final String TAG = BlogPostFragment.class.getName();
	private static final Logger LOG = getLogger(TAG);

	static final String POST_ID = "briar.POST_ID";

	protected BlogViewModel viewModel;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private ProgressBar progressBar;
	private BlogPostViewHolder ui;
	private BlogPostItem post;
	private Runnable refresher;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	static BlogPostFragment newInstance(GroupId blogId, MessageId postId) {
		BlogPostFragment f = new BlogPostFragment();
		Bundle bundle = new Bundle();
		bundle.putByteArray(GROUP_ID, blogId.getBytes());
		bundle.putByteArray(POST_ID, postId.getBytes());
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
		GroupId groupId =
				new GroupId(requireNonNull(args.getByteArray(GROUP_ID)));
		MessageId postId =
				new MessageId(requireNonNull(args.getByteArray(POST_ID)));

		View view = inflater.inflate(R.layout.fragment_blog_post, container,
				false);
		progressBar = view.findViewById(R.id.progressBar);
		progressBar.setVisibility(VISIBLE);
		ui = new BlogPostViewHolder(view, true, this, false);
		LifecycleOwner owner = getViewLifecycleOwner();
		viewModel.loadBlogPost(groupId, postId).observe(owner, result ->
				result.onError(this::handleException)
						.onSuccess(this::onBlogPostLoaded)
		);
		return view;
	}

	@Override
	public void onStart() {
		super.onStart();
		startPeriodicUpdate();
	}

	@Override
	public void onStop() {
		super.onStop();
		stopPeriodicUpdate();
	}

	@UiThread
	private void onBlogPostLoaded(BlogPostItem post) {
		progressBar.setVisibility(INVISIBLE);
		this.post = post;
		ui.bindItem(post);
	}

	@Override
	public void onBlogPostClick(BlogPostItem post) {
		// We're already there
	}

	@Override
	public void onAuthorClick(BlogPostItem post) {
		Intent i = new Intent(requireContext(), BlogActivity.class);
		i.putExtra(GROUP_ID, post.getGroupId().getBytes());
		i.setFlags(FLAG_ACTIVITY_CLEAR_TOP);
		requireContext().startActivity(i);
	}

	@Override
	public void onLinkClick(String url) {
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getParentFragmentManager(), f.getUniqueTag());
	}

	private void startPeriodicUpdate() {
		refresher = () -> {
			LOG.info("Updating Content...");
			ui.updateDate(post.getTimestamp());
			handler.postDelayed(refresher, MIN_DATE_RESOLUTION);
		};
		LOG.info("Adding Handler Callback");
		handler.postDelayed(refresher, MIN_DATE_RESOLUTION);
	}

	private void stopPeriodicUpdate() {
		if (refresher != null) {
			LOG.info("Removing Handler Callback");
			handler.removeCallbacks(refresher);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}

}
