package fr.cril.cropplanner;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.ingestion.ExcelReader;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.solver.cp.ChocoModel;
import fr.cril.cropplanner.solver.sat.SAT4JModel;
import fr.cril.cropplanner.validation.PlanVerifier;
import fr.cril.cropplanner.export.HTMLExporter;
import fr.cril.cropplanner.model.Culture;

import java.io.File;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       AGROÉCOLOGIQUE TROPICAL PLANNING           ║");
        System.out.println("║                                                  ║");
        System.out.println("╚══════════════════════════════════════════════════╝\n");

        String basePath  = getArg(args, "--base",   "data/base_agronomique_thies.xlsx");
        String consoPath = getArg(args, "--conso",  "data/analyse_consommation_P2_pipeline.xlsx");
        String solverArg = getArg(args, "--solver", "sat");
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

            System.out.println("\n[P2] Construction de la topologie...");
            GardenTopology topo = GardenTopology.bioJemm();
            System.out.println("  ✅ Topologie chargée.");

            PlanVerifier verifier = new PlanVerifier(db, topo, nbPeriodes);

            File dir = new File(outputDir);
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("  ⚠ Erreur dossier de sortie.");
            }

            if (solverArg.equalsIgnoreCase("cp")) {
                runChoco(db, topo, nbPeriodes, timeout, verifier, outputDir);
            } else if (solverArg.equalsIgnoreCase("sat")) {
                runSAT(db, topo, nbPeriodes, timeout, verifier, outputDir);
            } else {
                System.out.println("  ⚠ Solveur inconnu, lancement de SAT4J par défaut.");
                runSAT(db, topo, nbPeriodes, timeout, verifier, outputDir);
            }

        } catch (Exception e) {
            System.err.println("❌ Erreur critique : " + e.getMessage());
            e.printStackTrace();
        }
    }

    //  HELPER : calcul C04 identique à verifierToutesContraintes()
    //   → même résultat que "C04 Demande : 44,1% (124/281)" dans la console
    private static int[] calculerC04(int[][] plan, AgronomicDatabase db,
                                     GardenTopology topo, int H) {
        int N = topo.getTotalCarres();
        int totalGroupes = 0, satisfaits = 0;
        for (Culture cult : db.getAllCultures()) {
            if (cult.isRepos() || cult.id() <= 0) continue;
            for (int t = 0; t < H; t++) {
                int dem = db.getDemande(cult.id(), t);
                if (dem <= 0 || !db.isDisponible(cult.id(), t)) continue;
                int plantees = 0;
                for (int i = 0; i < N; i++) if (plan[i][t] == cult.id()) plantees++;
                totalGroupes += dem;
                satisfaits   += Math.min(plantees, dem);
            }
        }
        return new int[]{satisfaits, totalGroupes};
    }

    // =========================================================================
    // PIPELINE CHOCO-CP
    // =========================================================================
    private static void runChoco(AgronomicDatabase db, GardenTopology topo,
                                 int nbPeriodes, double ignored,
                                 PlanVerifier verifier, String outputDir) throws Exception {

        System.out.println("\n[P3] Initialisation Choco-CP...");
        ChocoModel model = new ChocoModel(db, topo, nbPeriodes, 42L);

        System.out.println("[P4] Résolution CP (timeout 120s)...");
        ChocoModel.SolveResult result = model.solve(120);

        if (result.plan() != null) {
            System.out.println("✅ SOLUTION CP TROUVÉE !");
            verifierSaisonnaliteEtRotationsConsole(
                    result.plan(), db, topo.getTotalCarres(), nbPeriodes);
            verifierToutesContraintes(result.plan(), db, topo, nbPeriodes);

            PlanVerifier.Report report = verifier.verify(result.plan());

            //  Calcul C04 exact
            int[] c04 = calculerC04(result.plan(), db, topo, nbPeriodes);
            int c04Sat   = c04[0];
            int c04Total = c04[1];
            System.out.printf("  [HTML] C04 → %d/%d = %.1f%%%n",
                    c04Sat, c04Total, c04Total>0 ? 100.0*c04Sat/c04Total : 0);

            HTMLExporter.export(
                    result.plan(), db, topo, nbPeriodes, report,
                    "Choco-CP (Résultat)", (long) result.timeMs(),
                    c04Sat, c04Total,
                    outputDir + "/plan_cp_final.html");
            System.out.println("  👉 Plan exporté : " + outputDir + "/plan_cp_final.html");
        } else {
            System.err.println("❌ Aucune solution CP trouvée.");
        }
    }

    // =========================================================================
    // PIPELINE SAT4J
    // =========================================================================
    private static void runSAT(AgronomicDatabase db, GardenTopology topo,
                               int nbPeriodes, int timeout,
                               PlanVerifier verifier, String outputDir) throws Exception {

        System.out.println("\n[P3] Initialisation SAT4J...");
        SAT4JModel model = new SAT4JModel(db, topo, nbPeriodes);

        System.out.println("[P4] Résolution MaxSAT en cours...");
        long startTime = System.currentTimeMillis();
        SAT4JModel.SolveResult result = model.solve(timeout);
        long duration = System.currentTimeMillis() - startTime;

        if (result != null && result.plan() != null) {
            int[][] planSolution = result.plan();
            System.out.println("✅ SOLUTION SAT TROUVÉE en " + (duration / 1000.0) + "s !");

            verifierSaisonnaliteEtRotationsConsole(planSolution, db,
                    topo.getTotalCarres(), nbPeriodes);
            verifierToutesContraintes(planSolution, db, topo, nbPeriodes);

            PlanVerifier.Report report = verifier.verify(planSolution);

            //  Calcul C04 exact
            int[] c04 = calculerC04(planSolution, db, topo, nbPeriodes);

            HTMLExporter.export(
                    planSolution, db, topo, nbPeriodes, report,
                    "SAT4J MaxSAT (Résultat)", result.timeMs(),
                    c04[0], c04[1],
                    outputDir + "/plan_sat_final.html");
            System.out.println("  👉 Plan exporté avec succès : " + outputDir + "/plan_sat_final.html");
        } else {
            System.err.println("❌ UNSAT : Le solveur SAT n'a trouvé aucune solution possible.");
        }
    }

    // =========================================================================
    // VÉRIFICATION COMPLÈTE DES CONTRAINTES
    // =========================================================================
    public static void verifierSaisonnaliteEtRotationsConsole(
            int[][] plan, AgronomicDatabase db, int N, int H) {
        System.out.println("\n==================================================");
        System.out.println("🔍 ANALYSE TECHNIQUE ET DÉTECTION DES ANOMALIES");
        System.out.println("==================================================");
        int fautesSaison = 0, fautesRotation = 0;
        for (int i = 0; i < N; i++) {
            for (int t = 0; t < H; t++) {
                int cultureId = plan[i][t];
                if (cultureId <= 0) continue;
                Culture culture = db.getCultureById(cultureId);
                if (!db.isDisponible(cultureId, t)) {
                    System.out.printf("🚨 [BUG SAISON] Parcelle %d, Mois %d : %s interdit.%n",
                            i, t, culture.nom());
                    fautesSaison++;
                }
                String familleId = (culture.famille() != null) ? culture.famille().id() : null;
                if (familleId != null) {
                    var famOpt = db.getAllFamilles().stream()
                            .filter(f -> f.id().equals(familleId)).findFirst();
                    if (famOpt.isPresent()) {
                        int retourMin = famOpt.get().retourMinPeriodes();
                        boolean estDernierMois = (t+1>=H)||(plan[i][t+1]!=cultureId);
                        if (!estDernierMois) continue;
                        for (int k=t+1; k<t+1+retourMin && k<H; k++) {
                            int futId = plan[i][k];
                            if (futId <= 0) continue;
                            Culture futCult = db.getCultureById(futId);
                            String futFam = (futCult.famille()!=null)?futCult.famille().id():null;
                            if (familleId.equals(futFam)) {
                                System.out.printf("⚠️ [BUG ROTATION] Parcelle %d : %s (fin Mois %d) → %s (Mois %d)%n",
                                        i, culture.nom(), t, futCult.nom(), k);
                                fautesRotation++;
                            }
                        }
                    }
                }
            }
        }
        System.out.println("--------------------------------------------------");
        System.out.println("📊 RAPPORT FINAL DE LA CONSOLE :");
        System.out.printf(" -> Infractions de SAISONNALITÉ (C03) détectées : %d%n", fautesSaison);
        System.out.printf(" -> Infractions de ROTATION (C01) détectées     : %d%n", fautesRotation);
        System.out.println("==================================================\n");
    }

    public static void verifierToutesContraintes(int[][] plan, AgronomicDatabase db,
                                                 fr.cril.cropplanner.transformation.GardenTopology topo, int H) {
        int N = topo.getTotalCarres();
        System.out.println("\n==================================================");
        System.out.println("📋 VÉRIFICATION COMPLÈTE DES CONTRAINTES");
        System.out.println("==================================================");

        // C03
        System.out.println("\n🌿 C03 — Saisonnalité :");
        int violC03 = 0;
        for (int i=0;i<N;i++) for (int t=0;t<H;t++) {
            int cid=plan[i][t]; if(cid<=0) continue;
            if(!db.isDisponible(cid,t)){
                System.out.printf("   ❌ Parcelle %d, Mois %d : %s interdit%n",i,t,db.getCultureById(cid).nom());
                violC03++;
            }
        }
        System.out.printf("   → %s (%d infraction%s)%n",
                violC03==0?"✅ AUCUNE INFRACTION":"❌ VIOLATIONS",violC03,violC03>1?"s":"");

        // C01
        System.out.println("\n🔄 C01 — Rotation familles botaniques :");
        int violC01=0;
        for (int i=0;i<N;i++) for (int t=0;t<H;t++) {
            int cid=plan[i][t]; if(cid<=0) continue;
            fr.cril.cropplanner.model.Culture cult=db.getCultureById(cid);
            if(cult==null||cult.famille()==null) continue;
            String famId=cult.famille().id();
            if((t+1<H)&&(plan[i][t+1]==cid)) continue;
            var famOpt=db.getAllFamilles().stream().filter(f->f.id().equals(famId)).findFirst();
            if(famOpt.isEmpty()) continue;
            int retourMin=famOpt.get().retourMinPeriodes();
            for(int k=t+1;k<t+1+retourMin&&k<H;k++){
                int futId=plan[i][k]; if(futId<=0) continue;
                fr.cril.cropplanner.model.Culture futCult=db.getCultureById(futId);
                if(futCult!=null&&futCult.famille()!=null&&futCult.famille().id().equals(famId)){
                    System.out.printf("   ❌ Parcelle %d : %s (fin mois %d) → %s (mois %d) trop tôt%n",
                            i,cult.nom(),t,futCult.nom(),k);
                    violC01++;
                }
            }
        }
        System.out.printf("   → %s (%d infraction%s)%n",
                violC01==0?"✅ AUCUNE INFRACTION":"❌ VIOLATIONS",violC01,violC01>1?"s":"");

        // C02
        System.out.println("\n🚫 C02 — Adjacence :");
        int violC02=0,totalAdj=0;
        for(int t=0;t<H;t++) for(int[] edge:topo.getEdges()){
            totalAdj++;
            int c1id=plan[edge[0]][t],c2id=plan[edge[1]][t];
            fr.cril.cropplanner.model.Culture cult1=db.getCultureById(c1id);
            fr.cril.cropplanner.model.Culture cult2=db.getCultureById(c2id);
            if(db.getCompatibilite(cult1,cult2)==fr.cril.cropplanner.model.TypeAssociation.DEFAVORABLE){
                System.out.printf("   ❌ Mois %d : Parcelles %d(%s) et %d(%s) incompatibles%n",
                        t,edge[0],cult1.nom(),edge[1],cult2.nom());
                violC02++;
            }
        }
        double pctAdj=totalAdj>0?100.0*(totalAdj-violC02)/totalAdj:0;
        System.out.printf("   → %s : %.1f%% (%d violation%s sur %d paires)%n",
                violC02==0?"✅ 100% RESPECTÉ":"⚠️ VIOLATIONS",
                pctAdj,violC02,violC02>1?"s":"",totalAdj);

        // C04 — Demande alimentaire
        System.out.println("\n🛒 C04 — Demande alimentaire :");
        int totalGroupes=0,satisfaits=0;
        for(fr.cril.cropplanner.model.Culture cult:db.getAllCultures()){
            if(cult.isRepos()||cult.id()<=0) continue;
            for(int t=0;t<H;t++){
                int dem=db.getDemande(cult.id(),t);
                if(dem<=0||!db.isDisponible(cult.id(),t)) continue;
                int plantees=0;
                for(int i=0;i<N;i++) if(plan[i][t]==cult.id()) plantees++;
                totalGroupes+=dem;
                satisfaits+=Math.min(plantees,dem);
            }
        }
        double pctDem=totalGroupes>0?100.0*satisfaits/totalGroupes:0;
        System.out.printf("   → Score demande : %.1f%% (%d/%d)%n",pctDem,satisfaits,totalGroupes);

        // C05
        System.out.println("\n💧 C05 — Budget eau :");
        int[] eauArray=db.getEauParCultureArray(); int violC05=0;
        for(int t=0;t<H;t++){
            int conso=0;
            for(int i=0;i<N;i++){int c=plan[i][t];if(c>0&&c<eauArray.length)conso+=eauArray[c];}
            String ok=conso<=500?"✅ OK":"❌ DÉPASSÉ"; if(conso>500)violC05++;
            System.out.printf("   Mois %02d : %d m³ / 500 m³ → %s%n",t,conso,ok);
        }
        System.out.printf("   → %s%n",violC05==0?"✅ BUDGET EAU RESPECTÉ 100%":"❌ "+violC05+" mois en dépassement");

        // BILAN
        System.out.println("\n==================================================");
        System.out.println("📊 BILAN GLOBAL");
        System.out.println("==================================================");
        System.out.printf("  C01 Rotation     : %s%n",violC01==0?"✅ 0 violation":"❌ "+violC01);
        System.out.printf("  C02 Adjacence    : %s%n",violC02==0?"✅ 100%":"⚠️ "+String.format("%.1f%%",pctAdj));
        System.out.printf("  C03 Saisonnalité : %s%n",violC03==0?"✅ 0 violation":"❌ "+violC03);
        System.out.printf("  C04 Demande      : %.1f%% (%d/%d)%n",pctDem,satisfaits,totalGroupes);
        System.out.printf("  C05 Budget eau   : %s%n",violC05==0?"✅ 100%":"❌ "+violC05+" mois dépassés");
        System.out.println("==================================================\n");

        afficherRapportProductionConsommation(plan, db, topo, H);
    }

    public static void afficherRapportProductionConsommation(
            int[][] plan, AgronomicDatabase db,
            fr.cril.cropplanner.transformation.GardenTopology topo, int H) {

        int N = topo.getTotalCarres();
        String[] moisNoms = {"Jan","Fév","Mar","Avr","Mai","Jun","Jul","Aoû","Sep","Oct","Nov","Déc"};

        System.out.println("\n" + "=".repeat(72));
        System.out.println("🌾 RAPPORT PRODUCTION vs CONSOMMATION — Bio Jêmm, Thiès");
        System.out.println("=".repeat(72));
        System.out.printf("   %-22s %6s %6s %6s %6s%n","Culture","Produit","Demande","Déficit","Statut");
        System.out.println("   " + "-".repeat(60));

        int totalProduitAnnuel=0, totalDemandeAnnuel=0;
        java.util.List<Culture> cultsTri=new java.util.ArrayList<>(db.getAllCultures());
        cultsTri.removeIf(c -> c.isRepos());

        java.util.Map<Integer,Integer> prodAnnuelle=new java.util.HashMap<>();
        java.util.Map<Integer,Integer> demAnnuelle=new java.util.HashMap<>();
        for(Culture cult:cultsTri){
            int prodTotal=0,demTotal=0;
            for(int t=0;t<H;t++){
                for(int i=0;i<N;i++) if(plan[i][t]==cult.id()) prodTotal++;
                demTotal+=db.getDemande(cult.id(),t);
            }
            prodAnnuelle.put(cult.id(),prodTotal);
            demAnnuelle.put(cult.id(),demTotal);
        }

        cultsTri.sort((a,b)->{
            int defA=demAnnuelle.getOrDefault(a.id(),0)-prodAnnuelle.getOrDefault(a.id(),0);
            int defB=demAnnuelle.getOrDefault(b.id(),0)-prodAnnuelle.getOrDefault(b.id(),0);
            return Integer.compare(defB,defA);
        });

        for(Culture cult:cultsTri){
            int dem=demAnnuelle.getOrDefault(cult.id(),0); if(dem==0) continue;
            int prod=prodAnnuelle.getOrDefault(cult.id(),0);
            int def=Math.max(0,dem-prod);
            double taux=dem>0?100.0*prod/dem:0;
            String statut=taux>=100?"✅ COUVERT":taux>=70?"🟡 PARTIEL ("+String.format("%.0f",taux)+"%)":
                                                taux>=40?"🟠 FAIBLE  ("+String.format("%.0f",taux)+"%)":
                                                "🔴 CRITIQUE("+String.format("%.0f",taux)+"%)";
            System.out.printf("   %-22s %6d %6d %6d   %s%n",cult.nom(),prod,dem,def,statut);
            totalProduitAnnuel+=prod; totalDemandeAnnuel+=dem;
        }

        System.out.println("   " + "-".repeat(60));
        double tauxGlobal=totalDemandeAnnuel>0?100.0*totalProduitAnnuel/totalDemandeAnnuel:0;
        System.out.printf("   %-22s %6d %6d %6d   %.1f%% BILAN GLOBAL%n",
                "TOTAL ANNUEL",totalProduitAnnuel,totalDemandeAnnuel,
                Math.max(0,totalDemandeAnnuel-totalProduitAnnuel),tauxGlobal);

        System.out.println("\n"+"=".repeat(72));
        System.out.println("📅 PRODUCTION PAR MOIS vs BESOINS DU FOYER");
        System.out.println("=".repeat(72));
        for(int t=0;t<H;t++){
            java.util.Map<Integer,Integer> prodMois=new java.util.HashMap<>();
            for(int i=0;i<N;i++){int c=plan[i][t];if(c>0)prodMois.merge(c,1,Integer::sum);}
            int totalDemMois=0,totalProdMois=0;
            java.util.List<String> manquants=new java.util.ArrayList<>();
            for(Culture cult:cultsTri){
                int dem=db.getDemande(cult.id(),t); if(dem==0) continue;
                int prod=prodMois.getOrDefault(cult.id(),0);
                totalDemMois+=dem; totalProdMois+=prod;
                if(prod<dem) manquants.add(cult.nom()+"("+prod+"/"+dem+")");
            }
            double tauxMois=totalDemMois>0?100.0*totalProdMois/totalDemMois:100;
            String icon=tauxMois>=80?"✅":tauxMois>=50?"🟡":"🔴";
            System.out.printf("  %s %-3s : %5.1f%% (%2d/%2d)",icon,moisNoms[t],tauxMois,totalProdMois,totalDemMois);
            if(!manquants.isEmpty()){
                String lst=String.join(", ",manquants.subList(0,Math.min(4,manquants.size())));
                if(manquants.size()>4) lst+=" +"+(manquants.size()-4)+" autres";
                System.out.print("  ← manque: "+lst);
            }
            System.out.println();
        }

        System.out.println("\n"+"=".repeat(72));
        System.out.println("💡 TOP 5 LÉGUMES CRITIQUES POUR LE FOYER :");
        System.out.println("=".repeat(72));
        int rang=1;
        for(Culture cult:cultsTri){
            int dem=demAnnuelle.getOrDefault(cult.id(),0);
            int prod=prodAnnuelle.getOrDefault(cult.id(),0);
            if(dem==0||prod>=dem||rang>5) continue;
            System.out.printf("  %d. %-22s : %d produits / %d demandés → manque %d (%.0f%% couvert)%n",
                    rang++,cult.nom(),prod,dem,dem-prod,100.0*prod/dem);
        }
        System.out.println("=".repeat(72)+"\n");
    }

    private static String getArg(String[] args, String key, String defaultVal) {
        for(int i=0;i<args.length-1;i++) if(args[i].equals(key)) return args[i+1];
        return defaultVal;
    }
}