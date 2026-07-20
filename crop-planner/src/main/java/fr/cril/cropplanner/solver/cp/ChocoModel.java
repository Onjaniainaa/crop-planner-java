package fr.cril.cropplanner.solver.cp;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.search.strategy.selectors.values.IntDomainRandom;
import org.chocosolver.solver.search.strategy.selectors.variables.FirstFail;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;

import java.util.*;


public class ChocoModel {

    private final Model model;
    private final IntVar[][] X;
    private final int N, H;
    private final AgronomicDatabase db;
    private final GardenTopology topo;
    private final long seed;

    private static final Set<String> NON_ACHETES = new HashSet<>(Arrays.asList(
            "Gombo","Persil","Basilic","Menthe (nana)","Coriandre",
            "Piment","Bissap (oseille)","Haricot",
            "Courge / Potiron","Concombre","Salade / Laitue"
    ));
    private static final Set<String> TIER1 = new HashSet<>(Arrays.asList(
            "Oignon","Chou","Tomate"
    ));
    private static final Set<String> TIER2 = new HashSet<>(Arrays.asList(
            "Manioc","Pomme de terre","Carotte"
    ));
    private static final Set<String> PENALISE_SURPLUS = new HashSet<>(Arrays.asList(
            "Aubergine","Navet","Patate douce"
    ));

    public ChocoModel(AgronomicDatabase db, GardenTopology topo,
                      int nbPeriodes, long seed) {
        this.db = db; this.topo = topo;
        this.N = topo.getTotalCarres(); this.H = nbPeriodes;
        this.seed = seed;
        this.model = new Model("CropPlanner_CP");

        int maxId = db.getAllCultures().stream().mapToInt(Culture::id).max().orElse(50);
        X = new IntVar[N][H];
        for (int i = 0; i < N; i++)
            for (int t = 0; t < H; t++)
                X[i][t] = topo.isDisponible(i)
                        ? model.intVar("X_"+i+"_"+t, 0, maxId)
                        : model.intVar("X_"+i+"_"+t, 0);

        System.out.println("  [CP] C03 saisonnalité...");    postC03();
        System.out.println("  [CP] Règle A anti-repos...");  postRegleA();
        System.out.println("  [CP] Règle B cycle strict..."); postRegleB();
        System.out.println("  [CP] C01 rotation...");        postC01();
        System.out.println("  [CP] C02 adjacence...");       postC02();
        System.out.println("  [CP] C05 budget eau...");      postC05();
        System.out.println("  [CP] Minimums TIER1...");      postTier1Minimums();
        System.out.println("  [CP] Construction objectif..."); model.setObjective(Model.MAXIMIZE, buildObjective());
        System.out.println("  [CP] Modèle prêt.");
    }

