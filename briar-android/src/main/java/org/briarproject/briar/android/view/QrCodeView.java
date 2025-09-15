package org.briarproject.briar.android.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.briarproject.briar.R;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

public class QrCodeView extends FrameLayout {

    private final ImageView qrCodeImageView;
    private boolean fullscreen = false;
    private FullscreenListener listener;

    public QrCodeView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.qr_code_view, this, true);
        qrCodeImageView = findViewById(R.id.qr_code);
        ImageView fullscreenButton = findViewById(R.id.fullscreen_button);
        fullscreenButton.setOnClickListener(v -> {
                    fullscreen = !fullscreen;
                    if (!fullscreen) {
                        fullscreenButton.setImageResource(
                                R.drawable.ic_fullscreen_black_48dp);
                    } else {
                        fullscreenButton.setImageResource(
                                R.drawable.ic_fullscreen_exit_black_48dp);
                    }
                    if (listener != null)
                        listener.setFullscreen(fullscreen);
                }
        );
    }

    @UiThread
    public void setQrCode(Bitmap qrCode) {
        qrCodeImageView.setImageBitmap(qrCode);
        // Simple fade-in animation
        AlphaAnimation anim = new AlphaAnimation(0.0f, 1.0f);
        anim.setDuration(200);
        qrCodeImageView.startAnimation(anim);
    }

    @UiThread
    public void setFullscreenListener(FullscreenListener listener) {
        this.listener = listener;
    }

    public interface FullscreenListener {
        void setFullscreen(boolean fullscreen);
    }

}
