package fr.cril.cropplanner.solver.cp;

import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.variables.IntVar;

import java.util.*;

/**
 * Modèle CP avec Choco Solver pour l'allocation de cultures.
 *
 * Variables : X[i][t] ∈ {0..M} = culture au carré i, période t (0 = repos)
 *
 * Contraintes dures :
 *   C01 — Rotation des familles botaniques
 *   C02 — Incompatibilité d'adjacence
 *   C03 — Saisonnalité (intégrée dans les domaines)
 *   C04 — Couverture de la demande alimentaire
 *
 * Objectif : maximiser les associations favorables (C07)
 */
public class ChocoModel {

    private final Model model;
    private final IntVar[][] X;          // X[carré][période]
    private final IntVar objectif;
    private final int N;                  // nombre de carrés
    private final int H;                  // nombre de périodes
    private final int M;                  // nombre de cultures (hors repos)
    private final AgronomicDatabase db;
    private final GardenTopology topo;

    public ChocoModel(AgronomicDatabase db, GardenTopology topo, int nbPeriodes) {
        this.db = db;
        this.topo = topo;
        this.N = topo.getTotalCarres();
        this.H = nbPeriodes;
        this.M = db.getNbCultures();
        this.model = new Model("CropPlanning-Thies-CP");

        // ── Création des variables ──
        X = new IntVar[N][H];
        for (int i = 0; i < N; i++) {
            for (int t = 0; t < H; t++) {
                if (topo.isDisponible(i)) {
                    // C03 : domaine restreint par saisonnalité
                    int[] domain = db.getCulturesDisponibles(t);
                    X[i][t] = model.intVar("X_" + topo.nomCarre(i) + "_T" + t, domain);
                } else {
                    // Carré indisponible = toujours repos
                    X[i][t] = model.intVar("X_" + i + "_T" + t, 0);
                }
            }
        }

        // ── Contraintes ──
        postC01_Rotation();
        postC02_Adjacence();
        postC04_Demande();

        // ── Objectif ──
        this.objectif = postC07_Objectif();
    }

