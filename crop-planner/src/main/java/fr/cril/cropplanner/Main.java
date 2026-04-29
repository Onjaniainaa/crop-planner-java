package fr.cril.cropplanner;

import fr.cril.cropplanner.ingestion.ExcelReader;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.solver.cp.ChocoModel;
import fr.cril.cropplanner.solver.sat.SAT4JModel;
import fr.cril.cropplanner.validation.PlanVerifier;
import fr.cril.cropplanner.export.HTMLExporter;

/**
 * Point d'entrée du pipeline de planification de cultures.
 *
 * Usage : java -jar crop-planner.jar [options]
 *   --base   chemin vers base_agronomique_thies.xlsx
 *   --conso  chemin vers analyse_consommation_P2_pipeline.xlsx
 *   --solver cp|sat|both    (défaut: cp)
 *   --time   timeout en secondes (défaut: 300)
 *   --output dossier de sortie (défaut: output/)
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  CropPlanner — Bio Jêmm, Thiès, Sénégal        ║");
        System.out.println("║  Pipeline P1→P6 : données → plan optimisé      ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        // ── Parse arguments ──
        String basePath  = getArg(args, "--base",   "data/base_agronomique_thies.xlsx");
        String consoPath = getArg(args, "--conso",  "data/analyse_consommation_P2_pipeline.xlsx");
        String solverArg = getArg(args, "--solver", "cp");
        int timeout      = Integer.parseInt(getArg(args, "--time", "300"));
        String outputDir = getArg(args, "--output", "output");
        int nbPeriodes   = 12; // mois

        try {
            // ═══════════════════════════════════
            // P1 — INGESTION
            // ═══════════════════════════════════
            System.out.println("[P1] Ingestion des données...");
            AgronomicDatabase db = ExcelReader.loadAgronomicDB(basePath);
            db.printSummary();

            // Charger la demande si disponible
            try {
                int[][] demande = ExcelReader.loadDemande(consoPath, db);
                db.setDemande(demande);
                System.out.println("  Demande alimentaire chargée (P2 pipeline)");
            } catch (Exception e) {
                System.out.println("  ⚠ Fichier consommation non trouvé, demande = 0");
            }

            // ═══════════════════════════════════
            // P2 — TRANSFORMATION
            // ═══════════════════════════════════
            System.out.println("\n[P2] Construction de la topologie...");
            GardenTopology topo = GardenTopology.bioJemm();
            topo.printSummary();

            // ═══════════════════════════════════
            // P3+P4 — MODÉLISATION + RÉSOLUTION
            // ═══════════════════════════════════
            PlanVerifier verifier = new PlanVerifier(db, topo, nbPeriodes);
            java.io.File outDir = new java.io.File(outputDir);
            outDir.mkdirs();

            if (solverArg.contains("cp") || solverArg.equals("both")) {
                runChoco(db, topo, nbPeriodes, timeout, verifier, outputDir);
            }

            if (solverArg.contains("sat") || solverArg.equals("both")) {
                runSAT4J(db, topo, nbPeriodes, timeout, verifier, outputDir);
            }

        } catch (Exception e) {
            System.err.println("Erreur fatale : " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Exécute le solveur Choco (CP).
     */
    private static void runChoco(AgronomicDatabase db, GardenTopology topo,
                                  int nbPeriodes, int timeout,
                                  PlanVerifier verifier, String outputDir)
            throws Exception {
        System.out.println("\n[P3] Construction du modèle CP (Choco)...");
        ChocoModel chocoModel = new ChocoModel(db, topo, nbPeriodes);

        System.out.println("[P4] Résolution CP (timeout=" + timeout + "s)...");

        // Tester les 3 stratégies
        for (String strategy : new String[]{"domwdeg"}) {
            System.out.println("\n  ── Stratégie : " + strategy + " ──");
            ChocoModel model = new ChocoModel(db, topo, nbPeriodes);
            ChocoModel.SolveResult result = model.solve(timeout, strategy);

            System.out.printf("  Status    : %s%n", result.status());
            System.out.printf("  Temps     : %d ms%n", result.timeMs());
            System.out.printf("  Noeuds    : %d%n", result.nodes());
            System.out.printf("  Backtracks: %d%n", result.backtracks());
            System.out.printf("  Objectif  : %d%n", result.objectifValue());

            if (result.plan() != null) {
                // P5 — Validation
                System.out.println("\n[P5] Vérification de la solution CP...");
                PlanVerifier.Report report = verifier.verify(result.plan());
                report.print();

                // P6 — Visualisation
                System.out.println("[P6] Export HTML...");
                HTMLExporter.export(result.plan(), db, topo, nbPeriodes,
                    report, "Choco-" + strategy, result.timeMs(),
                    outputDir + "/plan_cp_" + strategy + ".html");

                // Export console du plan
                printPlan(result.plan(), db, topo, nbPeriodes);
            } else {
                System.out.println("  ⚠ Aucune solution trouvée (UNSAT ou timeout)");
                System.out.println("  → Activer la boucle de relâchement P5→P3");
            }
        }
    }

    /**
     * Exécute le solveur SAT4J (MaxSAT).
     */
    private static void runSAT4J(AgronomicDatabase db, GardenTopology topo,
                                   int nbPeriodes, int timeout,
                                   PlanVerifier verifier, String outputDir)
            throws Exception {
        System.out.println("\n[P3] Construction du modèle MaxSAT (SAT4J)...");
        SAT4JModel satModel = new SAT4JModel(db, topo, nbPeriodes);

        System.out.println("[P4] Résolution MaxSAT (timeout=" + timeout + "s)...");
        SAT4JModel.SolveResult result = satModel.solve(timeout);

        System.out.printf("  Status  : %s%n", result.status());
        System.out.printf("  Temps   : %d ms%n", result.timeMs());
        System.out.printf("  Objectif: %d%n", result.objectifValue());

        if (result.plan() != null) {
            System.out.println("\n[P5] Vérification de la solution SAT...");
            PlanVerifier.Report report = verifier.verify(result.plan());
            report.print();

            System.out.println("[P6] Export HTML...");
            HTMLExporter.export(result.plan(), db, topo, nbPeriodes,
                report, "SAT4J-MaxSAT", result.timeMs(),
                outputDir + "/plan_sat.html");

            printPlan(result.plan(), db, topo, nbPeriodes);
        } else {
            System.out.println("  ⚠ Aucune solution trouvée");
        }
    }

    /**
     * Affiche le plan en console (format compact).
     */
    private static void printPlan(int[][] plan, AgronomicDatabase db,
                                   GardenTopology topo, int nbPeriodes) {
        String[] months = {"Jan","Fév","Mar","Avr","Mai","Jun",
                           "Jul","Aoû","Sep","Oct","Nov","Déc"};
        System.out.println("\n  ── Plan de culture ──");
        System.out.printf("  %-10s", "Carré");
        for (int t = 0; t < nbPeriodes; t++) {
            System.out.printf(" %-10s", months[t % 12]);
        }
        System.out.println();
        System.out.println("  " + "-".repeat(10 + nbPeriodes * 11));

        for (int i = 0; i < plan.length; i++) {
            if (!topo.isDisponible(i)) continue;
            // N'afficher que les résumés par réseau (1 ligne sur 4)
            if (i % 12 == 0) {
                int[] coord = topo.fromIndex(i);
                System.out.printf("%n  -- Réseau %d, Rangée %c --%n",
                    coord[0]+1, (char)('A'+coord[1]));
            }
            System.out.printf("  %-10s", topo.nomCarre(i));
            for (int t = 0; t < nbPeriodes; t++) {
                int cId = plan[i][t];
                String name = cId == 0 ? "." :
                    db.getCultureById(cId).nom().substring(0,
                        Math.min(9, db.getCultureById(cId).nom().length()));
                System.out.printf(" %-10s", name);
            }
            System.out.println();
        }
    }

    private static String getArg(String[] args, String key, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultVal;
    }
}
