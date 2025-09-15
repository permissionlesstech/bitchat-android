package org.briarproject.briar.android.settings;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import de.hdodenhof.circleimageview.CircleImageView;

import static org.briarproject.briar.android.view.AuthorView.setAvatar;

@NotNullByDefault
public class AvatarPreference extends Preference {

	@Nullable
	private OwnIdentityInfo info;

	public AvatarPreference(Context context, AttributeSet attrs) {
		super(context, attrs);
		setLayoutResource(R.layout.preference_avatar);
	}

	@Override
	public void onBindViewHolder(PreferenceViewHolder holder) {
		super.onBindViewHolder(holder);
		View v = holder.itemView;
		if (info != null) {
			TextView textViewUserName = v.findViewById(R.id.username);
			CircleImageView imageViewAvatar = v.findViewById(R.id.avatarImage);
			textViewUserName.setText(info.getLocalAuthor().getName());
			setAvatar(imageViewAvatar, info.getLocalAuthor().getId(),
					info.getAuthorInfo());
		}
	}

	void setOwnIdentityInfo(OwnIdentityInfo info) {
		this.info = info;
		notifyChanged();
	}

}
