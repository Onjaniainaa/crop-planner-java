package fr.cril.cropplanner.solver.sat;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import org.sat4j.core.VecInt;
import org.sat4j.maxsat.WeightedMaxSatDecorator;
import org.sat4j.maxsat.SolverFactory;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.TimeoutException;

import java.util.*;

/**
 * Modèle MaxSAT complet pour le projet CropPlanner.
 * Intègre les contraintes dures (Cycles, Rotations, Saisonnalité, Eau)
 * et les contraintes souples (Demande alimentaire, Associations).
 */
public class SAT4JModel {

    private final int N;     // Nombre de parcelles
    private final int H;     // Horizon de planification (mois)
    private final int M;     // Nombre de cultures
    private final AgronomicDatabase db;
    private final GardenTopology topo;

    private WeightedMaxSatDecorator solver;
    private final int nbVars;
    private int nextAuxVar;

    public SAT4JModel(AgronomicDatabase db, GardenTopology topo, int nbPeriodes) {
        this.db = db;
        this.topo = topo;
        this.N = topo.getTotalCarres();
        this.H = nbPeriodes;
        this.M = db.getNbCultures();
        this.nbVars = N * H * (M + 1);
        this.nextAuxVar = nbVars + 1;
    }

    private int var(int i, int t, int c) {
        return i * H * (M + 1) + t * (M + 1) + c + 1;
    }

    public SolveResult solve(int timeoutSec) {
        try {
            solver = new WeightedMaxSatDecorator(SolverFactory.newDefault());
            // Réservation d'espace pour les variables primaires et auxiliaires
            solver.newVar(nbVars + (N * H * M) + 1000);
            solver.setTimeout(timeoutSec);

            long start = System.currentTimeMillis();
            encode();

            boolean sat = solver.isSatisfiable();
            long elapsed = System.currentTimeMillis() - start;

            if (!sat) return new SolveResult(null, elapsed, 0, "UNSAT");

            int[] model = solver.model();
            int[][] plan = new int[N][H];
            for (int i = 0; i < N; i++) {
                for (int t = 0; t < H; t++) {
                    for (int c = 0; c <= M; c++) {
                        int v = var(i, t, c);
                        if (v <= model.length && model[v - 1] > 0) {
                            plan[i][t] = c;
                            break;
                        }
                    }
                }
            }

            return new SolveResult(plan, elapsed, computeScore(plan), "SAT");

        } catch (ContradictionException e) {
            return new SolveResult(null, 0, 0, "UNSAT (Contradiction)");
        } catch (TimeoutException e) {
            return new SolveResult(null, timeoutSec * 1000L, 0, "TIMEOUT");
        }
    }