    /**
     * C01 — Rotation des familles botaniques.
     * Deux cultures de la même famille ne doivent pas apparaître
     * sur le même carré dans une fenêtre de retour.
     *
     * Implémentation : pour chaque famille, on crée une table de
     * paires interdites (c1, c2) où famille(c1) == famille(c2),
     * puis on pose la contrainte table sur (X[i][t], X[i][t+k]).
     */
    private void postC01_Rotation() {
        Map<String, List<Integer>> familyMap = db.getCulturesByFamille();

        for (var entry : familyMap.entrySet()) {
            List<Integer> culturesInFamily = entry.getValue();
            FamilleBotanique famille = db.getAllCultures().stream()
                .filter(c -> c.famille() != null && c.famille().id().equals(entry.getKey()))
                .map(Culture::famille)
                .findFirst().orElse(null);

            if (famille == null || culturesInFamily.size() < 2) continue;
            int retour = famille.retourMinPeriodes();

            // Construire les paires interdites pour cette famille
            // (toute paire c1, c2 dans la même famille)
            List<int[]> forbidden = new ArrayList<>();
            for (int c1 : culturesInFamily) {
                for (int c2 : culturesInFamily) {
                    forbidden.add(new int[]{c1, c2});
                }
            }

            // Poster pour chaque carré et fenêtre de retour
            for (int i = 0; i < N; i++) {
                if (!topo.isDisponible(i)) continue;
                for (int t = 0; t < H; t++) {
                    for (int k = 1; k < Math.min(retour, H - t); k++) {
                        int t2 = t + k;
                        // Pour chaque paire interdite, empêcher la combinaison
                        for (int[] pair : forbidden) {
                            model.ifThen(
                                model.arithm(X[i][t], "=", pair[0]),
                                model.arithm(X[i][t2], "!=", pair[1])
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * C02 — Incompatibilité d'adjacence.
     * Deux cultures défavorables ne doivent pas être voisines
     * à la même période.
     *
     * Implémentation : contrainte table avec tuples autorisés
     * sur chaque paire de voisins pour chaque période.
     */
    private void postC02_Adjacence() {
        // Construire la table des paires autorisées
        List<int[]> allowed = db.getAllowedPairs();
        org.chocosolver.solver.constraints.extension.Tuples tuples =
            new org.chocosolver.solver.constraints.extension.Tuples(true);
        for (int[] pair : allowed) {
            tuples.add(pair[0], pair[1]);
        }

        // Poster pour chaque arête et chaque période
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                model.table(X[edge[0]][t], X[edge[1]][t], tuples).post();
            }
        }
    }

    /**
     * C04 — Couverture de la demande alimentaire.
     * Pour chaque culture c et période t, au moins demande(c,t)
     * carrés doivent porter la culture c.
     *
     * Implémentation : contrainte count.
     */
    private void postC04_Demande() {
        for (Culture c : db.getAllCultures()) {
            for (int t = 0; t < H; t++) {
                int d = db.getDemande(c.id(), t);
                if (d > 0) {
                    IntVar[] colonne = getColumn(t);
                    // count(c.id, colonne) >= d
                    IntVar countVar = model.intVar("cnt_" + c.nom() + "_T" + t, d, N);
                    model.count(c.id(), colonne, countVar).post();
                }
            }
        }
    }

    /**
     * C07 — Maximiser les associations favorables entre voisins.
     * Pour chaque arête (i,j) et période t, on ajoute 1 à l'objectif
     * si les cultures sont en association favorable.
     *
     * Implémentation : variables réifiées + somme.
     */
    private IntVar postC07_Objectif() {
        List<IntVar> bonusList = new ArrayList<>();
        List<int[]> favPairs = db.getFavorablePairs();

        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                IntVar xi = X[edge[0]][t];
                IntVar xj = X[edge[1]][t];

                // Pour chaque paire favorable (c1, c2), créer un bonus
                for (int[] fav : favPairs) {
                    IntVar b = model.intVar("b_" + edge[0] + "_" + edge[1]
                        + "_" + t + "_" + fav[0] + "_" + fav[1], 0, 1);
                    // b = 1 ssi (xi == fav[0] && xj == fav[1])
                    //       ou (xi == fav[1] && xj == fav[0])
                    model.ifThenElse(
                        model.or(
                            model.and(
                                model.arithm(xi, "=", fav[0]),
                                model.arithm(xj, "=", fav[1])),
                            model.and(
                                model.arithm(xi, "=", fav[1]),
                                model.arithm(xj, "=", fav[0]))
                        ),
                        model.arithm(b, "=", 1),
                        model.arithm(b, "=", 0)
                    );
                    bonusList.add(b);
                }
            }
        }

        if (bonusList.isEmpty()) {
            return model.intVar("objectif", 0);
        }

        IntVar obj = model.intVar("objectif", 0, bonusList.size());
        model.sum(bonusList.toArray(new IntVar[0]), "=", obj).post();
        return obj;
    }

    /**
     * Résout le modèle et retourne la meilleure solution.
     * @param timeoutSec timeout en secondes
     * @param strategy heuristique de recherche ("domwdeg", "activity", "input")
     */
    public SolveResult solve(int timeoutSec, String strategy) {
        Solver solver = model.getSolver();

        // Stratégie de recherche
        IntVar[] allVars = flatten(X);
        switch (strategy.toLowerCase()) {
            case "activity" -> solver.setSearch(
                Search.activityBasedSearch(allVars));
            case "input" -> solver.setSearch(
                Search.inputOrderLBSearch(allVars));
            default -> solver.setSearch(
                Search.domOverWDegSearch(allVars));
        }

        solver.limitTime(timeoutSec + "s");
        solver.showShortStatistics();

        // Recherche de la solution optimale
        long start = System.currentTimeMillis();
        Solution best = solver.findOptimalSolution(objectif, Model.MAXIMIZE);
        long elapsed = System.currentTimeMillis() - start;

        if (best == null) {
            return new SolveResult(null, elapsed, solver.getNodeCount(),
                solver.getBackTrackCount(), -1, "UNSAT");
        }

        // Extraire le plan
        int[][] plan = new int[N][H];
        for (int i = 0; i < N; i++)
            for (int t = 0; t < H; t++)
                plan[i][t] = best.getIntVal(X[i][t]);

        return new SolveResult(plan, elapsed, solver.getNodeCount(),
            solver.getBackTrackCount(), best.getIntVal(objectif), "OPTIMAL");
    }

    /** Retourne la colonne de variables à la période t. */
    private IntVar[] getColumn(int t) {
        IntVar[] col = new IntVar[N];
        for (int i = 0; i < N; i++) col[i] = X[i][t];
        return col;
    }

    /** Aplatit la matrice X en un tableau 1D. */
    private IntVar[] flatten(IntVar[][] matrix) {
        List<IntVar> list = new ArrayList<>();
        for (IntVar[] row : matrix)
            Collections.addAll(list, row);
        return list.toArray(new IntVar[0]);
    }

    public Model getModel() { return model; }

    /** Résultat de la résolution. */
    public record SolveResult(
        int[][] plan,            // plan[carré][période] = cultureId
        long timeMs,             // temps en ms
        long nodes,              // nœuds explorés
        long backtracks,         // retours arrière
        int objectifValue,       // valeur de l'objectif
        String status            // "OPTIMAL", "SAT", "UNSAT"
    ) {}
}
