package org.briarproject.briar.android.sharing;

import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.briar.R;
import org.briarproject.briar.android.activity.BriarActivity;
import org.briarproject.briar.android.controller.handler.UiExceptionHandler;
import org.briarproject.briar.android.controller.handler.UiResultExceptionHandler;
import org.briarproject.briar.android.sharing.InvitationController.InvitationListener;
import org.briarproject.briar.android.view.BriarRecyclerView;
import org.briarproject.briar.api.sharing.InvitationItem;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Collection;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;

import static android.widget.Toast.LENGTH_SHORT;
import static org.briarproject.briar.android.sharing.InvitationAdapter.InvitationClickListener;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public abstract class InvitationActivity<I extends InvitationItem>
		extends BriarActivity
		implements InvitationListener, InvitationClickListener<I> {

	protected static final Logger LOG =
			Logger.getLogger(InvitationActivity.class.getName());

	private InvitationAdapter<I, ?> adapter;
	private BriarRecyclerView list;

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		setContentView(R.layout.list);

		adapter = getAdapter(this, this);
		list = findViewById(R.id.list);
		if (list != null) {
			list.setLayoutManager(new LinearLayoutManager(this));
			list.setAdapter(adapter);
		}
	}

	abstract protected InvitationAdapter<I, ?> getAdapter(Context ctx,
			InvitationClickListener<I> listener);

	@Override
	public void onStart() {
		super.onStart();
		loadInvitations(false);
	}

	@Override
	public void onStop() {
		super.onStop();
		adapter.clear();
		list.showProgressBar();
	}

	@Override
	public void onItemClick(I item, boolean accept) {
		respondToInvitation(item, accept);

		// show toast
		int res = getDeclineRes();
		if (accept) res = getAcceptRes();
		Toast.makeText(this, res, LENGTH_SHORT).show();

		// remove item and finish if it was the last
		adapter.incrementRevision();
		adapter.remove(item);
		if (adapter.getItemCount() == 0) {
			supportFinishAfterTransition();
		}
	}

	@Override
	public void loadInvitations(boolean clear) {
		int revision = adapter.getRevision();
		getController().loadInvitations(clear,
				new UiResultExceptionHandler<Collection<I>, DbException>(
						this) {
					@Override
					public void onResultUi(Collection<I> items) {
						displayInvitations(revision, items, clear);
					}

					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
	}

	abstract protected InvitationController<I> getController();

	protected void respondToInvitation(I item, boolean accept) {
		getController().respondToInvitation(item, accept,
				new UiExceptionHandler<DbException>(this) {
					@Override
					public void onExceptionUi(DbException exception) {
						handleException(exception);
					}
				});
	}

	@StringRes
	abstract protected int getAcceptRes();

	@StringRes
	abstract protected int getDeclineRes();

	protected void displayInvitations(int revision, Collection<I> invitations,
			boolean clear) {
		runOnUiThreadUnlessDestroyed(() -> {
			if (invitations.isEmpty()) {
				LOG.info("No more invitations available, finishing");
				supportFinishAfterTransition();
			} else if (revision == adapter.getRevision()) {
				adapter.incrementRevision();
				if (clear) adapter.setItems(invitations);
				else adapter.addAll(invitations);
			} else {
				LOG.info("Concurrent update, reloading");
				loadInvitations(clear);
			}
		});
	}

}
