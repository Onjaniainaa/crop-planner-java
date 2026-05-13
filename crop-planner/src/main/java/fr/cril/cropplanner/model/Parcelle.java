package fr.cril.cropplanner.model;

/**
 * Représente une unité de culture (un carré dans le potager).
 */
public record Parcelle(int id, String nom, double surface) {
    // Le record génère automatiquement les getters : id(), nom() et surface()
}