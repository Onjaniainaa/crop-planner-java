package fr.cril.cropplanner;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.ingestion.ExcelReader;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.solver.cp.ChocoModel;
import fr.cril.cropplanner.validation.PlanVerifier;
import fr.cril.cropplanner.export.HTMLExporter;
import fr.cril.cropplanner.model.Culture;

import java.io.File;

public class Main {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║  CropPlanner — Bio Jêmm, Thiès, Sénégal        ║");
        System.out.println("║  Pipeline P1→P6 : données → plan optimisé      ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        // Récupération des arguments
        String basePath  = getArg(args, "--base",   "data/base_agronomique_thies.xlsx");
        String consoPath = getArg(args, "--conso",  "data/analyse_consommation_P2_pipeline.xlsx");
        String solverArg = getArg(args, "--solver", "cp");
        int timeout = Integer.parseInt(getArg(args, "--time", "300")); // 5 minutes
        String outputDir = getArg(args, "--output", "output");
        int nbPeriodes   = 12;

        try {
            // [P1] Ingestion des données
            System.out.println("[P1] Ingestion des données...");
            AgronomicDatabase db = ExcelReader.loadAgronomicDB(basePath);

            try {
                int[][] demande = ExcelReader.loadDemande(consoPath, db);
                db.setDemande(demande);
                System.out.println("  ✅ Demande alimentaire chargée.");
            } catch (Exception e) {
                System.out.println("  ⚠ Fichier consommation non trouvé ou erreur de lecture.");
            }

            // [P1.5] Diagnostic
            effectuerDiagnostic(db);

            // [P2] Topologie
            System.out.println("\n[P2] Construction de la topologie...");
            GardenTopology topo = GardenTopology.bioJemm();
            System.out.println("  ✅ Topologie chargée : " + topo.getParcelles().size() + " parcelles.");

            PlanVerifier verifier = new PlanVerifier(db, topo, nbPeriodes);

            // Préparation du dossier de sortie
            File dir = new File(outputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("  ⚠ Attention : Impossible de créer le dossier " + outputDir);
            }

            // [P3/P4] Résolution
            if (solverArg.contains("cp")) {
                runChoco(db, topo, nbPeriodes, timeout, verifier, outputDir);
            }

        } catch (Exception e) {
            System.err.println("❌ Erreur critique : " + e.getMessage());
        }
    }

    private static void effectuerDiagnostic(AgronomicDatabase db) {
        System.out.println("\n🔍 DIAGNOSTIC DES DONNÉES :");
        boolean alerte = false;
        int conflits = 0;

        for (Culture c : db.getAllCultures()) {
            if (c.isRepos()) continue;
            for (int m = 0; m < 12; m++) {
                if (db.getDemande(c.id(), m) > 0 && !db.isDisponible(c.id(), m)) {
                    System.err.println("  ❌ CONFLIT : '" + c.nom() + "' demandée au mois " + (m + 1) + " mais interdite au calendrier.");
                    alerte = true;
                    conflits++;
                }
            }
        }
        if (!alerte) System.out.println("  ✅ Cohérence Demande/Calendrier : OK.");
        else System.out.println("  ⚠ Total de " + conflits + " conflits détectés.");
    }

    private static void runChoco(AgronomicDatabase db, GardenTopology topo, int nbPeriodes,
                                 int timeout, PlanVerifier verifier, String outputDir) throws Exception {

        boolean solutionTrouvee = false;
        int tentative = 0;
        int maxTentatives = 5;

        while (!solutionTrouvee && tentative < maxTentatives) {
            // Logique de relâchement : on commence à 0.6 (60% de la demande)
            // et on descend de 10% à chaque échec.
            double facteurDemande = 0.6 - (tentative * 0.1);
            if (facteurDemande < 0.1) facteurDemande = 0.1;

            System.out.println("\n[P3] Tentative #" + (tentative + 1));
            System.out.println("     📊 Facteur de demande ciblé : " + String.format("%.0f%%", facteurDemande * 100));

            ChocoModel model = new ChocoModel(db, topo, nbPeriodes, facteurDemande);
            System.out.println("[P4] Résolution CP en cours...");

            long startTime = System.currentTimeMillis();
            ChocoModel.SolveResult result = model.solve(timeout);
            long duration = System.currentTimeMillis() - startTime;

            if (result.plan() != null) {
                System.out.println("✅ SOLUTION TROUVÉE en " + (duration / 1000.0) + "s !");
                PlanVerifier.Report report = verifier.verify(result.plan());

                String fileName = outputDir + "/plan_cp_t" + (tentative + 1) + ".html";

                // --- CORRECTION APPLIQUÉE ICI ---
                HTMLExporter.export(
                        result.plan(),
                        db,
                        topo,
                        nbPeriodes,
                        report,
                        "Choco-CP (Tentative " + (tentative + 1) + ")",
                        (long) result.timeMs(), // Cast explicite en long
                        fileName
                );

                System.out.println("  👉 Plan exporté : " + fileName);
                solutionTrouvee = true;
            } else {
                System.out.println("❌ UNSAT (Aucune solution possible).");
                tentative++;
            }
        }

        if (!solutionTrouvee) {
            System.err.println("\nAbandon : Aucune solution après " + maxTentatives + " essais.");
        }
    }

    private static String getArg(String[] args, String key, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultVal;
    }
}