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
        String nickname = binding.editTextNickname.getText().toString().trim();
        String password = binding.editTextPassword.getText().toString();

        // Validation simple des champs
        if (nickname.isEmpty()) {
            binding.textInputLayoutNickname.setError("Le pseudonyme ne peut pas être vide.");
            return;
        } else {
            binding.textInputLayoutNickname.setError(null); // Enlève l'erreur précédente
        }

        if (password.isEmpty()) {
            binding.textInputLayoutPassword.setError("Le mot de passe est obligatoire.");
            return;
        } else {
            binding.textInputLayoutPassword.setError(null); // Enlève l'erreur précédente
        }

        // TODO: Implémenter la logique de création de compte avec le ViewModel.
        // - Générer une identité (clés de chiffrement).
        // - Chiffrer et sauvegarder les informations du compte de manière sécurisée.
        // - Naviguer vers l'activité principale une fois le compte créé.

        Toast.makeText(this, "Logique de création de compte à implémenter.", Toast.LENGTH_SHORT).show();
    }
}
