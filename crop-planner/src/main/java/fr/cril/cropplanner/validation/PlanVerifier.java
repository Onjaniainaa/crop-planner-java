package fr.cril.cropplanner.validation;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;

import java.util.*;

/**

 * Vérifie 4 règles :
 *   R1 — Rotation des familles botaniques
 *   R2 — Incompatibilité d'adjacence
 *   R3 — Couverture de la demande alimentaire
 *   R4 — Comptage des associations favorables
 */
public class PlanVerifier {

    private final AgronomicDatabase db;
    private final GardenTopology topo;
    private final int H;

    public PlanVerifier(AgronomicDatabase db, GardenTopology topo, int nbPeriodes) {
        this.db = db;
        this.topo = topo;
        this.H = nbPeriodes;
    }

    public Report verify(int[][] plan) {
        Report report = new Report();

        checkRotation(plan, report);
        checkAdjacence(plan, report);
        checkDemande(plan, report);
        countAssociations(plan, report);

        return report;
    }

    /** R1 — Rotation. */
    private void checkRotation(int[][] plan, Report report) {
        int total = 0, violations = 0;

        for (int i = 0; i < plan.length; i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t = 0; t < H; t++) {
                int cId = plan[i][t];
                if (cId == 0) continue;
                Culture c = db.getCultureById(cId);
                if (c == null || c.famille() == null) continue;

                int retour = c.famille().retourMinPeriodes();
                total++;

                for (int t2 = t + 1; t2 < Math.min(t + retour, H); t2++) {
                    int cId2 = plan[i][t2];
                    if (cId2 == 0) continue;
                    Culture c2 = db.getCultureById(cId2);
                    if (c2 == null || c2.famille() == null) continue;

                    if (c.famille().id().equals(c2.famille().id())) {
                        violations++;
                        report.addError("R1-ROTATION",
                            String.format("%s: %s (T%d) → %s (T%d) — même famille %s, retour=%d",
                                topo.nomCarre(i), c.nom(), t, c2.nom(), t2,
                                c.famille().nom(), retour));
                    }
                }
            }
        }
        report.setScore("rotation", total > 0 ?
            (double)(total - violations) / total * 100 : 100);
    }

    /** R2 — Adjacence. */
    private void checkAdjacence(int[][] plan, Report report) {
        int total = 0, violations = 0;

        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                int c1 = plan[edge[0]][t], c2 = plan[edge[1]][t];
                if (c1 == 0 || c2 == 0) continue;

                Culture cu1 = db.getCultureById(c1);
                Culture cu2 = db.getCultureById(c2);
                if (cu1 == null || cu2 == null) continue;
                total++;

                TypeAssociation assoc = db.getCompatibilite(cu1, cu2);
                if (assoc == TypeAssociation.DEFAVORABLE) {
                    violations++;
                    report.addError("R2-ADJACENCE",
                        String.format("T%d: %s (%s) ↔ %s (%s) — défavorable",
                            t, cu1.nom(), topo.nomCarre(edge[0]),
                            cu2.nom(), topo.nomCarre(edge[1])));
                }
            }
        }
        report.setScore("adjacence", total > 0 ?
            (double)(total - violations) / total * 100 : 100);
    }

    /** R3 — Demande. */
    private void checkDemande(int[][] plan, Report report) {
        int total = 0, satisfied = 0;

        for (Culture c : db.getAllCultures()) {
            for (int t = 0; t < H; t++) {
                int d = db.getDemande(c.id(), t);
                if (d <= 0) continue;
                total++;

                int count = 0;
                for (int i = 0; i < plan.length; i++) {
                    if (plan[i][t] == c.id()) count++;
                }

                if (count >= d) {
                    satisfied++;
                } else {
                    report.addWarning("R3-DEMANDE",
                        String.format("T%d: %s — %d carrés (besoin %d)",
                            t, c.nom(), count, d));
                }
            }
        }
        report.setScore("demande", total > 0 ?
            (double) satisfied / total * 100 : 100);
    }

    /** R4 — Associations favorables  */
    private void countAssociations(int[][] plan, Report report) {
        int favorable = 0, total = 0;

        for (int t = 0; t < H; t++) {
            for (int[] edge : topo.getEdges()) {
                int c1 = plan[edge[0]][t], c2 = plan[edge[1]][t];
                if (c1 == 0 || c2 == 0) continue;
                Culture cu1 = db.getCultureById(c1);
                Culture cu2 = db.getCultureById(c2);
                if (cu1 == null || cu2 == null) continue;
                total++;
                if (db.getCompatibilite(cu1, cu2) == TypeAssociation.FAVORABLE) {
                    favorable++;
                }
            }
        }
        report.setScore("associations_favorables", favorable);
        report.setScore("associations_total", total);
    }

    /** Rapport de vérification. */
    public static class Report {
        private final List<String> errors = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Map<String, Double> scores = new LinkedHashMap<>();

        void addError(String rule, String msg) { errors.add("[" + rule + "] " + msg); }
        void addWarning(String rule, String msg) { warnings.add("[" + rule + "] " + msg); }
        void setScore(String name, double value) { scores.put(name, value); }

        public List<String> getErrors() { return errors; }
        public List<String> getWarnings() { return warnings; }
        public Map<String, Double> getScores() { return scores; }
        public int getNbErrors() { return errors.size(); }
        public int getNbWarnings() { return warnings.size(); }

        public boolean isValid() { return errors.isEmpty(); }

        public void print() {
            System.out.println("\n══════════════════════════════════════");
            System.out.println("  RAPPORT DE VÉRIFICATION");
            System.out.println("══════════════════════════════════════");
            System.out.println("  Scores:");
            scores.forEach((k, v) -> System.out.printf("    %-25s %.1f%n", k, v));
            System.out.println("  Erreurs: " + errors.size());
            for (String e : errors) System.out.println("    ✗ " + e);
            System.out.println("  Avertissements: " + warnings.size());
            for (String w : warnings) System.out.println("    ⚠ " + w);
            System.out.println("  Résultat: " + (isValid() ? "VALIDE ✓" : "INVALIDE ✗"));
            System.out.println("══════════════════════════════════════\n");
        }
    }
}
