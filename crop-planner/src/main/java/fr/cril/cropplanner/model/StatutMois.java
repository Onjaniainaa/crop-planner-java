package fr.cril.cropplanner.model;

/** Statut d'une culture pour un mois donné. */
public enum StatutMois {
    SEMIS, CROISSANCE, RECOLTE, DIFFICILE, IMPOSSIBLE;

    public boolean isPossible() {
        // Seuls les mois de SEMIS, CROISSANCE, ou RECOLTE sont considérés comme valides.
        // DIFFICILE et IMPOSSIBLE renvoient false et bloquent le solveur.
        return this != IMPOSSIBLE && this != DIFFICILE;
    }
}