package com.bitchat.android.ui.account;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import com.bitchat.android.databinding.ActivitySetupBinding;

/**
 * Activité pour la configuration initiale du compte utilisateur.
 * Permet à l'utilisateur de choisir un pseudonyme et de définir un mot de passe.
 */
public class SetupActivity extends AppCompatActivity {

    private ActivitySetupBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Utilisation de ViewBinding pour gonfler le layout, c'est la méthode moderne et sécurisée
        // pour accéder aux vues XML.
        binding = ActivitySetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Configuration du listener pour le bouton de création de compte
        binding.buttonCreateAccount.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleCreateAccount();
            }
        });
    }

    /**
     * Gère la logique de création de compte lorsque l'utilisateur clique sur le bouton.
     */
    private void handleCreateAccount() {
        binding.buttonCreateAccount.setEnabled(false);
        String nickname = binding.editTextNickname.getText().toString().trim();
        String password = binding.editTextPassword.getText().toString();

        if (nickname.isEmpty()) {
            binding.textInputLayoutNickname.setError("Le pseudonyme ne peut pas être vide.");
            binding.buttonCreateAccount.setEnabled(true);
            return;
        } else {
            binding.textInputLayoutNickname.setError(null);
        }

        if (password.isEmpty()) {
            binding.textInputLayoutPassword.setError("Le mot de passe est obligatoire.");
            binding.buttonCreateAccount.setEnabled(true);
            return;
        } else {
            binding.textInputLayoutPassword.setError(null);
        }

        // Lancer la création du compte en arrière-plan pour ne pas bloquer l'UI
        new Thread(() -> {
            try {
                // 1. Créer et sauvegarder les clés cryptographiques.
                // L'instanciation de EncryptionService déclenche la génération et la sauvegarde des clés.
                new com.bitchat.android.crypto.EncryptionService(getApplicationContext());

                // 2. Sauvegarder le pseudonyme.
                com.bitchat.android.ui.DataManager dataManager = new com.bitchat.android.ui.DataManager(getApplicationContext());
                dataManager.saveNickname(nickname);

                // 3. Le mot de passe serait utilisé ici pour chiffrer la base de données ou le keystore,
                // mais cette logique n'est pas présente dans le code de base de Bitchat.
                // Nous le gardons pour une future implémentation.

                // 4. Une fois terminé, naviguer vers l'activité principale.
                runOnUiThread(() -> {
                    Toast.makeText(SetupActivity.this, "Compte créé avec succès !", Toast.LENGTH_SHORT).show();
                    android.content.Intent intent = new android.content.Intent(SetupActivity.this, com.bitchat.android.MainActivity.class);
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(SetupActivity.this, "Erreur lors de la création du compte.", Toast.LENGTH_LONG).show();
                    binding.buttonCreateAccount.setEnabled(true);
                });
            }
        }).start();
    }
}
