package com.bitchat.android.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bitchat.android.databinding.ListItemMessageBinding;
import com.bitchat.android.model.BitchatMessage;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Adapter pour la RecyclerView affichant la liste des messages.
 * Utilise ListAdapter avec DiffUtil pour des mises à jour efficaces et performantes de la liste.
 */
public class MessageAdapter extends ListAdapter<BitchatMessage, MessageAdapter.MessageViewHolder> {

    public MessageAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<BitchatMessage> DIFF_CALLBACK = new DiffUtil.ItemCallback<BitchatMessage>() {
        @Override
        public boolean areItemsTheSame(@NonNull BitchatMessage oldItem, @NonNull BitchatMessage newItem) {
            // Les items sont les mêmes s'ils ont le même ID
            return oldItem.getId().equals(newItem.getId());
        }

        @Override
        public boolean areContentsTheSame(@NonNull BitchatMessage oldItem, @NonNull BitchatMessage newItem) {
            // Le contenu est le même si les objets sont égaux (equals a été surchargé)
            return oldItem.equals(newItem);
        }
    };

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Utilise ViewBinding pour gonfler le layout de l'item, méthode propre et sécurisée.
        ListItemMessageBinding binding = ListItemMessageBinding.inflate(
            LayoutInflater.from(parent.getContext()),
            parent,
            false
        );
        return new MessageViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        BitchatMessage message = getItem(position);
        holder.bind(message);
    }

    /**
     * ViewHolder pour un seul item de message.
     * Il contient les références vers les vues du layout list_item_message.xml.
     */
    public static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final ListItemMessageBinding binding;
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        public MessageViewHolder(ListItemMessageBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        /**
         * Lie un objet BitchatMessage aux vues de ce ViewHolder.
         * @param message Le message à afficher.
         */
        public void bind(BitchatMessage message) {
            binding.textViewSender.setText(message.getSender());
            binding.textViewMessageContent.setText(message.getContent());
            binding.textViewTimestamp.setText(timeFormat.format(message.getTimestamp()));
        }
    }
}
