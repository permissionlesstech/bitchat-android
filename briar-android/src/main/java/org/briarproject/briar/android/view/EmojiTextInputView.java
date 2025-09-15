package org.briarproject.briar.android.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.vanniktech.emoji.EmojiPopup;
import com.vanniktech.emoji.RecentEmoji;

import org.briarproject.briar.R;
import org.briarproject.briar.android.BriarApplication;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.AppCompatImageButton;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.view.KeyEvent.KEYCODE_ENTER;
import static android.view.inputmethod.EditorInfo.IME_ACTION_SEND;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static java.lang.Character.isWhitespace;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.utf8IsTooLong;
import static org.briarproject.briar.android.util.UiUtils.resolveColorAttribute;

public class EmojiTextInputView extends LinearLayout implements
		TextWatcher {

	@Inject
	RecentEmoji recentEmoji;

	private final AppCompatImageButton emojiToggle;
	private final EmojiPopup emojiPopup;
	private final EditText editText;
	private final InputMethodManager imm;

	@Nullable
	private TextInputListener listener;
	@Nullable
	private OnKeyboardShownListener keyboardShownListener;
	private int maxLength = Integer.MAX_VALUE;
	private boolean emptyTextAllowed = false;
	private boolean isEmpty = true;
	private boolean keyboardOpen = false;

	public EmojiTextInputView(Context context) {
		this(context, null);
	}

	public EmojiTextInputView(Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public EmojiTextInputView(Context context, @Nullable AttributeSet attrs,
			int defStyleAttr) {
		super(context, attrs, defStyleAttr);

		// inflate layout
		LayoutInflater inflater = (LayoutInflater) requireNonNull(
				context.getSystemService(LAYOUT_INFLATER_SERVICE));
		inflater.inflate(R.layout.emoji_text_input_view, this, true);

		// get attributes
		TypedArray a = context.obtainStyledAttributes(attrs,
				R.styleable.EmojiTextInputView);
		int paddingBottom = a.getDimensionPixelSize(
				R.styleable.EmojiTextInputView_textPaddingBottom, 0);
		int paddingEnd = a.getDimensionPixelSize(
				R.styleable.EmojiTextInputView_textPaddingEnd, 0);
		int maxLines =
				a.getInteger(R.styleable.EmojiTextInputView_maxTextLines, 0);
		a.recycle();

		// apply attributes to editText
		editText = findViewById(R.id.input_text);
		editText.setPadding(0, 0, paddingEnd, paddingBottom);
		if (maxLines > 0) editText.setMaxLines(maxLines);
		editText.addTextChangedListener(this);
		editText.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == IME_ACTION_SEND) {
				listener.onSendEvent();
				hideSoftKeyboard();
				return true;
			}
			return false;
		});
		// also support sending with Ctrl+Enter
		editText.setOnKeyListener((v, keyCode, event) -> {
			if (listener != null && keyCode == KEYCODE_ENTER &&
					event.isCtrlPressed()) {
				listener.onSendEvent();
				return true;
			}
			return false;
		});
		emojiToggle = findViewById(R.id.emoji_toggle);

		// stuff we can't do in edit mode goes below
		if (isInEditMode()) {
			emojiPopup = null;
			imm = null;
			return;
		}
		Object o = getContext().getSystemService(INPUT_METHOD_SERVICE);
		imm = (InputMethodManager) requireNonNull(o);

		BriarApplication app =
				(BriarApplication) context.getApplicationContext();
		app.getApplicationComponent().inject(this);
		emojiPopup = EmojiPopup.Builder
				.fromRootView(getRootView())
				.setRecentEmoji(recentEmoji)
				.setOnEmojiPopupShownListener(this::showKeyboardIcon)
				.setOnEmojiPopupDismissListener(this::showEmojiIcon)
				.setKeyboardAnimationStyle(R.style.emoji_fade_animation_style)
				.setOnSoftKeyboardOpenListener(this::onKeyboardOpened)
				.setOnSoftKeyboardCloseListener(this::onKeyboardClosed)
				.setIconColor(resolveColorAttribute(getContext(),
						R.attr.colorControlNormal))
				.build(editText);
		emojiToggle.setOnClickListener(v -> emojiPopup.toggle());
		editText.setOnClickListener(v -> {
			if (emojiPopup.isShowing()) emojiPopup.dismiss();
		});
	}

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count,
			int after) {
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before,
			int count) {
		if (emptyTextAllowed || listener == null) return;
		// Work out whether the trimmed text has become empty or non-empty
		if (isEmpty) {
			// We only need to check the characters that were added
			if (countLeadingWhitespace(s, start, count) < count) {
				isEmpty = false;
				listener.onTextIsEmptyChanged(false);
			}
		} else if (before > 0) {
			// Characters have been removed or replaced - check from the start
			int length = s.length();
			if (countLeadingWhitespace(s, 0, length) == length) {
				isEmpty = true;
				listener.onTextIsEmptyChanged(true);
			}
		}
	}

	private int countLeadingWhitespace(CharSequence s, int off, int len) {
		for (int i = 0; i < len; i++) {
			if (!isWhitespace(s.charAt(off + i))) return i;
		}
		return len;
	}

	@Override
	public void afterTextChanged(Editable s) {
	}

	@Override
	public void setEnabled(boolean enabled) {
		super.setEnabled(enabled);
		editText.setEnabled(enabled);
		emojiToggle.setEnabled(enabled);
	}

	@Override
	public void setGravity(int gravity) {
		editText.setGravity(gravity);
	}

	@Override
	public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
		return editText.requestFocus(direction, previouslyFocusedRect);
	}

	@Override
	public void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		if (emojiPopup.isShowing()) emojiPopup.dismiss();
	}

	void setTextInputListener(@Nullable TextInputListener listener) {
		this.listener = listener;
	}

	void setAllowEmptyText(boolean emptyTextAllowed) {
		this.emptyTextAllowed = emptyTextAllowed;
	}

	void setMaxLength(int maxLength) {
		this.maxLength = maxLength;
	}

	void setMaxLines(int maxLines) {
		editText.setMaxLines(maxLines);
	}

	/**
	 * Returns the current text or {@code null},
	 * if it is empty or only consists of white-spaces.
	 */
	@Nullable
	String getText() {
		Editable editable = editText.getText();
		String str = editable == null ? null : editable.toString().trim();
		if (str == null || str.length() == 0) return null;
		return str;
	}

	void clearText() {
		editText.setText(null);
	}

	boolean isEmpty() {
		return getText() == null;
	}

	boolean isTooLong() {
		return editText.getText() != null &&
				utf8IsTooLong(editText.getText().toString().trim(), maxLength);
	}

	CharSequence getHint() {
		return editText.getHint();
	}

	void setHint(@StringRes int res) {
		setHint(getContext().getString(res));
	}

	void setHint(CharSequence hint) {
		editText.setHint(hint);
	}

	boolean isKeyboardOpen() {
		return keyboardOpen || imm.isFullscreenMode();
	}

	private void showEmojiIcon() {
		emojiToggle.setImageResource(R.drawable.ic_emoji_toggle);
	}

	private void showKeyboardIcon() {
		emojiToggle.setImageResource(R.drawable.ic_keyboard);
	}

	void showSoftKeyboard() {
		if (editText.requestFocus()) imm.showSoftInput(editText, SHOW_IMPLICIT);
	}

	void hideSoftKeyboard() {
		if (emojiPopup.isShowing()) emojiPopup.dismiss();
		IBinder token = editText.getWindowToken();
		imm.hideSoftInputFromWindow(token, 0);
	}

	private void onKeyboardOpened(
			@SuppressWarnings("unused") int keyboardHeight) {
		keyboardOpen = true;
		if (keyboardShownListener != null)
			keyboardShownListener.onKeyboardShown();
	}

	private void onKeyboardClosed() {
		if (imm.isFullscreenMode()) {
			onKeyboardOpened(0);
			return;
		}
		keyboardOpen = false;
	}

	void setOnKeyboardShownListener(
			@Nullable OnKeyboardShownListener listener) {
		keyboardShownListener = listener;
	}

	interface TextInputListener {
		void onTextIsEmptyChanged(boolean isEmpty);

		void onSendEvent();
	}

	public interface OnKeyboardShownListener {
		void onKeyboardShown();
	}

}
