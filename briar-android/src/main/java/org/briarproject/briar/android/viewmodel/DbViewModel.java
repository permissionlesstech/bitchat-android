package org.briarproject.briar.android.viewmodel;

import android.app.Application;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbCallable;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.DbRunnable;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.briar.android.util.UiUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;

import androidx.annotation.AnyThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.arch.core.util.Function;
import androidx.core.util.Consumer;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.LogUtils.logException;

@Immutable
@NotNullByDefault
public abstract class DbViewModel extends AndroidViewModel {

	private static final Logger LOG = getLogger(DbViewModel.class.getName());

	@DatabaseExecutor
	private final Executor dbExecutor;
	protected final LifecycleManager lifecycleManager;
	private final TransactionManager db;
	protected final AndroidExecutor androidExecutor;

	public DbViewModel(
			Application application,
			@DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor) {
		super(application);
		this.dbExecutor = dbExecutor;
		this.lifecycleManager = lifecycleManager;
		this.db = db;
		this.androidExecutor = androidExecutor;
	}

	/**
	 * Waits for the DB to open and runs the given task on the
	 * {@link DatabaseExecutor}.
	 * <p>
	 * If you need a list of items to be displayed in a
	 * {@link RecyclerView.Adapter},
	 * use {@link #loadFromDb(DbCallable, UiConsumer)} instead.
	 */
	protected void runOnDbThread(Runnable task) {
		dbExecutor.execute(() -> {
			try {
				lifecycleManager.waitForDatabase();
				task.run();
			} catch (InterruptedException e) {
				LOG.warning("Interrupted while waiting for database");
				Thread.currentThread().interrupt();
			}
		});
	}

	/**
	 * Waits for the DB to open and runs the given task on the
	 * {@link DatabaseExecutor}.
	 * <p>
	 * If you need a list of items to be displayed in a
	 * {@link RecyclerView.Adapter},
	 * use {@link #loadFromDb(DbCallable, UiConsumer)} instead.
	 */
	protected void runOnDbThread(boolean readOnly,
			DbRunnable<Exception> task, Consumer<Exception> err) {
		dbExecutor.execute(() -> {
			try {
				lifecycleManager.waitForDatabase();
				db.transaction(readOnly, task);
			} catch (InterruptedException e) {
				LOG.warning("Interrupted while waiting for database");
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				err.accept(e);
			}
		});
	}

	/**
	 * Loads a data on the {@link DatabaseExecutor} within a single
	 * {@link Transaction} and publishes it as a {@link LiveResult}
	 * to the {@link UiThread}.
	 * <p>
	 * Use this to ensure that modifications to your local UI data do not get
	 * overridden by database loads that were in progress while the modification
	 * was made.
	 * E.g. An event about the removal of a message causes the message item to
	 * be removed from the local data set while all messages are reloaded.
	 * This method ensures that those operations can be processed on the
	 * UiThread in the correct order so that the removed message will not be
	 * re-added when the re-load completes.
	 */
	protected <T> void loadFromDb(DbCallable<T, DbException> task,
			UiConsumer<LiveResult<T>> uiConsumer) {
		dbExecutor.execute(() -> {
			try {
				lifecycleManager.waitForDatabase();
				db.transaction(true, txn -> {
					T t = task.call(txn);
					txn.attach(() -> uiConsumer.accept(new LiveResult<>(t)));
				});
			} catch (InterruptedException e) {
				LOG.warning("Interrupted while waiting for database");
				Thread.currentThread().interrupt();
			} catch (DbException e) {
				logException(LOG, WARNING, e);
				androidExecutor.runOnUiThread(
						() -> uiConsumer.accept(new LiveResult<>(e)));
			}
		});
	}

	@NotNullByDefault
	public interface UiConsumer<T> {
		@UiThread
		void accept(T t);
	}

	/**
	 * Creates a copy of the given list and adds the given item to the copy.
	 *
	 * @return an updated copy of the list, or null if the list is null
	 */
	@Nullable
	protected <T> List<T> addListItem(@Nullable List<T> list, T item) {
		if (list == null) return null;
		List<T> copy = new ArrayList<>(list);
		copy.add(item);
		return copy;
	}

	/**
	 * Creates a copy of the given list and adds the given items to the copy.
	 *
	 * @return an updated copy of the list, or null if the list is null
	 */
	@Nullable
	protected <T> List<T> addListItems(@Nullable List<T> list,
			Collection<T> items) {
		if (list == null) return null;
		List<T> copy = new ArrayList<>(list);
		copy.addAll(items);
		return copy;
	}

	/**
	 * Creates a copy of the given list, replacing items where the given test
	 * function returns true.
	 *
	 * @return an updated copy of the list, or null if either the list is null
	 * or the test function returns false for all items
	 */
	@Nullable
	protected <T> List<T> updateListItems(@Nullable List<T> list,
			Function<T, Boolean> test, Function<T, T> replacer) {
		if (list == null) return null;
		List<T> copy = new ArrayList<>(list);

		ListIterator<T> iterator = copy.listIterator();
		boolean changed = false;
		while (iterator.hasNext()) {
			T item = iterator.next();
			if (test.apply(item)) {
				changed = true;
				iterator.set(replacer.apply(item));
			}
		}
		return changed ? copy : null;
	}

	/**
	 * Creates a copy of the given list, removing items from it where the given
	 * test function returns true.
	 *
	 * @return an updated copy of the list, or null if either the list is null
	 * or the test function returns false for all items
	 */
	@Nullable
	protected <T> List<T> removeListItems(@Nullable List<T> list,
			Function<T, Boolean> test) {
		if (list == null) return null;
		List<T> copy = new ArrayList<>(list);

		ListIterator<T> iterator = copy.listIterator();
		boolean changed = false;
		while (iterator.hasNext()) {
			T item = iterator.next();
			if (test.apply(item)) {
				changed = true;
				iterator.remove();
			}
		}
		return changed ? copy : null;
	}

	/**
	 * Updates the given LiveData with a copy of its list
	 * with the items removed where the given test function returns true.
	 * <p>
	 * Nothing is updated, if the
	 * <ul>
	 * <li> LiveData does not have a value
	 * <li> LiveResult in the LiveData has an error
	 * <li> test function returned false for all items in the list
	 * </ul>
	 */
	@UiThread
	protected <T> void removeAndUpdateListItems(
			MutableLiveData<LiveResult<List<T>>> liveData,
			Function<T, Boolean> test) {
		List<T> copy = removeListItems(getList(liveData), test);
		if (copy != null) liveData.setValue(new LiveResult<>(copy));
	}

	/**
	 * Returns the list of items from the given LiveData, or null if no list is
	 * available.
	 */
	@Nullable
	protected <T> List<T> getList(LiveData<LiveResult<List<T>>> liveData) {
		LiveResult<List<T>> value = liveData.getValue();
		if (value == null) return null;
		return value.getResultOrNull();
	}

	/**
	 * Logs the exception and shows a Toast to the user.
	 * <p>
	 * Errors that are likely or expected to happen should not use this method
	 * and show proper error states in UI.
	 */
	@AnyThread
	protected void handleException(Exception e) {
		UiUtils.handleException(getApplication(), androidExecutor, LOG, e);
	}

}
