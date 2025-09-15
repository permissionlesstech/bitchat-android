package com.bitchat.android.ui;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Représente une suggestion de commande pour l'auto-complétion.
 * Un simple POJO (Plain Old Java Object) pour contenir les détails de la commande.
 */
public final class CommandSuggestion {

    private final String command;
    private final List<String> aliases;
    private final String syntax;
    private final String description;

    public CommandSuggestion(String command, List<String> aliases, String syntax, String description) {
        this.command = command;
        this.aliases = (aliases != null) ? aliases : Collections.emptyList();
        this.syntax = syntax;
        this.description = description;
    }

    // Getters
    public String getCommand() { return command; }
    public List<String> getAliases() { return aliases; }
    public String getSyntax() { return syntax; }
    public String getDescription() { return description; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommandSuggestion that = (CommandSuggestion) o;
        return Objects.equals(command, that.command) &&
               Objects.equals(aliases, that.aliases) &&
               Objects.equals(syntax, that.syntax) &&
               Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(command, aliases, syntax, description);
    }
}
