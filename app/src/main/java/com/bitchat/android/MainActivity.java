package com.bitchat.android;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bitchat.android.databinding.ActivityMainBinding;
import com.bitchat.android.ui.ChatViewModel;
import com.bitchat.android.ui.account.SetupActivity;
import com.bitchat.android.ui.adapter.MessageAdapter;

import java.util.ArrayList;

/**
 * L'activité principale de l'application, affichant l'interface de chat.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ChatViewModel chatViewModel;
    private MessageAdapter messageAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Vérifier si un compte a été configuré en regardant si une identité existe.
        // C'est la manière la plus fiable de savoir si l'onboarding a été fait.
        com.bitchat.android.identity.SecureIdentityStateManager identityManager = new com.bitchat.android.identity.SecureIdentityStateManager(getApplicationContext());
        if (identityManager.loadStaticKey() == null) {
            // Aucune clé n'existe, lancer l'activité de configuration.
            startActivity(new Intent(this, SetupActivity.class));
            finish(); // Termine MainActivity pour que l'utilisateur ne puisse pas y revenir.
            return;
        }

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialiser le ViewModel
        chatViewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        // Configurer la RecyclerView
        setupRecyclerView();

        // Observer les changements de messages
        chatViewModel.messages.observe(this, messages -> {
            messageAdapter.submitList(new ArrayList<>(messages));
            binding.recyclerViewMessages.scrollToPosition(messageAdapter.getItemCount() - 1);
        });

        // Configurer le bouton d'envoi
        binding.buttonSend.setOnClickListener(v -> {
            String messageText = binding.editTextMessage.getText().toString().trim();
            if (!messageText.isEmpty()) {
                chatViewModel.sendMessage(messageText);
                binding.editTextMessage.setText("");
            }
        });
    }

    private void setupRecyclerView() {
        messageAdapter = new MessageAdapter();
        binding.recyclerViewMessages.setAdapter(messageAdapter);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true); // Fait en sorte que la liste commence par le bas
        binding.recyclerViewMessages.setLayoutManager(layoutManager);
    }
}
