package org.briarproject.briar.android.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.InterfaceNotNullByDefault;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.cardview.widget.CardView;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;

@InterfaceNotNullByDefault
public class InfoView extends CardView {

	public InfoView(Context context) {
		this(context, null);
	}

	public InfoView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public InfoView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);
		LayoutInflater inflater = (LayoutInflater)
				context.getSystemService(LAYOUT_INFLATER_SERVICE);
		inflater.inflate(R.layout.info_view, this, true);
	}

	public void setText(@StringRes int resId) {
		TextView infoText = findViewById(R.id.info_text);
		infoText.setText(resId);
	}
}
