package fr.cril.cropplanner.model;


public record FamilleBotanique(
    String id,
    String nom,
    int retourMinPeriodes
) {
    @Override
    public String toString() {
        return nom + " (" + id + ", retour=" + retourMinPeriodes + ")";
    }
}