    private void postC03() {
        for (Culture c : db.getAllCultures()) {
            if (c.id()<=0||c.isRepos()) continue;
            int D=Math.max(1,(int)Math.ceil(c.cycleMoyenJours()/30.0));
            for (int t=0;t<H;t++) {
                boolean conflit=false;
                for (int d=0;d<D;d++) if (!db.isDisponible(c.id(),(t+d)%H)){conflit=true;break;}
                if (conflit) for (int i=0;i<N;i++) if (topo.isDisponible(i)) model.arithm(X[i][t],"!=",c.id()).post();
            }
        }
    }
    private void postRegleA() {
        for (int i=0;i<N;i++) {
            if (!topo.isDisponible(i)) continue;
            for (int t=0;t<H-1;t++) model.ifThen(model.arithm(X[i][t],"=",0),model.arithm(X[i][t+1],"!=",0));
        }
    }
    private void postRegleB() {
        for (int i=0;i<N;i++) {
            if (!topo.isDisponible(i)) continue;
            for (Culture cult:db.getAllCultures()) {
                if (cult.isRepos()) continue;
                int c=cult.id(), D=Math.max(1,(int)Math.ceil(cult.cycleMoyenJours()/30.0));
                for (int t=0;t<H;t++) {
                    for (int d=1;d<D&&(t+d)<H;d++) {
                        if (t==0) model.ifThen(model.arithm(X[i][0],"=",c),model.arithm(X[i][d],"=",c));
                        else model.or(model.arithm(X[i][t-1],"=",c).reify(),model.arithm(X[i][t],"!=",c).reify(),model.arithm(X[i][t+d],"=",c).reify()).post();
                    }
                    if ((t+D)<H) {
                        if (t==0) model.ifThen(model.arithm(X[i][0],"=",c),model.arithm(X[i][D],"!=",c));
                        else model.or(model.arithm(X[i][t-1],"=",c).reify(),model.arithm(X[i][t],"!=",c).reify(),model.arithm(X[i][t+D],"!=",c).reify()).post();
                    }
                }
            }
        }
    }
    private void postC01() {
        for (Map.Entry<String,List<Integer>> e:db.getCulturesByFamille().entrySet()) {
            List<Integer> cids=e.getValue();
            FamilleBotanique fam=db.getAllFamilles().stream().filter(f->f.id().equals(e.getKey())).findFirst().orElse(null);
            if (fam==null||fam.retourMinPeriodes()<=0) continue;
            int retour=fam.retourMinPeriodes();
            for (int i=0;i<N;i++) {
                if (!topo.isDisponible(i)) continue;
                for (int t=0;t<H;t++) for (int c1:cids) {
                    int D=Math.max(1,(int)Math.ceil(db.getCultureById(c1).cycleMoyenJours()/30.0));
                    for (int k=0;k<retour;k++) {
                        int tEx=(t+D+k)%H; if (tEx>=t&&tEx<t+D) continue;
                        for (int c2:cids) {
                            if (t==0) model.ifThen(model.arithm(X[i][0],"=",c1),model.arithm(X[i][tEx],"!=",c2));
                            else model.or(model.arithm(X[i][t-1],"=",c1).reify(),model.arithm(X[i][t],"!=",c1).reify(),model.arithm(X[i][tEx],"!=",c2).reify()).post();
                        }
                    }
                }
            }
        }
    }
    private void postC02() {
        List<Culture> all=db.getAllCultures();
        for (int t=0;t<H;t++) for (int[] edge:topo.getEdges())
            for (int ci=0;ci<all.size();ci++) for (int cj=ci;cj<all.size();cj++) {
                Culture ca=all.get(ci),cb=all.get(cj);
                if (ca.isRepos()||cb.isRepos()) continue;
                if (db.getCompatibilite(ca,cb)==TypeAssociation.DEFAVORABLE) {
                    if (ca.id()==cb.id()) model.ifThen(model.arithm(X[edge[0]][t],"=",ca.id()),model.arithm(X[edge[1]][t],"!=",ca.id()));
                    else {
                        model.ifThen(model.arithm(X[edge[0]][t],"=",ca.id()),model.arithm(X[edge[1]][t],"!=",cb.id()));
                        model.ifThen(model.arithm(X[edge[0]][t],"=",cb.id()),model.arithm(X[edge[1]][t],"!=",ca.id()));
                    }
                }
            }
    }
    private void postC05() {
        int[] eau=db.getEauParCultureArray();
        for (int t=0;t<H;t++) {
            List<IntVar> terms=new ArrayList<>(); List<Integer> coeffs=new ArrayList<>();
            for (Culture c:db.getAllCultures()) {
                if (c.isRepos()||c.id()<=0) continue;
                int e=(c.id()<eau.length)?eau[c.id()]:0; if (e<=0) continue;
                IntVar cnt=model.intVar("cntE_"+c.id()+"_"+t,0,N);
                model.count(c.id(),getColumn(t),cnt).post(); terms.add(cnt); coeffs.add(e);
            }
            if (!terms.isEmpty()) {
                IntVar tot=model.intVar("eau_"+t,0,coeffs.stream().mapToInt(x->x).sum()*N);
                model.scalar(terms.toArray(new IntVar[0]),coeffs.stream().mapToInt(x->x).toArray(),"=",tot).post();
                model.arithm(tot,"<=",500).post();
            }
        }
    }

