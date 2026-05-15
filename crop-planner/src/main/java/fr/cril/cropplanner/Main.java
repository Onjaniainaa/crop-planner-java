package fr.cril.cropplanner;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.ingestion.ExcelReader;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.solver.cp.ChocoModel;
import fr.cril.cropplanner.solver.sat.SAT4JModel; // IMPORT AJOUTÉ
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

        String basePath  = getArg(args, "--base",   "data/base_agronomique_thies.xlsx");
        String consoPath = getArg(args, "--conso",  "data/analyse_consommation_P2_pipeline.xlsx");
        String solverArg = getArg(args, "--solver", "sat"); // PAR DÉFAUT MAINTENANT SUR SAT
        int timeout = Integer.parseInt(getArg(args, "--time", "300"));
        String outputDir = getArg(args, "--output", "output");
        int nbPeriodes   = 12;

        try {
            System.out.println("[P1] Ingestion des données...");
            AgronomicDatabase db = ExcelReader.loadAgronomicDB(basePath);

            try {
                int[][] demande = ExcelReader.loadDemande(consoPath, db);
                db.setDemande(demande);
                System.out.println("  ✅ Demande alimentaire chargée.");
            } catch (Exception e) {
                System.out.println("  ⚠ Fichier consommation non trouvé.");
            }

            effectuerDiagnostic(db);

            System.out.println("\n[P2] Construction de la topologie...");
            GardenTopology topo = GardenTopology.bioJemm();
            System.out.println("  ✅ Topologie chargée : " + topo.getParcelles().size() + " parcelles.");

            PlanVerifier verifier = new PlanVerifier(db, topo, nbPeriodes);

            File dir = new File(outputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("  ⚠ Erreur dossier de sortie.");
            }

            // --- CHOIX DU SOLVEUR ---
            if (solverArg.equalsIgnoreCase("cp")) {
                runChoco(db, topo, nbPeriodes, timeout, verifier, outputDir);
            } else if (solverArg.equalsIgnoreCase("sat")) {
                runSAT(db, topo, nbPeriodes, timeout, verifier, outputDir); // APPEL AJOUTÉ
            } else {
                System.out.println("  ⚠ Solveur inconnu, lancement de SAT4J par défaut.");
                runSAT(db, topo, nbPeriodes, timeout, verifier, outputDir);
            }

        } catch (Exception e) {
            System.err.println("❌ Erreur critique : " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- MÉTHODE AJOUTÉE POUR SAT4J ---
    private static void runSAT(AgronomicDatabase db, GardenTopology topo, int nbPeriodes,
                               int timeout, PlanVerifier verifier, String outputDir) throws Exception {

        System.out.println("\n[P3] Initialisation SAT4J...");
        // SAT n'utilise pas de "boucle de relâchement" automatique ici, on le lance en direct
        SAT4JModel model = new SAT4JModel(db, topo, nbPeriodes);

        System.out.println("[P4] Résolution MaxSAT en cours...");
        long startTime = System.currentTimeMillis();
        SAT4JModel.SolveResult result = model.solve(timeout);
        long duration = System.currentTimeMillis() - startTime;

        if (result.plan() != null) {
            System.out.println("✅ SOLUTION SAT TROUVÉE en " + (duration / 1000.0) + "s !");
            PlanVerifier.Report report = verifier.verify(result.plan());

            String fileName = outputDir + "/plan_sat_final.html";

            HTMLExporter.export(
                    result.plan(),
                    db,
                    topo,
                    nbPeriodes,
                    report,
                    "SAT4J MaxSAT (Résultat)",
                    result.timeMs(),
                    fileName
            );
            System.out.println("  👉 Plan exporté : " + fileName);
        } else {
            System.err.println("❌ UNSAT : Le solveur SAT n'a trouvé aucune solution possible.");
        }
    }

    private static void effectuerDiagnostic(AgronomicDatabase db) {
        System.out.println("\n🔍 DIAGNOSTIC DES DONNÉES :");
        boolean alerte = false;
        for (Culture c : db.getAllCultures()) {
            if (c.isRepos()) continue;
            for (int m = 0; m < 12; m++) {
                if (db.getDemande(c.id(), m) > 0 && !db.isDisponible(c.id(), m)) {
                    System.err.println("  ❌ CONFLIT : '" + c.nom() + "' demandée au mois " + (m + 1) + " mais interdite.");
                    alerte = true;
                }
            }
        }
        if (!alerte) System.out.println("  ✅ Cohérence Demande/Calendrier : OK.");
    }

    private static void runChoco(AgronomicDatabase db, GardenTopology topo, int nbPeriodes,
                                 int timeout, PlanVerifier verifier, String outputDir) throws Exception {
        boolean solutionTrouvee = false;
        int tentative = 0;
        while (!solutionTrouvee && tentative < 5) {
            double facteurDemande = 0.6 - (tentative * 0.1);
            ChocoModel model = new ChocoModel(db, topo, nbPeriodes, facteurDemande);
            ChocoModel.SolveResult result = model.solve(timeout);

            if (result.plan() != null) {
                System.out.println("✅ SOLUTION CP TROUVÉE !");
                PlanVerifier.Report report = verifier.verify(result.plan());
                HTMLExporter.export(result.plan(), db, topo, nbPeriodes, report, "Choco-CP", (long)result.timeMs(), outputDir + "/plan_cp_t" + (tentative+1) + ".html");
                solutionTrouvee = true;
            } else {
                System.out.println("❌ UNSAT à " + (int)(facteurDemande*100) + "%");
                tentative++;
            }
        }
    }

    private static String getArg(String[] args, String key, String defaultVal) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return defaultVal;
    }
}