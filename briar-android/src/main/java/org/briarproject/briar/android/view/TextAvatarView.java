package org.briarproject.briar.android.view;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.briarproject.briar.R;

import java.util.Locale;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;
import androidx.appcompat.widget.AppCompatTextView;
import de.hdodenhof.circleimageview.CircleImageView;

@UiThread
public class TextAvatarView extends FrameLayout {

	private final AppCompatTextView character;
	private final CircleImageView background;
	private final TextView badge;

	public TextAvatarView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);

		LayoutInflater inflater = (LayoutInflater) context
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		inflater.inflate(R.layout.text_avatar_view, this, true);
		character = findViewById(R.id.textAvatarView);
		background = findViewById(R.id.avatarBackground);
		badge = findViewById(R.id.unreadCountView);
	}

	public TextAvatarView(Context context) {
		this(context, null);
	}

	public void setText(String text) {
		character.setText(text.toUpperCase());
	}

	public void setUnreadCount(int count) {
		if (count > 0) {
			badge.setText(String.format(Locale.getDefault(), "%d", count));
			badge.setVisibility(VISIBLE);
		} else {
			badge.setVisibility(INVISIBLE);
		}
	}

	public void setBackgroundBytes(byte[] bytes) {
		int r = getByte(bytes, 0) * 3 / 4 + 96;
		int g = getByte(bytes, 1) * 3 / 4 + 96;
		int b = getByte(bytes, 2) * 3 / 4 + 96;
		int color = Color.rgb(r, g, b);

		background.setImageDrawable(new ColorDrawable(color));
	}

	private byte getByte(byte[] bytes, int index) {
		if (bytes == null) {
			return -128;
		} else {
			return bytes[index % bytes.length];
		}
	}

}
