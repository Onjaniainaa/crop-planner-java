package fr.cril.cropplanner.solver.sat;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import org.sat4j.core.Vec;
import org.sat4j.core.VecInt;
import org.sat4j.maxsat.WeightedMaxSatDecorator;
import org.sat4j.maxsat.SolverFactory;
import org.sat4j.pb.IPBSolver;
import org.sat4j.specs.ContradictionException;
import org.sat4j.specs.IVec;
import org.sat4j.specs.IVecInt;
import org.sat4j.specs.TimeoutException;

import java.math.BigInteger;

import java.util.*;


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

    /** Index unique DIMACS pour la variable x_{i,t,c} */
    private int var(int i, int t, int c) {
        return i * H * (M + 1) + t * (M + 1) + c + 1;
    }

    public SolveResult solve(int timeoutSec) {
        try {
            solver = new WeightedMaxSatDecorator(SolverFactory.newDefault());
            solver.newVar(nbVars + (N * H * M * 4));
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
        // --- 1. ALO + AMO (Une seule culture par parcelle/mois - DUR) ---
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) {
                for (int t = 0; t < H; t++) addHardUnit(var(i, t, 0));
                continue;
            }
            for (int t = 0; t < H; t++) encodeExactlyOne(i, t);
        }

        // --- 2 & 3. C03 & C05 : Saisonnalité STRICTE NON-DÉBORDANTE (DUR) ---
        for (Culture cult : db.getAllCultures()) {
            if (cult.id() <= 0 || cult.isRepos()) continue;

            // Calcul de la durée exacte en mois de la culture (ex: 60 jours -> 2 mois)
            int dureeMois = (int) Math.ceil(cult.cycleMoyenJours() / 30.0);
            if (dureeMois <= 0) dureeMois = 1;

            for (int t = 0; t < H; t++) {
                boolean conflitHivernage = false;

                // On vérifie si un seul des mois du cycle complet de croissance touche à une interdiction
                for (int d = 0; d < dureeMois; d++) {
                    int moisPresence = (t + d) % H;
                    if (!db.isDisponible(cult.id(), moisPresence)) {
                        conflitHivernage = true;
                        break;
                    }
                }

                if (conflitHivernage) {
                    for (int i = 0; i < N; i++) {
                        solver.addHardClause(new VecInt(new int[]{ -var(i, t, cult.id()) }));
                    }
                }
            }
        }

        // Règle A : Interdiction absolue de faire 2 mois de repos (0) consécutifs (DUR)
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t = 0; t < H - 1; t++) {
                addHardBinary(-var(i, t, 0), -var(i, t + 1, 0));
            }
        }

        // Règle B : Logique de Maintien au Sol / Cycle Strict (DUR)
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t = 0; t < H; t++) {
                for (Culture cult : db.getAllCultures()) {
                    if (cult.isRepos()) continue;
                    int c = cult.id();
                    int dMois = (int) Math.ceil(cult.cycleMoyenJours() / 30.0);
                    if (dMois <= 0) dMois = 1;

                    for (int d = 1; d < dMois && (t + d) < H; d++) {
                        if (t == 0) {
                            addHardBinary(-var(i, t, c), var(i, t + d, c));
                        } else {
                            solver.addHardClause(new VecInt(new int[]{var(i, t - 1, c), -var(i, t, c), var(i, t + d, c)}));
                        }
                    }

                    if (t + dMois < H) {
                        if (t == 0) {
                            addHardBinary(-var(i, t, c), -var(i, t + dMois, c));
                        } else {
                            solver.addHardClause(new VecInt(new int[]{var(i, t - 1, c), -var(i, t, c), -var(i, t + dMois, c)}));
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, List<Integer>> entry : db.getCulturesByFamille().entrySet()) {
            List<Integer> cids = entry.getValue();

            FamilleBotanique fam = db.getAllFamilles().stream()
                    .filter(f -> f.id().equals(entry.getKey()))
                    .findFirst().orElse(null);
            if (fam == null) continue;

            int retourMin = fam.retourMinPeriodes();
            if (retourMin <= 0) continue;

            for (int i = 0; i < N; i++) {
                if (!topo.isDisponible(i)) continue;

                for (int t = 0; t < H; t++) {
                    for (int c1 : cids) {
                        // D = durée du cycle de c1 en mois (≥ 1)
                        int D = Math.max(1, (int) Math.ceil(
                                db.getCultureById(c1).cycleMoyenJours() / 30.0));

                        // Fenêtre : [t+D .. t+D+retourMin-1]  (modulo H)
                        for (int k = 0; k < retourMin; k++) {
                            int tExclu = (t + D + k) % H;
                            if (tExclu >= t && tExclu < t + D) continue;

                            for (int c2 : cids) {
                                addHardBinary(-var(i, t, c1), -var(i, tExclu, c2));
                            }
                        }
                    }
                }
            }
        }

        // --- 5. C02 : Incompatibilité d'adjacence (Voisins interdits - DUR) ---
        List<Culture> allCults = db.getAllCultures();
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                for (int ci = 0; ci < allCults.size(); ci++) {
                    for (int cj = ci; cj < allCults.size(); cj++) {
                        Culture ca = allCults.get(ci);
                        Culture cb = allCults.get(cj);
                        if (ca.isRepos() || cb.isRepos()) continue;
                        if (db.getCompatibilite(ca, cb) == TypeAssociation.DEFAVORABLE) {
                            if (ca.id() == cb.id()) {
                                addHardBinary(-var(edge[0], t, ca.id()), -var(edge[1], t, ca.id()));
                            } else {
                                addHardBinary(-var(edge[0], t, ca.id()), -var(edge[1], t, cb.id()));
                                addHardBinary(-var(edge[0], t, cb.id()), -var(edge[1], t, ca.id()));
                            }
                        }
                    }
                }
            }
        }


        // --- C05 : Budget eau cumulé — addAtMost() PB natif SAT4J (DUR) ---
        if (!(solver instanceof IPBSolver)) {
            throw new IllegalStateException("[C05] IPBSolver requis");
        }
        IPBSolver pbSolverC05 = (IPBSolver) solver;
        int[] eauArray = db.getEauParCultureArray();
        final int CAPACITE_EAU = 500;

        for (int t = 0; t < H; t++) {
            IVecInt litsEau = new VecInt();
            IVec<BigInteger> coeffsEau = new Vec<>();

            for (int i = 0; i < N; i++) {
                if (!topo.isDisponible(i)) continue;
                for (int c = 1; c <= M; c++) {
                    int eau = (c < eauArray.length) ? eauArray[c] : 0;
                    if (eau <= 0) continue;
                    litsEau.push(var(i, t, c));
                    coeffsEau.push(BigInteger.valueOf(eau));
                }
            }
            if (litsEau.size() > 0) {
                pbSolverC05.addAtMost(litsEau, coeffsEau, BigInteger.valueOf(CAPACITE_EAU));
            }
        }

        // --- Règle A-bis : Anti-repos consécutif (Lissage occupation sol) ---
        // Pénalise 2 mois de Repos consécutifs sur la même parcelle — poids 5
        for (int t = 0; t < H; t++) {
            int prochainMois = (t + 1) % H;
            for (int i = 0; i < N; i++) {
                if (!topo.isDisponible(i)) continue;
                solver.addSoftClause(5, new VecInt(new int[]{
                        -var(i, t, 0), -var(i, prochainMois, 0)
                }));
            }
        }

        // --- C08 : Continuité / Étalement des semis (SOUPLE) ---
        final int SEUIL_FORTE_DEMANDE = 20; // parcelles/an

        for (Culture cult : db.getAllCultures()) {
            if (cult.isRepos()) continue;

            // Calculer la demande annuelle totale
            int demandeAnnuelle = 0;
            for (int t = 0; t < H; t++)
                demandeAnnuelle += db.getDemande(cult.id(), t);

            // Appliquer C08 uniquement aux cultures à forte demande
            if (demandeAnnuelle < SEUIL_FORTE_DEMANDE) continue;

            for (int t = 0; t < H; t++) {
                if (!db.isDisponible(cult.id(), t)) continue;

                // Clause OR : culture c présente sur au moins 1 parcelle au mois t
                int[] litsPresence = new int[N];
                for (int i = 0; i < N; i++)
                    litsPresence[i] = var(i, t, cult.id());
                solver.addSoftClause(8, new VecInt(litsPresence));
            }
        }

        // --- 6. C04 : Demande Alimentaire Réelle  ---
        double facteurTerrain = (N <= 24) ? 0.33 : 1.0;

        for (Culture c : db.getAllCultures()) {
            if (c.isRepos()) continue;

            for (int t = 0; t < H; t++) {
                int demandeExcel = db.getDemande(c.id(), t);
                int quotaDemande = (int) Math.ceil(demandeExcel * facteurTerrain);
                if (demandeExcel > 0 && quotaDemande == 0) quotaDemande = 1;

                if (quotaDemande <= 0 || !db.isDisponible(c.id(), t)) continue;

                int tailleGroupe = Math.max(1, N / quotaDemande);
                for (int grp = 0; grp < quotaDemande; grp++) {
                    int debut = grp * tailleGroupe;
                    int fin = (grp == quotaDemande - 1) ? N : Math.min(N, debut + tailleGroupe);
                    int nb = fin - debut;
                    if (nb <= 0) continue;


                    int[] lits = new int[nb];
                    for (int j = 0; j < nb; j++) {
                        lits[j] = var(debut + j, t, c.id());
                    }
                    solver.addSoftClause(300, new VecInt(lits));
                }
            }
        }



        // --- C09 : Diversité des cultures (SOUPLE) ---
        for (Culture cult : db.getAllCultures()) {
            if (cult.isRepos()) continue;
            for (int t = 0; t < H; t++) {
                for (int[] edge : topo.getEdges()) {
                    solver.addSoftClause(5, new VecInt(new int[]{
                            -var(edge[0], t, cult.id()),
                            -var(edge[1], t, cult.id())
                    }));
                }
            }
        }

        // --- C10 : Précédents favorables / Bonnes successions (SOUPLE) ---
        String[][] bonnesSuivantes = {
                // {culture_precedente, culture_suivante}
                {"Tomate",          "Haricot"},
                {"Tomate",          "Oignon"},
                {"Tomate",          "Carotte"},
                {"Tomate",          "Salade / Laitue"},
                {"Tomate",          "Chou"},
                {"Oignon",          "Tomate"},
                {"Oignon",          "Chou"},
                {"Oignon",          "Carotte"},
                {"Chou",            "Haricot"},
                {"Chou",            "Carotte"},
                {"Chou",            "Oignon"},
                {"Aubergine",       "Haricot"},
                {"Aubergine",       "Oignon"},
                {"Carotte",         "Tomate"},
                {"Carotte",         "Haricot"},
                {"Carotte",         "Oignon"},
                {"Concombre",       "Haricot"},
                {"Concombre",       "Oignon"},
                {"Haricot",         "Tomate"},
                {"Haricot",         "Chou"},
                {"Haricot",         "Aubergine"},
                {"Betterave",       "Oignon"},
                {"Betterave",       "Haricot"},
                {"Patate douce",    "Haricot"},
                {"Patate douce",    "Oignon"},
                {"Manioc",          "Haricot"},
                {"Manioc",          "Gombo"},
                {"Pomme de terre",  "Haricot"},
                {"Pomme de terre",  "Carotte"},
        };

        for (String[] paire : bonnesSuivantes) {
            Culture cPrev = db.getCultureByName(paire[0]);
            Culture cNext = db.getCultureByName(paire[1]);
            if (cPrev == null || cNext == null) continue;
            for (int i = 0; i < N; i++) {
                if (!topo.isDisponible(i)) continue;
                for (int t = 0; t < H - 1; t++) {
                    solver.addSoftClause(20, new VecInt(new int[]{
                            -var(i, t, cPrev.id()),
                            var(i, t + 1, cNext.id())
                    }));
                }
            }
        }

        // --- 7. C07 : Associations Favorables (SOUPLE) ---
        List<int[]> favPairs = db.getFavorablePairs();
        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                for (int[] fav : favPairs) {
                    addSoft(new int[]{-var(edge[0], t, fav[0]), var(edge[1], t, fav[1])}, 15);
                    addSoft(new int[]{-var(edge[0], t, fav[1]), var(edge[1], t, fav[0])}, 15);
                }
            }
        }

        // --- 8. C10 : Anti-Repos / Maximisation de la Production (SOUPLE) ---
        for (int i = 0; i < N; i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t = 0; t < H; t++) {
                int reposLit = var(i, t, 0);
                solver.addSoftClause(60, new VecInt(new int[]{-reposLit}));
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

    private void addSoft(int[] lits, int weight) throws ContradictionException {
        solver.addSoftClause(weight, new VecInt(lits));
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