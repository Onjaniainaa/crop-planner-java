package fr.cril.cropplanner.transformation;

import java.util.*;

/**
 * Topologie du potager en carrés.
 * Modélise la grille comme un graphe non orienté G = (V, E)
 * avec voisinage 4-connexe (haut, bas, gauche, droite).
 *
 * Le potager Bio Jêmm a 3 réseaux × 2 rangées × 12 carrés = 72 carrés.
 * Les carrés sont numérotés linéairement :
 *   Réseau 1, Rangée A : 0..11
 *   Réseau 1, Rangée B : 12..23
 *   Réseau 2, Rangée A : 24..35
 *   ...
 */
public class GardenTopology {

    private final int nbReseaux;
    private final int nbRangees;
    private final int nbCarresParRangee;
    private final int totalCarres;
    private final boolean[] disponible;
    private final List<int[]> edges;

    /**
     * Crée une topologie de potager.
     * @param nbReseaux nombre de réseaux (ex: 3)
     * @param nbRangees nombre de rangées par réseau (ex: 2)
     * @param nbCarresParRangee nombre de carrés par rangée (ex: 12)
     */
    public GardenTopology(int nbReseaux, int nbRangees, int nbCarresParRangee) {
        this.nbReseaux = nbReseaux;
        this.nbRangees = nbRangees;
        this.nbCarresParRangee = nbCarresParRangee;
        this.totalCarres = nbReseaux * nbRangees * nbCarresParRangee;
        this.disponible = new boolean[totalCarres];
        Arrays.fill(disponible, true);
        this.edges = buildEdges();
    }

    /** Configuration par défaut Bio Jêmm : 3 réseaux × 2 rangées × 12 carrés. */
    public static GardenTopology bioJemm() {
        return new GardenTopology(3, 2, 12);
    }

    /** Marque un carré comme indisponible (chemin, compost, vivace permanente). */
    public void setIndisponible(int carre) {
        disponible[carre] = false;
    }

    /** Convertit (réseau, rangée, position) en index linéaire. */
    public int toIndex(int reseau, int rangee, int position) {
        return reseau * nbRangees * nbCarresParRangee
             + rangee * nbCarresParRangee
             + position;
    }

    /** Convertit un index linéaire en (réseau, rangée, position). */
    public int[] fromIndex(int idx) {
        int pos = idx % nbCarresParRangee;
        int rest = idx / nbCarresParRangee;
        int rangee = rest % nbRangees;
        int reseau = rest / nbRangees;
        return new int[]{reseau, rangee, pos};
    }

    /** Nom lisible d'un carré. */
    public String nomCarre(int idx) {
        int[] coord = fromIndex(idx);
        char rangeeChar = (char)('A' + coord[1]);
        return "R" + (coord[0]+1) + "-" + rangeeChar + (coord[2]+1);
    }

    /**
     * Construit les arêtes d'adjacence.
     * Dans chaque réseau :
     *   - Adjacence horizontale : carré i et i+1 dans la même rangée
     *   - Adjacence verticale : carré (rangée A, pos j) et (rangée B, pos j)
     * Les réseaux ne sont PAS adjacents entre eux (physiquement séparés).
     */
    private List<int[]> buildEdges() {
        List<int[]> e = new ArrayList<>();
        for (int r = 0; r < nbReseaux; r++) {
            for (int rg = 0; rg < nbRangees; rg++) {
                // Adjacence horizontale dans chaque rangée
                for (int p = 0; p < nbCarresParRangee - 1; p++) {
                    int i = toIndex(r, rg, p);
                    int j = toIndex(r, rg, p + 1);
                    if (disponible[i] && disponible[j]) {
                        e.add(new int[]{i, j});
                    }
                }
            }
            // Adjacence verticale entre rangées A et B
            if (nbRangees >= 2) {
                for (int p = 0; p < nbCarresParRangee; p++) {
                    int a = toIndex(r, 0, p);
                    int b = toIndex(r, 1, p);
                    if (disponible[a] && disponible[b]) {
                        e.add(new int[]{a, b});
                    }
                }
            }
        }
        return e;
    }

    /** Retourne les voisins du carré i. */
    public List<Integer> getVoisins(int i) {
        List<Integer> v = new ArrayList<>();
        for (int[] edge : edges) {
            if (edge[0] == i) v.add(edge[1]);
            else if (edge[1] == i) v.add(edge[0]);
        }
        return v;
    }

    // ── Getters ──

    public int getTotalCarres() { return totalCarres; }
    public int getNbCarresDisponibles() {
        int n = 0;
        for (boolean b : disponible) if (b) n++;
        return n;
    }
    public List<int[]> getEdges() { return Collections.unmodifiableList(edges); }
    public boolean isDisponible(int i) { return disponible[i]; }
    public int getNbReseaux() { return nbReseaux; }
    public int getNbRangees() { return nbRangees; }
    public int getNbCarresParRangee() { return nbCarresParRangee; }

    public void printSummary() {
        System.out.println("=== Topologie potager ===");
        System.out.printf("  %d réseaux × %d rangées × %d carrés = %d carrés%n",
            nbReseaux, nbRangees, nbCarresParRangee, totalCarres);
        System.out.println("  Carrés disponibles: " + getNbCarresDisponibles());
        System.out.println("  Arêtes adjacence: " + edges.size());
    }
}
