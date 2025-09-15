package org.briarproject.briar.android.reporting;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import org.briarproject.briar.R;
import org.briarproject.briar.android.reporting.ReportData.ReportItem;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

@NotNullByDefault
class ReportDataAdapter
		extends Adapter<ReportDataAdapter.ReportDataViewHolder> {

	private final List<ReportItem> items;

	ReportDataAdapter(List<ReportItem> items) {
		this.items = items;
	}

	@Override
	public ReportDataViewHolder onCreateViewHolder(ViewGroup parent,
			int viewType) {
		View v = LayoutInflater.from(parent.getContext())
				.inflate(R.layout.list_item_crash, parent, false);
		return new ReportDataViewHolder(v);
	}

	@Override
	public void onBindViewHolder(ReportDataViewHolder holder, int position) {
		holder.bind(items.get(position));
	}

	@Override
	public int getItemCount() {
		return items.size();
	}

	static class ReportDataViewHolder extends RecyclerView.ViewHolder {
		private final CheckBox cb;
		private final TextView content;

		private ReportDataViewHolder(View v) {
			super(v);
			cb = v.findViewById(R.id.include_in_report);
			content = v.findViewById(R.id.content);
		}

		public void bind(ReportItem item) {
			cb.setChecked(!item.isOptional || item.isIncluded);
			cb.setEnabled(item.isOptional);
			cb.setOnCheckedChangeListener((buttonView, isChecked) ->
					item.isIncluded = isChecked
			);
			cb.setText(item.nameRes);
			content.setText(item.info.toString());
		}
	}

}
