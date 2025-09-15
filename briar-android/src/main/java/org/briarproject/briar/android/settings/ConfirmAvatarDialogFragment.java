package org.briarproject.briar.android.settings;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.BaseActivity;
import org.briarproject.briar.android.conversation.glide.GlideApp;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;

import static java.util.Objects.requireNonNull;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConfirmAvatarDialogFragment extends DialogFragment {

	final static String TAG = ConfirmAvatarDialogFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	private SettingsViewModel viewModel;

	private static final String ARG_URI = "uri";
	private Uri uri;

	static ConfirmAvatarDialogFragment newInstance(Uri uri) {
		ConfirmAvatarDialogFragment f = new ConfirmAvatarDialogFragment();

		Bundle args = new Bundle();
		args.putString(ARG_URI, uri.toString());
		f.setArguments(args);

		return f;
	}

	@Override
	public void onAttach(Context ctx) {
		super.onAttach(ctx);
		((BaseActivity) requireActivity()).getActivityComponent().inject(this);
		ViewModelProvider provider =
				new ViewModelProvider(requireActivity(), viewModelFactory);
		viewModel = provider.get(SettingsViewModel.class);
	}

	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		Bundle args = requireArguments();
		String argUri = requireNonNull(args.getString(ARG_URI));
		uri = Uri.parse(argUri);

		FragmentActivity activity = requireActivity();
		LayoutInflater inflater = LayoutInflater.from(activity);
		final View view =
				inflater.inflate(R.layout.fragment_confirm_avatar_dialog, null);
		ImageView imageView = view.findViewById(R.id.image);
		TextView textViewUserName = view.findViewById(R.id.username);

		GlideApp.with(imageView)
				.load(uri)
				.diskCacheStrategy(DiskCacheStrategy.NONE)
				.error(R.drawable.ic_image_broken)
				.into(imageView)
				.waitForLayout();

		// we can't use getViewLifecycleOwner() here
		// as this fragment technically doesn't have a view
		viewModel.getOwnIdentityInfo().observe(activity, us ->
				textViewUserName.setText(us.getLocalAuthor().getName())
		);

		int theme = R.style.BriarDialogTheme;
		return new MaterialAlertDialogBuilder(activity, theme)
				.setView(view)
				.setTitle(R.string.dialog_confirm_profile_picture_title)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.change, (d, id) ->
						viewModel.setAvatar(uri)
				)
				.create();
	}

}