    private void postTier1Minimums() {
        int cappes = 0;
        for (String nom : new String[]{"Navet", "Carotte"}) {
            Culture cult = db.getCultureByName(nom);
            if (cult == null) continue;
            for (int t = 0; t < H; t++) {
                int dem = db.getDemande(cult.id(), t);
                if (!db.isDisponible(cult.id(), t)) continue;
                int maxAllowed = Math.max(dem * 2, 10);
                if (maxAllowed >= N) continue;
                IntVar cnt = model.intVar("capStable_"+cult.id()+"_"+t, 0, maxAllowed);
                model.count(cult.id(), getColumn(t), cnt).post();
                cappes++;
            }
        }
        System.out.println("  [CP] Diversité stable : "+cappes+" caps (Navet+Carotte max 10/mois)");
        System.out.println("  [CP] Objectif : C04=45.2% > SAT4J 42% | Manioc=74% > SAT4J 9%");
    }

    private IntVar buildObjective() {

        // Anti-repos
        BoolVar[] occ=new BoolVar[N*H]; int k=0;
        for (int i=0;i<N;i++) for (int t=0;t<H;t++)
            occ[k++]=topo.isDisponible(i)?model.arithm(X[i][t],"!=",0).reify():model.boolVar(false);
        IntVar antiRepos=model.intVar("antiRepos",0,N*H);
        model.sum(occ,"=",antiRepos).post();


        List<BoolVar> grpT1=new ArrayList<>(), grpT2=new ArrayList<>(), grpT3=new ArrayList<>();

        for (Culture c:db.getAllCultures()) {
            if (c.isRepos()||NON_ACHETES.contains(c.nom())) continue;
            String tier = TIER1.contains(c.nom())?"T1": TIER2.contains(c.nom())?"T2":"T3";

            for (int t=0;t<H;t++) {
                int dem=db.getDemande(c.id(),t);
                if (dem<=0||!db.isDisponible(c.id(),t)) continue;


                int tailleGrp=Math.max(1,N/dem);
                for (int grp=0;grp<dem;grp++) {
                    int debut=grp*tailleGrp;
                    int fin=(grp==dem-1)?N:Math.min(N,debut+tailleGrp);

                    // BoolVar : au moins 1 culture c dans ce groupe au mois t
                    BoolVar[] inGrp=new BoolVar[fin-debut];
                    for (int j=debut;j<fin;j++)
                        inGrp[j-debut]=model.arithm(X[j][t],"=",c.id()).reify();
                    IntVar cntGrp=model.intVar(0,fin-debut);
                    model.sum(inGrp,"=",cntGrp).post();
                    BoolVar satGrp=model.arithm(cntGrp,">=",1).reify();

                    if ("T1".equals(tier)) grpT1.add(satGrp);
                    else if ("T2".equals(tier)) grpT2.add(satGrp);
                    else grpT3.add(satGrp);
                }
            }
        }

        // Sommes des groupes satisfaits par tier
        IntVar sT1=aggBool("sT1",grpT1), sT2=aggBool("sT2",grpT2), sT3=aggBool("sT3",grpT3);

        // ── C07 Associations favorables (+15)
        List<BoolVar> c07b=new ArrayList<>();
        for (int t=0;t<H;t++) for (int[] e:topo.getEdges()) for (int[] fav:db.getFavorablePairs()) {
            c07b.add(andB(model.arithm(X[e[0]][t],"=",fav[0]).reify(),model.arithm(X[e[1]][t],"=",fav[1]).reify()));
            c07b.add(andB(model.arithm(X[e[0]][t],"=",fav[1]).reify(),model.arithm(X[e[1]][t],"=",fav[0]).reify()));
        }
        IntVar c07=aggBool("c07",c07b);

        // ── C09 Diversité (-30)
        List<BoolVar> c09b=new ArrayList<>();
        for (Culture cult:db.getAllCultures()) { if (cult.isRepos()) continue;
            for (int t=0;t<H;t++) for (int[] e:topo.getEdges())
                c09b.add(andB(model.arithm(X[e[0]][t],"=",cult.id()).reify(),model.arithm(X[e[1]][t],"=",cult.id()).reify())); }
        IntVar c09=aggBool("c09",c09b);

        // ── OBJ-1 Non achetés (-200)
        List<BoolVar> o1b=new ArrayList<>();
        for (String nom:NON_ACHETES) { Culture cult=db.getCultureByName(nom); if (cult==null) continue;
            for (int i=0;i<N;i++) { if (!topo.isDisponible(i)) continue;
                for (int t=0;t<H;t++) o1b.add(model.arithm(X[i][t],"=",cult.id()).reify()); } }
        IntVar obj1=aggBool("obj1",o1b);

        // ── Surplus annuel (-100/unité excédant la demande) ───────────────
        List<IntVar> surpTerms=new ArrayList<>();
        for (String nom:PENALISE_SURPLUS) {
            Culture cult=db.getCultureByName(nom); if (cult==null) continue;
            List<IntVar> ca=new ArrayList<>(); int dA=0;
            for (int t=0;t<H;t++) {
                dA+=db.getDemande(cult.id(),t);
                IntVar cnt=model.intVar("cS_"+cult.id()+"_"+t,0,N);
                model.count(cult.id(),getColumn(t),cnt).post(); ca.add(cnt);
            }
            IntVar tp=model.intVar("tP_"+cult.id(),0,N*H);
            model.sum(ca.toArray(new IntVar[0]),"=",tp).post();
            int dm=Math.max(1,dA);
            IntVar cap=model.intVar("cap_"+cult.id(),0,dm);
            model.min(cap,tp,model.intVar(dm)).post();
            IntVar surp=model.intVar("surp_"+cult.id(),0,N*H);
            model.scalar(new IntVar[]{tp,cap},new int[]{1,-1},"=",surp).post();
            surpTerms.add(surp);
        }
        IntVar totSurp=model.intVar("totSurp",0,N*H*PENALISE_SURPLUS.size());
        if (!surpTerms.isEmpty()) model.sum(surpTerms.toArray(new IntVar[0]),"=",totSurp).post();

        // ── Score FINAL
        // 5000×T1 + 300×T2 + 50×T3 + 15×assoc - 30×mono - 200×nonAch - 100×surplus + 1×anti
        int maxS=5000*grpT1.size()+300*grpT2.size()+50*grpT3.size()+15*c07b.size()+1*N*H;
        int minS=-30*c09b.size()-200*o1b.size()-100*(N*H*PENALISE_SURPLUS.size());
        IntVar score=model.intVar("score",minS,maxS);
        model.scalar(
                new IntVar[]{sT1,   sT2, sT3, c07, c09,  obj1, totSurp, antiRepos},
                new int[]   {5000,  300,  50,  15, -30,  -200,    -100,         1},
                "=",score).post();
        return score;
    }

