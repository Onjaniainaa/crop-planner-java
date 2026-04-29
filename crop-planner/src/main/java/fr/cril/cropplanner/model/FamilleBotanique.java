package fr.cril.cropplanner.model;

/**
 * Famille botanique — clé pour les contraintes de rotation.
 * Deux cultures de même famille ne doivent pas se succéder
 * avant {@code retourMinPeriodes} périodes.
 */
public record FamilleBotanique(
    String id,               // "F01", "F02", ...
    String nom,              // "Solanacées", "Alliacées", ...
    int retourMinPeriodes    // temps de retour en nombre de périodes
) {
    @Override
    public String toString() {
        return nom + " (" + id + ", retour=" + retourMinPeriodes + ")";
    }
}
