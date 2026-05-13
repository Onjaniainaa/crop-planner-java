package fr.cril.cropplanner.solver.cp;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainMax;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;

/**
 * Modèle final optimisé pour CropPlanner.
 * Gère la maximisation de l'occupation et la continuité des cycles.
 */
public class ChocoModel {

    private final Model model;
    private final IntVar[][] X; // X[parcelle][mois]
    private final int N, H;
    private final AgronomicDatabase db;

    public ChocoModel(AgronomicDatabase db, GardenTopology topo, int nbPeriodes, double facteurRelachement) {
        this.db = db;
        this.N = topo.getTotalCarres();
        this.H = nbPeriodes;
        this.model = new Model("CropPlanner_BioJemm_Final");

        // 1. Initialisation des variables
        X = new IntVar[N][H];
        int maxId = db.getAllCultures().stream().mapToInt(Culture::id).max().orElse(100);

        for (int i = 0; i < N; i++) {
            for (int t = 0; t < H; t++) {
                X[i][t] = model.intVar("X_" + i + "_T" + t, 0, maxId);
            }
        }

        // 2. Application des contraintes
        postContraintesCalendrier();
        postContraintesDemande(facteurRelachement);
        postContrainteDureeCycle();

        // 3. OBJECTIF : Maximiser l'occupation du sol
        setupMaximizationObjective();
    }

    private void setupMaximizationObjective() {
        IntVar totalCultive = model.intVar("totalCultive", 0, N * H);
        BoolVar[] isOccupied = model.boolVarArray("isOccupied", N * H);
        IntVar[] flatX = flatten(X);

        for (int j = 0; j < flatX.length; j++) {
            // isOccupied[j] = 1 si flatX[j] > 0 (si la case n'est pas au repos)
            model.reifyXneC(flatX[j], 0, isOccupied[j]);
        }
        model.sum(isOccupied, "=", totalCultive).post();
        model.setObjective(Model.MAXIMIZE, totalCultive);
    }

    private void postContraintesCalendrier() {
        for (Culture c : db.getAllCultures()) {
            if (c.id() <= 0 || c.isRepos()) continue;
            for (int t = 0; t < H; t++) {
                if (!db.isDisponible(c.id(), t)) {
                    for (int i = 0; i < N; i++) {
                        model.arithm(X[i][t], "!=", c.id()).post();
                    }
                }
            }
        }
    }

    private void postContraintesDemande(double facteur) {
        for (Culture c : db.getAllCultures()) {
            if (c.id() <= 0 || c.isRepos()) continue;
            for (int t = 0; t < H; t++) {
                int besoin = (int) Math.ceil(db.getDemande(c.id(), t) * facteur);
                if (besoin > 0) {
                    // Si le facteur est élevé (>30%), on force au moins 1 parcelle
                    int min = (facteur > 0.3) ? 1 : 0;
                    model.count(c.id(), getColumn(t), model.intVar(min, besoin)).post();
                }
            }
        }
    }

    private void postContrainteDureeCycle() {
        for (int i = 0; i < N; i++) {
            for (int t = 0; t < H; t++) {
                for (Culture c : db.getAllCultures()) {
                    if (c.id() <= 0 || c.isRepos()) continue;

                    // Sécurité : plafonnement de la durée à 12 mois
                    int dureeMois = (int) Math.ceil(c.cycleMoyenJours() / 30.0);
                    if (dureeMois > 12) dureeMois = 12;

                    if (dureeMois > 1) {
                        for (int d = 1; d < dureeMois; d++) {
                            if (t + d < H) {
                                // Implication : si planté à T, alors planté à T+d
                                model.ifThen(
                                        model.arithm(X[i][t], "=", c.id()),
                                        model.arithm(X[i][t + d], "=", c.id())
                                );
                            }
                        }
                    }
                }
            }
        }
    }

    public SolveResult solve(int timeoutSec) {
        Solver solver = model.getSolver();
        solver.limitTime(timeoutSec + "s");

        // Stratégie de recherche : tester les cultures (ID max) avant le repos (ID 0)
        solver.setSearch(Search.intVarSearch(
                new FirstFail(model),
                new IntDomainMax(),
                flatten(X)
        ));

        boolean found = false;
        int[][] bestPlan = null;
        double time = 0;

        // Recherche itérative de la meilleure solution (Maximisation)
        while (solver.solve()) {
            found = true;
            bestPlan = new int[N][H];
            for (int i = 0; i < N; i++) {
                for (int t = 0; t < H; t++) {
                    bestPlan[i][t] = X[i][t].getValue();
                }
            }
            time = solver.getTimeCount() * 1000;
            System.out.println("  [CP] Solution améliorée trouvée...");
        }

        if (found) {
            return new SolveResult(bestPlan, time, "SAT");
        }
        return new SolveResult(null, 0, "UNSAT");
    }

    private IntVar[] getColumn(int t) {
        IntVar[] col = new IntVar[N];
        for (int i = 0; i < N; i++) col[i] = X[i][t];
        return col;
    }

    private IntVar[] flatten(IntVar[][] matrix) {
        IntVar[] flat = new IntVar[N * H];
        int k = 0;
        for (int i = 0; i < N; i++)
            for (int t = 0; t < H; t++)
                flat[k++] = matrix[i][t];
        return flat;
    }

    public record SolveResult(int[][] plan, double timeMs, String status) {}
}