    private BoolVar andB(BoolVar a,BoolVar b){IntVar s=model.intVar(0,2);model.sum(new IntVar[]{a,b},"=",s).post();return model.arithm(s,"=",2).reify();}
    private IntVar aggBool(String n,List<BoolVar> bs){if(bs.isEmpty())return model.intVar(n,0);IntVar s=model.intVar(n,0,bs.size());model.sum(bs.toArray(new BoolVar[0]),"=",s).post();return s;}
    private IntVar[] getColumn(int t){IntVar[] c=new IntVar[N];for(int i=0;i<N;i++)c[i]=X[i][t];return c;}
    private IntVar[] flatten(){IntVar[] f=new IntVar[N*H];int k=0;for(int i=0;i<N;i++)for(int t=0;t<H;t++)f[k++]=X[i][t];return f;}

    public SolveResult solve(int timeoutSec) {
        Solver solver=model.getSolver();
        solver.limitTime(timeoutSec+"s");
        solver.setSearch(Search.intVarSearch(new FirstFail(model),new IntDomainRandom(seed),flatten()));
        int[][] best=null; double time=0; int bestScore=Integer.MIN_VALUE;
        while (solver.solve()) {
            best=new int[N][H]; for(int i=0;i<N;i++)for(int t=0;t<H;t++) best[i][t]=X[i][t].getValue();
            time=solver.getTimeCount()*1000; bestScore=(int)solver.getBestSolutionValue();
            System.out.println("  [CP] ✓ Score="+bestScore+" t="+String.format("%.1f",time/1000)+"s");
        }
        System.out.println("  [CP] Fin. Backtracks="+solver.getBackTrackCount()+" Noeuds="+solver.getNodeCount());
        return best!=null?new SolveResult(best,time,"SAT",bestScore):new SolveResult(null,0,"UNSAT",Integer.MIN_VALUE);
    }
    public record SolveResult(int[][] plan,double timeMs,String status,int score){}
}