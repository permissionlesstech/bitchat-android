package org.briarproject.briar.android.test;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.ActivityComponent;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.api.test.TestDataCreator;

import javax.inject.Inject;

import androidx.appcompat.app.ActionBar;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP;
import static org.briarproject.briar.android.BriarApplication.ENTRY_ACTIVITY;

public class TestDataActivity extends BriarActivity {

	@Inject
	TestDataCreator testDataCreator;

	private TextView contactsTextView, forumsTextView, privateGroupsTextView;
	private SeekBar contactsSeekBar, messagesSeekBar, avatarsSeekBar,
			blogPostsSeekBar, forumsSeekBar, forumPostsSeekBar,
			privateGroupsSeekBar, privateGroupPostsSeekBar;

	@Override
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setHomeButtonEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setContentView(R.layout.activity_test_data);
		contactsTextView = findViewById(R.id.textViewContactsSb);
		TextView messagesTextView = findViewById(R.id.textViewMessagesSb);
		TextView avatarsTextView = findViewById(R.id.textViewAvatarsSb);
		TextView blogPostsTextView = findViewById(R.id.TextViewBlogPostsSb);
		forumsTextView = findViewById(R.id.TextViewForumsSb);
		TextView forumPostsTextView =
				findViewById(R.id.TextViewForumMessagesSb);
		privateGroupsTextView = findViewById(R.id.TextViewPrivateGroupsSb);
		TextView privateGroupPostsTextView =
				findViewById(R.id.TextViewPrivateGroupMessagesSb);
		contactsSeekBar = findViewById(R.id.seekBarContacts);
		messagesSeekBar = findViewById(R.id.seekBarMessages);
		avatarsSeekBar = findViewById(R.id.seekBarAvatars);
		blogPostsSeekBar = findViewById(R.id.seekBarBlogPosts);
		forumsSeekBar = findViewById(R.id.seekBarForums);
		forumPostsSeekBar = findViewById(R.id.seekBarForumMessages);
		privateGroupsSeekBar = findViewById(R.id.seekBarPrivateGroups);
		privateGroupPostsSeekBar =
				findViewById(R.id.seekBarPrivateGroupMessages);

		contactsSeekBar.setOnSeekBarChangeListener(
				new AbstractOnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress,
							boolean fromUser) {
						contactsTextView.setText(String.valueOf(progress + 1));
					}
				});

		messagesSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(messagesTextView));
		avatarsSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(avatarsTextView));
		blogPostsSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(blogPostsTextView));
		forumsSeekBar.setOnSeekBarChangeListener(
				new AbstractOnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress,
							boolean fromUser) {
						forumsTextView.setText(String.valueOf(progress));
						forumPostsSeekBar.setEnabled(progress > 0);
					}
				});
		forumPostsSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(forumPostsTextView));
		privateGroupsSeekBar.setOnSeekBarChangeListener(
				new AbstractOnSeekBarChangeListener() {
					@Override
					public void onProgressChanged(SeekBar seekBar, int progress,
							boolean fromUser) {
						privateGroupsTextView.setText(String.valueOf(progress));
						privateGroupPostsSeekBar.setEnabled(progress > 0);
					}
				});
		privateGroupPostsSeekBar.setOnSeekBarChangeListener(
				new OnSeekBarChangeUpdateProgress(privateGroupPostsTextView));

		findViewById(R.id.buttonZeroValues).setOnClickListener(v -> {
			contactsSeekBar.setProgress(0);
			messagesSeekBar.setProgress(0);
			avatarsSeekBar.setProgress(0);
			blogPostsSeekBar.setProgress(0);
			forumsSeekBar.setProgress(0);
			forumPostsSeekBar.setProgress(0);
			privateGroupsSeekBar.setProgress(0);
			privateGroupPostsSeekBar.setProgress(0);
		});

		findViewById(R.id.buttonCreateTestData).setOnClickListener(
				v -> createTestData());
	}

	private void createTestData() {
		testDataCreator.createTestData(contactsSeekBar.getProgress() + 1,
				messagesSeekBar.getProgress(), avatarsSeekBar.getProgress(),
				blogPostsSeekBar.getProgress(), forumsSeekBar.getProgress(),
				forumPostsSeekBar.getProgress(),
				privateGroupsSeekBar.getProgress(),
				privateGroupPostsSeekBar.getProgress());
		Intent intent = new Intent(this, ENTRY_ACTIVITY);
		intent.addFlags(FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
		finish();
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		}
		return false;
	}

	private static class OnSeekBarChangeUpdateProgress
			extends AbstractOnSeekBarChangeListener {
		private final TextView textView;

		private OnSeekBarChangeUpdateProgress(TextView textView) {
			this.textView = textView;
		}

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress,
				boolean fromUser) {
			textView.setText(String.valueOf(progress));
		}
	}

	private abstract static class AbstractOnSeekBarChangeListener
			implements OnSeekBarChangeListener {
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
		}
	}
}
