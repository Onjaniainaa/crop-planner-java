package fr.cril.cropplanner.model;


public record Culture(
        int id,
        String nom,
        String nomLocal,           // nom wolof
        FamilleBotanique famille,
        TypeLegume type,
        int cycleMinJours,
        int cycleMaxJours,
        double besoinEauMin,       // L/m²/jour
        double besoinEauMax,
        String espacement
) {

    public static final Culture REPOS = new Culture(
            0, "Repos", "—", null, null, 0, 0, 0, 0, "—"
    );

    /**
     * Méthode requise par AgronomicDatabase pour le calcul du cycle de l'eau (C05).
     * Elle utilise la valeur moyenne pour la planification.
     */
    public double besoinEau() {
        return besoinEauMoyen();
    }

    /** Besoin en eau moyen (L/m²/jour). */
    public double besoinEauMoyen() {
        return (besoinEauMin + besoinEauMax) / 2.0;
    }

    /** Durée moyenne du cycle en jours. */
    public int cycleMoyenJours() {
        return (cycleMinJours + cycleMaxJours) / 2;
    }

    /** Durée du cycle en semaines (arrondi sup). */
    public int cycleSemaines() {
        return (int) Math.ceil(cycleMoyenJours() / 7.0);
    }

    public boolean isRepos() { return id == 0; }

    @Override
    public String toString() {
        return nom + " [" + (famille != null ? famille.nom() : "—") + "]";
    }
}