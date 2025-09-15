package org.briarproject.briar.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.Button;

import org.briarproject.briar.R;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.UiThread;

import static android.view.Gravity.BOTTOM;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

@UiThread
@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class LargeTextInputView extends TextInputView {

	public LargeTextInputView(Context context) {
		this(context, null);
	}

	public LargeTextInputView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public LargeTextInputView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		// get attributes
		TypedArray attributes = context.obtainStyledAttributes(attrs,
				R.styleable.LargeTextInputView);
		String buttonText =
				attributes.getString(R.styleable.LargeTextInputView_buttonText);
		int maxLines = attributes
				.getInteger(R.styleable.LargeTextInputView_maxLines, 0);
		boolean fillHeight = attributes
				.getBoolean(R.styleable.LargeTextInputView_fillHeight, false);
		attributes.recycle();

		if (buttonText != null) setButtonText(buttonText);
		if (maxLines > 0) textInput.setMaxLines(maxLines);
		if (fillHeight) {
			ViewGroup layout = findViewById(R.id.input_layout);
			LayoutParams params = (LayoutParams) layout.getLayoutParams();
			params.height = 0;
			params.weight = 1;
			layout.setLayoutParams(params);
			ViewGroup.LayoutParams inputParams = textInput.getLayoutParams();
			inputParams.height = MATCH_PARENT;
			textInput.setLayoutParams(inputParams);
		}
		textInput.setGravity(BOTTOM);
	}

	@Override
	protected int getLayout() {
		return R.layout.text_input_view_large;
	}

	public void setButtonText(String text) {
		((Button) findViewById(R.id.compositeSendButton)).setText(text);
	}

}
