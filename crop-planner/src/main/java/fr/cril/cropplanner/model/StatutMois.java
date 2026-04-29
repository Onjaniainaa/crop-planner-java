package fr.cril.cropplanner.model;

/** Statut d'une culture pour un mois donné. */
public enum StatutMois {
    SEMIS, CROISSANCE, RECOLTE, DIFFICILE, IMPOSSIBLE;

    public boolean isPossible() {
        return this != IMPOSSIBLE;
    }
}
