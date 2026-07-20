package fr.cril.cropplanner.transformation;

import fr.cril.cropplanner.model.Parcelle;
import java.util.*;


public class GardenTopology {

    private final int nbReseaux;
    private final int nbRangees;
    private final int nbCarresParRangee;
    private final int totalCarres;
    private final boolean[] disponible;
    private final List<int[]> edges;

    public GardenTopology(int nbReseaux, int nbRangees, int nbCarresParRangee) {
        this.nbReseaux = nbReseaux;
        this.nbRangees = nbRangees;
        this.nbCarresParRangee = nbCarresParRangee;
        this.totalCarres = nbReseaux * nbRangees * nbCarresParRangee;
        this.disponible = new boolean[totalCarres];
        Arrays.fill(disponible, true);
        this.edges = buildEdges();
    }

    public static GardenTopology bioJemm() {
        return new GardenTopology(3, 2, 12);
    }


    public List<Parcelle> getParcelles() {
        List<Parcelle> liste = new ArrayList<>();
        for (int i = 0; i < totalCarres; i++) {
            if (disponible[i]) {
                // On considère ici que chaque carré fait 1.0 m² par défaut
                liste.add(new Parcelle(i, nomCarre(i), 1.0));
            }
        }
        return liste;
    }

    public void setIndisponible(int carre) {
        disponible[carre] = false;
    }

    public int toIndex(int reseau, int rangee, int position) {
        return reseau * nbRangees * nbCarresParRangee
                + rangee * nbCarresParRangee
                + position;
    }

    public int[] fromIndex(int idx) {
        int pos = idx % nbCarresParRangee;
        int rest = idx / nbCarresParRangee;
        int rangee = rest % nbRangees;
        int reseau = rest / nbRangees;
        return new int[]{reseau, rangee, pos};
    }

    public String nomCarre(int idx) {
        int[] coord = fromIndex(idx);
        char rangeeChar = (char)('A' + coord[1]);
        return "R" + (coord[0]+1) + "-" + rangeeChar + (coord[2]+1);
    }

    private List<int[]> buildEdges() {
        List<int[]> e = new ArrayList<>();
        for (int r = 0; r < nbReseaux; r++) {
            for (int rg = 0; rg < nbRangees; rg++) {
                for (int p = 0; p < nbCarresParRangee - 1; p++) {
                    int i = toIndex(r, rg, p);
                    int j = toIndex(r, rg, p + 1);
                    if (disponible[i] && disponible[j]) {
                        e.add(new int[]{i, j});
                    }
                }
            }
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