    private void encode() throws ContradictionException {
        // --- 1. Occupation des parcelles (ALO + AMO) ---
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) {
                for(int t=0; t<H; t++) addHardUnit(var(i, t, 0));
                continue;
            }
            for (int t = 0; t < H; t++) encodeExactlyOne(i, t);
        }

        // --- 2. C03 : Saisonnalité ---
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t = 0; t < H; t++) {
                int[] available = db.getCulturesDisponibles(t);
                Set<Integer> avSet = new HashSet<>();
                for (int a : available) avSet.add(a);
                for (int c = 1; c <= M; c++) {
                    if (!avSet.contains(c)) addHardUnit(-var(i, t, c));
                }
            }
        }

        // --- 3. C05 : Durée des cycles (Maintien au sol) ---
        for (int i = 0; i < N; i++) {
            for (int t = 0; t < H; t++) {
                for (Culture cult : db.getAllCultures()) {
                    if (cult.isRepos()) continue;
                    int dMois = (int) Math.ceil(cult.cycleMoyenJours() / 30.0);
                    for (int d = 1; d < dMois && (t + d) < H; d++) {
                        addHardBinary(-var(i, t, cult.id()), var(i, t + d, cult.id()));
                    }
                }
            }
        }

        // --- 4. C01 : Rotation (Familles Botaniques) ---
        Map<String, List<Integer>> familyMap = db.getCulturesByFamille();
        for (var entry : familyMap.entrySet()) {
            List<Integer> cids = entry.getValue();
            FamilleBotanique fam = db.getAllFamilles().stream()
                    .filter(f -> f.id().equals(entry.getKey())).findFirst().orElse(null);
            if (fam == null) continue;

            int retour = fam.retourMinPeriodes();
            for (int i = 0; i < N; i++) {
                for (int t = 0; t < H; t++) {
                    for (int k = 1; k < Math.min(retour, H - t); k++) {
                        for (int c1 : cids) {
                            for (int c2 : cids) {
                                int dureeC1 = (int) Math.ceil(db.getCultureById(c1).cycleMoyenJours() / 30.0);
                                if (c1 == c2 && k < dureeC1) continue;
                                addHardBinary(-var(i, t, c1), -var(i, t + k, c2));
                            }
                        }
                    }
                }
            }
        }

        // --- 5. C02 : Incompatibilité d'adjacence ---
        List<int[]> forbidden = db.getForbiddenPairs();
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                for (int[] pair : forbidden) {
                    addHardBinary(-var(edge[0], t, pair[0]), -var(edge[1], t, pair[1]));
                    addHardBinary(-var(edge[0], t, pair[1]), -var(edge[1], t, pair[0]));
                }
            }
        }

        // --- 6. C04 : Demande Alimentaire (Souple - Poids 500) ---
        for (Culture c : db.getAllCultures()) {
            for (int t = 0; t < H; t++) {
                if (db.getDemande(c.id(), t) > 0) {
                    int[] lits = new int[N];
                    for (int i = 0; i < N; i++) lits[i] = var(i, t, c.id());
                    solver.addSoftClause(500, new VecInt(lits));
                }
            }
        }

        // --- 7. C07 : Associations Favorables (Souple - Poids 1) ---
        List<int[]> favPairs = db.getFavorablePairs();
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                for (int[] fav : favPairs) {
                    addSoft(var(edge[0], t, fav[0]), 1);
                    addSoft(var(edge[1], t, fav[1]), 1);
                }
            }
        }

        // --- 8. Gestion de l'Eau (Budget hydraulique) ---
        encodeWaterConstraint();
    }

    private void encodeWaterConstraint() throws ContradictionException {
        int capacityMax = 500; // Capacité max en Litres/jour
        int[] waterNeeds = db.getEauParCultureArray();

        for (int t = 0; t < H; t++) {
            VecInt literals = new VecInt();
            VecInt coefficients = new VecInt();

            for (int i = 0; i < N; i++) {
                for (int c = 1; c <= M; c++) {
                    if (waterNeeds[c] > 0) {
                        literals.push(var(i, t, c));
                        coefficients.push(waterNeeds[c]);
                    }
                }
            }

            if (literals.size() > 0) {
                // Utilisation de la signature standard de SAT4J pour les poids entiers
                // false signifie "inférieur ou égal" (<=)
                solver.addWeightConstraint(literals, coefficients, false, capacityMax);
            }
        }
    }

    private void encodeExactlyOne(int i, int t) throws ContradictionException {
        int[] lits = new int[M + 1];
        for (int c = 0; c <= M; c++) lits[c] = var(i, t, c);
        solver.addHardClause(new VecInt(lits));
        int n = lits.length;
        int[] aux = new int[n - 1];
        for (int k = 0; k < n - 1; k++) aux[k] = nextAuxVar++;
        addHardBinary(-lits[0], aux[0]);
        for (int k = 1; k < n - 1; k++) {
            addHardBinary(-lits[k], aux[k]);
            addHardBinary(-aux[k - 1], aux[k]);
            addHardBinary(-lits[k], -aux[k - 1]);
        }
        addHardBinary(-lits[n - 1], -aux[n - 2]);
    }

    private void addHardUnit(int lit) throws ContradictionException {
        solver.addHardClause(new VecInt(new int[]{lit}));
    }

    private void addHardBinary(int lit1, int lit2) throws ContradictionException {
        solver.addHardClause(new VecInt(new int[]{lit1, lit2}));
    }

    private void addSoft(int lit, int weight) throws ContradictionException {
        solver.addSoftClause(weight, new VecInt(new int[]{lit}));
    }

    private int computeScore(int[][] plan) {
        int score = 0;
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                Culture c1 = db.getCultureById(plan[edge[0]][t]);
                Culture c2 = db.getCultureById(plan[edge[1]][t]);
                if (db.getCompatibilite(c1, c2) == TypeAssociation.FAVORABLE) score++;
            }
        }
        return score;
    }

    public record SolveResult(int[][] plan, long timeMs, int objectifValue, String status) {}
}