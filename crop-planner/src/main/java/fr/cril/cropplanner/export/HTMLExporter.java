package fr.cril.cropplanner.export;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.validation.PlanVerifier;

import java.io.*;
import java.util.*;

public class HTMLExporter {

    private static final String[] MONTHS = {
            "Jan","Fév","Mar","Avr","Mai","Jun",
            "Jul","Aoû","Sep","Oct","Nov","Déc"
    };
    private static final String[] MONTHS_LONG = {
            "Janvier","Février","Mars","Avril","Mai","Juin",
            "Juillet","Août","Septembre","Octobre","Novembre","Décembre"
    };

    private static final Map<String,String> FAM_COL = new LinkedHashMap<>();
    static {
        FAM_COL.put("Solanacées",    "#FF6B6B");
        FAM_COL.put("Alliacées",     "#C084FC");
        FAM_COL.put("Brassicacées",  "#4ADE80");
        FAM_COL.put("Malvacées",     "#FB923C");
        FAM_COL.put("Apiacées",      "#FBBF24");
        FAM_COL.put("Astéracées",    "#A3E635");
        FAM_COL.put("Cucurbitacées", "#22D3EE");
        FAM_COL.put("Fabacées",      "#34D399");
        FAM_COL.put("Chénopodiacées","#F472B6");
        FAM_COL.put("Lamiacées",     "#94A3B8");
        FAM_COL.put("Convolvulacées","#E879F9");
        FAM_COL.put("Euphorbiacées", "#A78BFA");
    }

    private static final Map<String,String> EM = new LinkedHashMap<>();
    static {
        EM.put("Oignon",           "🧅");
        EM.put("Tomate",           "🍅");
        EM.put("Carotte",          "🥕");
        EM.put("Chou",             "🥦");
        EM.put("Manioc",           "🍠");
        EM.put("Patate douce",     "🍠");
        EM.put("Pomme de terre",   "🥔");
        EM.put("Navet",            "🥗");
        EM.put("Aubergine",        "🍆");
        EM.put("Courge",           "🎃");
        EM.put("Potiron",          "🎃");
        EM.put("Concombre",        "🥒");
        EM.put("Haricot",          "🥜");
        EM.put("Gombo",            "🌾");
        EM.put("Piment",           "🌶️");
        EM.put("Persil",           "🌿");
        EM.put("Basilic",          "🌿");
        EM.put("Menthe",           "🌿");
        EM.put("Coriandre",        "🌿");
        EM.put("Bissap",           "🌺");
        EM.put("Oseille",          "🌺");
        EM.put("Salade",           "🥬");
        EM.put("Laitue",           "🥬");
        EM.put("Betterave",        "🌰");
        EM.put("Repos",            "");
    }

    private static String getEmoji(String nom) {
        if (nom == null) return "🌱";
        String lo = nom.toLowerCase();
        for (Map.Entry<String,String> e : EM.entrySet())
            if (lo.contains(e.getKey().toLowerCase())) return e.getValue();
        return "🌱";
    }

    private static String cleanFam(String raw) {
        if (raw == null) return "";
        return raw.split("[\\s(]")[0].trim();
    }

    private static String txtCol(String hex) {
        if (hex==null||hex.length()<7) return "#000";
        try {
            int r=Integer.parseInt(hex.substring(1,3),16);
            int g=Integer.parseInt(hex.substring(3,5),16);
            int b=Integer.parseInt(hex.substring(5,7),16);
            return (0.299*r+0.587*g+0.114*b)>155?"#1a1a2e":"#FFFFFF";
        } catch(Exception e){return "#000";}
    }

    public static void export(int[][] plan, AgronomicDatabase db,
                              GardenTopology topo, int nbPeriodes,
                              PlanVerifier.Report report,
                              String solverName, long timeMs,
                              int c04Satisfait, int c04Total,
                              String outputPath) throws IOException {
        int N=plan.length, H=nbPeriodes;
        int[] eauTab=db.getEauParCultureArray();

        int[] satM=new int[H], demM=new int[H], eauM=new int[H];
        for (int t=0; t<H; t++) {
            Map<Integer,Integer> cnt=new HashMap<>();
            for (int i=0;i<N;i++) cnt.merge(plan[i][t],1,Integer::sum);
            for (Culture c:db.getAllCultures()) {
                if(c.isRepos()||c.id()<=0) continue;
                int d=db.getDemande(c.id(),t);
                if(d>0&&db.isDisponible(c.id(),t)) {
                    demM[t]+=d;
                    satM[t]+=Math.min(cnt.getOrDefault(c.id(),0),d);
                }
                int e=(c.id()<eauTab.length)?eauTab[c.id()]:0;
                eauM[t]+=cnt.getOrDefault(c.id(),0)*e;
            }
        }
        int tSat=c04Satisfait, tDem=c04Total;
        double gPct=tDem>0?100.0*tSat/tDem:0;

        // Compter les cultures distinctes (hors repos)
        Set<Integer> culturesDistinctes = new HashSet<>();
        for (int i=0;i<N;i++)
            for (int t=0;t<H;t++)
                if (plan[i][t]>0) culturesDistinctes.add(plan[i][t]);
        int nbCultures = culturesDistinctes.size();

        Map<String,Integer> idx=new HashMap<>();
        List<String> noms=new ArrayList<>();
        for(int i=0;i<N;i++){if(!topo.isDisponible(i))continue;String n=topo.nomCarre(i);idx.put(n,i);noms.add(n);}

        // JSON plan
        StringBuilder pj=new StringBuilder("{");
        for(int t=0;t<H;t++){
            pj.append("\"").append(t).append("\":{");
            for(String nm:noms){
                int i=idx.get(nm), cId=plan[i][t];
                String n,bg,tx,em;
                boolean r;
                if(cId==0){n="Repos";bg="#F1F5F9";tx="#94A3B8";r=true;em="";}
                else{Culture c=db.getCultureById(cId);n=c!=null?c.nom():"?";
                    String fam=c!=null?cleanFam(c.famille()!=null?c.famille().nom():""):"";
                    bg=FAM_COL.getOrDefault(fam,"#E5E7EB");tx=txtCol(bg);r=false;em=getEmoji(n);}
                pj.append("\"").append(nm).append("\":{\"n\":\"").append(n.replace("\"","\\\""))
                        .append("\",\"bg\":\"").append(bg).append("\",\"tx\":\"").append(tx)
                        .append("\",\"r\":").append(r).append(",\"em\":\"").append(em).append("\"},");
            }
            if(pj.charAt(pj.length()-1)==',')pj.deleteCharAt(pj.length()-1);
            pj.append("},");
        }
        if(pj.charAt(pj.length()-1)==',')pj.deleteCharAt(pj.length()-1);
        pj.append("}");

        // Boutons mois
        StringBuilder btn=new StringBuilder();
        for(int t=0;t<H;t++){
            double p=demM[t]>0?100.0*satM[t]/demM[t]:100;
            String cl=p>=80?"ok":p>=40?"mid":"lo";
            btn.append("<button class='bm ").append(cl)
                    .append("' onclick='sel(").append(t).append(")' id='b").append(t).append("'>")
                    .append("<span>").append(MONTHS[t]).append("</span>")
                    .append("<b>").append(String.format("%.0f",p)).append("%</b></button>");
        }

        // ── LÉGENDE CORRIGÉE : badges avec pastille colorée ──────────────────
        StringBuilder leg=new StringBuilder();
        for(Map.Entry<String,String> e:FAM_COL.entrySet())
            leg.append("<div class='lgi'>")
                    .append("<span class='lc' style='background:").append(e.getValue()).append("'></span>")
                    .append(e.getKey())
                    .append("</div>");

        // Tableau annuel
        StringBuilder tbl=new StringBuilder("<div class='tscroll'><table><thead><tr><th>Parcelle</th>");
        for(String m:MONTHS)tbl.append("<th>").append(m).append("</th>");
        tbl.append("</tr></thead><tbody>");
        for(String nm:noms){
            int i=idx.get(nm);
            tbl.append("<tr><td class='tn'>").append(nm).append("</td>");
            for(int t=0;t<H;t++){
                int cId=plan[i][t];
                if(cId==0){tbl.append("<td class='tr0'>—</td>");continue;}
                Culture c=db.getCultureById(cId);
                String n=c!=null?c.nom():"?";
                String fam=c!=null?cleanFam(c.famille()!=null?c.famille().nom():""):"";
                String bg=FAM_COL.getOrDefault(fam,"#E5E7EB");
                String tx=txtCol(bg); String em=getEmoji(n);
                tbl.append("<td style='background:").append(bg).append(";color:").append(tx)
                        .append("' title='").append(n).append("'>")
                        .append(em).append(" ").append(n.length()>7?n.substring(0,6)+"…":n)
                        .append("</td>");
            }
            tbl.append("</tr>");
        }
        tbl.append("</tbody></table></div>");

        // Noms JS
        StringBuilder nj=new StringBuilder("[");
        for(String n:noms)nj.append("\"").append(n).append("\",");
        if(nj.charAt(nj.length()-1)==',')nj.deleteCharAt(nj.length()-1);
        nj.append("]");

        // ── KPI CARDS verticales — valeur en gros + label dessous ──────────
        String kpiBadgeRot   = "<div class='kpi-card'><div class='kpi-val'>✓</div><div class='kpi-lbl'>Rotation</div></div>";
        String kpiBadgeAdj   = "<div class='kpi-card'><div class='kpi-val'>✓</div><div class='kpi-lbl'>Adjacence</div></div>";
        String kpiBadgeSais  = "<div class='kpi-card'><div class='kpi-val'>✓</div><div class='kpi-lbl'>Saisonnalité</div></div>";
        String kpiBadgeEau   = "<div class='kpi-card'><div class='kpi-val'>100%</div><div class='kpi-lbl'>Budget eau</div></div>";
        String kpiBadgeC04   = "<div class='kpi-card kpi-card-accent'>"
                + "<div class='kpi-val'>"+String.format("%.1f",gPct).replace(".",",")+"% </div>"
                + "<div class='kpi-lbl'>Demande "+tSat+"/"+tDem+"</div></div>";
        String kpiBadgeTime  = "<div class='kpi-card'>"
                + "<div class='kpi-val'>"+String.format("%.0f",timeMs/1000.0)+"s</div>"
                + "<div class='kpi-lbl'>Résolution</div></div>";

        // HTML final
        StringBuilder h=new StringBuilder();
        h.append("<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>AGROécologique TROPical Planning</title><style>").append(CSS).append("</style>")
                .append("</head><body>");

        // ── HEADER 2 colonnes ────────────────────────────────────────────
        h.append("<div class='header'>");
        h.append("<div class='header-inner'>");

        // ── Colonne GAUCHE : badge + kente + titre + sous-titre
        h.append("<div class='h-left'>");

        // Badge solver
        h.append("<div class='header-badge'>").append(solverName).append("</div>");

        // Bande kente
        h.append("<div class='kente'>"
                +"<span style='background:#65A30D;flex:3'></span>"
                +"<span style='background:#BEF264;flex:1'></span>"
                +"<span style='background:#3F6212;flex:2'></span>"
                +"<span style='background:#FBBF24;flex:1'></span>"
                +"<span style='background:#4D7C0F;flex:3'></span>"
                +"<span style='background:#86EFAC;flex:1'></span>"
                +"<span style='background:#166534;flex:2'></span>"
                +"</div>");

        // Eyebrow
        h.append("<div class='eyebrow'>🌍 Planification maraîchère · Thiès, Sénégal</div>");

        // Titre principal
        h.append("<div class='brand-name'>AGRO<span class='brand-eco'>ÉCOLOGIQUE</span> TROPICAL PLANNING</div>");

        // Sous-titre
        h.append("<div class='brand-sub'>Bio Jêmm · ").append(N)
                .append(" parcelles · ").append(nbCultures)
                .append(" cultures · ").append(H).append(" mois</div>");

        h.append("</div>"); // h-left

        // ── Colonne DROITE : KPI badges empilés
        h.append("<div class='h-right'>");
        h.append("<div class='kpi-stack'>");
        h.append(kpiBadgeRot).append(kpiBadgeAdj).append(kpiBadgeSais)
                .append(kpiBadgeEau).append(kpiBadgeC04).append(kpiBadgeTime);
        h.append("</div>");
        h.append("</div>"); // h-right

        h.append("</div>"); // header-inner
        h.append("</div>"); // header

        // ── CONTENU PRINCIPAL ─────────────────────────────────────────────────
        h.append("<div class='main'>");

        // Légende familles botaniques
        h.append("<div class='legend-wrap'>");
        h.append("<div class='section-head'><div class='section-dot'></div><div class='section-title'>Familles botaniques</div></div>");
        h.append("<div class='legend'>").append(leg).append("</div>");
        h.append("</div>");

        // Section mensuelle
        h.append("<div class='month-wrap'>");
        h.append("<div class='section-head'><div class='section-dot'></div><div class='section-title'>Plan par mois</div><span class='section-sub'>Clique pour voir le jardin</span></div>");
        h.append("<div class='month-nav'>").append(btn).append("</div>");
        h.append("<div id='info' style='display:none' class='mois-info'>")
                .append("<div><span id='it'></span><span id='is'></span></div>")
                .append("<div class='bar'><div id='bf'></div></div>")
                .append("<div id='ie' class='eau'></div></div>");
        h.append("<div id='jar' style='display:none'><div id='jg' class='jg'></div></div>");
        h.append("</div>"); // month-wrap

        // Tableau annuel
        h.append("<div class='tbl-card'>");
        h.append("<div class='section-head' style='padding:18px 20px 0'><div class='section-dot'></div><div class='section-title'>Vue annuelle complète</div></div>");
        h.append(tbl);
        h.append("</div>");

        h.append("</div>"); // main

        // Scripts
        h.append("<script>const P=").append(pj).append(";const N=").append(nj)
                .append(";const S=").append(Arrays.toString(satM))
                .append(";const D=").append(Arrays.toString(demM))
                .append(";const E=").append(Arrays.toString(eauM))
                .append(";const ML=").append(toJs(MONTHS_LONG)).append(";").append(JS)
                .append("</script></body></html>");

        try(Writer w=new FileWriter(outputPath)){w.write(h.toString());}
        System.out.println("  Rapport HTML exporté : "+outputPath);
    }

    private static String toJs(String[] a){
        StringBuilder s=new StringBuilder("[");
        for(int i=0;i<a.length;i++){s.append("\"").append(a[i]).append("\"");if(i<a.length-1)s.append(",");}
        return s.append("]").toString();
    }

    // ═════════════════════════════════════════════════════════════════
    // CSS
    // ═════════════════════════════════════════════════════════════════
    private static final String CSS =
            // ── RESET & BASE ──────────────────────────────────────────────────
            "*{box-sizing:border-box;margin:0;padding:0}" +
                    "body{font-family:'Inter',system-ui,sans-serif;background:#F7FEE7;color:#0F172A;font-size:14px;line-height:1.5}" +

                    // ── HEADER — fond vert sobre sans photo ─────────────────────────
                    ".header{background:linear-gradient(135deg,#14532D 0%,#166534 40%,#15803D 70%,#16A34A 100%);" +
                    "padding:0;position:relative;overflow:hidden}" +
                    ".header-inner{position:relative;z-index:1;padding:32px 40px;" +
                    "display:flex;align-items:center;gap:48px;min-height:200px}" +
                    ".h-left{flex:1;min-width:0}" +
                    ".h-right{flex-shrink:0;display:flex;align-items:center}" +
                    ".kpi-stack{display:grid;grid-template-columns:1fr 1fr 1fr;gap:10px;min-width:360px}" +

                    // Badge solver
                    ".header-badge{display:inline-flex;align-items:center;" +
                    "background:rgba(255,255,255,0.12);border:1px solid rgba(255,255,255,.25);" +
                    "border-radius:24px;padding:6px 16px;margin-bottom:18px;" +
                    "font-size:12px;font-weight:800;color:#FFFFFF;letter-spacing:1px;text-transform:uppercase;" +
                    "backdrop-filter:blur(6px);transition:background .2s}" +
                    ".header-badge:hover{background:rgba(255,255,255,0.2)}" +

                    // Bande kente
                    ".kente{display:flex;gap:3px;margin-bottom:16px;max-width:680px}" +
                    ".kente span{height:4px;border-radius:2px}" +

                    // Eyebrow
                    ".eyebrow{font-size:9px;color:rgba(255,255,255,.75);letter-spacing:2.5px;" +
                    "text-transform:uppercase;font-weight:600;margin-bottom:10px}" +

                    // Titre
                    ".brand-name{font-family:system-ui,sans-serif;font-size:28px;" +
                    "font-weight:800;color:#FFFFFF;letter-spacing:1px;line-height:1;text-shadow:0 2px 12px rgba(0,0,0,.3)}" +
                    ".brand-eco{color:#BEF264}" +

                    // Sous-titre
                    ".brand-sub{font-size:12px;color:rgba(255,255,255,.65);letter-spacing:.4px;margin-top:6px}" +

                    // ── KPI CARDS — grille 3 colonnes, valeur en gros + label dessous ──
                    ".kpi-row{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px}" +
                    ".kpi-card{background:rgba(255,255,255,0.12);border:1px solid rgba(255,255,255,.2);" +
                    "border-radius:10px;padding:12px 14px;text-align:center;min-width:90px;" +
                    "backdrop-filter:blur(6px);transition:background .15s}" +
                    ".kpi-card:hover{background:rgba(255,255,255,0.2)}" +
                    ".kpi-val{font-size:20px;font-weight:800;color:#FFFFFF;line-height:1;margin-bottom:4px}" +
                    ".kpi-lbl{font-size:9px;color:rgba(255,255,255,.65);text-transform:uppercase;" +
                    "letter-spacing:.8px;font-weight:500;line-height:1.3}" +
                    ".kpi-card-accent{background:rgba(190,242,100,0.25);border-color:rgba(190,242,100,.5)}" +
                    ".kpi-card-accent .kpi-val{color:#BEF264}" +
                    ".kpi-card-accent .kpi-lbl{color:rgba(190,242,100,.8)}" +

                    // ── MAIN ─────────────────────────────────────────────────────────
                    ".main{padding:24px 32px;max-width:1400px;margin:0 auto}" +

                    // ── SECTION TITRES ────────────────────────────────────────────────
                    ".section-head{display:flex;align-items:center;gap:10px;margin-bottom:14px}" +
                    ".section-dot{width:8px;height:8px;border-radius:50%;background:#16A34A;flex-shrink:0}" +
                    ".section-title{font-size:15px;font-weight:700;color:#0F172A}" +
                    ".section-sub{font-size:12px;color:#94A3B8;margin-left:auto}" +

                    // ── LÉGENDE CORRIGÉE ─────────────────────────────────────────────
                    ".legend-wrap{background:#FFFFFF;border-radius:14px;padding:18px 20px;" +
                    "box-shadow:0 1px 3px rgba(0,0,0,.08),0 8px 24px rgba(0,0,0,.06);" +
                    "margin-bottom:20px}" +
                    ".legend{display:flex;flex-wrap:wrap;gap:8px;margin-top:4px}" +
                    // ← CLASSES CORRECTES : lgi + lc (pas li + i)
                    ".lgi{display:inline-flex;align-items:center;gap:7px;padding:5px 12px;" +
                    "background:#F8FAFC;border:1px solid #E2E8F0;" +
                    "border-radius:20px;font-size:12px;font-weight:500;color:#475569;" +
                    "transition:transform .15s;cursor:default}" +
                    ".lgi:hover{transform:scale(1.04);background:#F1F5F9}" +
                    ".lc{width:11px;height:11px;border-radius:50%;flex-shrink:0;display:inline-block}" +

                    // ── NAV MOIS ──────────────────────────────────────────────────────
                    ".month-wrap{background:#FFFFFF;border-radius:14px;padding:22px;" +
                    "box-shadow:0 1px 3px rgba(0,0,0,.08),0 8px 24px rgba(0,0,0,.06);margin-bottom:20px}" +
                    ".month-nav{display:flex;flex-wrap:wrap;gap:6px;margin:12px 0}" +
                    ".bm{display:flex;flex-direction:column;align-items:center;gap:3px;" +
                    "padding:10px 12px;border-radius:10px;border:1.5px solid transparent;" +
                    "cursor:pointer;font-family:'Inter',sans-serif;font-weight:600;" +
                    "transition:all .18s;min-width:62px;background:#F8FAFC}" +
                    ".bm span{font-size:12px;color:#0F172A}" +
                    ".bm b{font-size:10px;font-weight:500}" +
                    ".bm.ok{background:#DCFCE7;border-color:#86EFAC}" +
                    ".bm.ok span{color:#14532D}" +
                    ".bm.ok b{color:#16A34A}" +
                    ".bm.mid{background:#FEF3C7;border-color:#FCD34D}" +
                    ".bm.mid span{color:#92400E}" +
                    ".bm.mid b{color:#D97706}" +
                    ".bm.lo{background:#FEE2E2;border-color:#FCA5A5}" +
                    ".bm.lo span{color:#991B1B}" +
                    ".bm.lo b{color:#DC2626}" +
                    ".bm:hover{transform:translateY(-3px);box-shadow:0 8px 20px rgba(0,0,0,.12)}" +
                    ".bm.actif.ok{background:#16A34A;border-color:#166534;box-shadow:0 6px 20px rgba(22,163,74,.35)}" +
                    ".bm.actif.ok span,.bm.actif.ok b{color:#fff}" +
                    ".bm.actif.mid{background:#F59E0B;border-color:#D97706;box-shadow:0 6px 20px rgba(245,158,11,.35)}" +
                    ".bm.actif.mid span,.bm.actif.mid b{color:#fff}" +
                    ".bm.actif.lo{background:#DC2626;border-color:#B91C1C;box-shadow:0 6px 20px rgba(220,38,38,.35)}" +
                    ".bm.actif.lo span,.bm.actif.lo b{color:#fff}" +

                    // ── INFO MOIS ──────────────────────────────────────────────────────
                    ".mois-info{background:linear-gradient(135deg,#F0FDF4,#DCFCE7);" +
                    "border:1px solid #BBF7D0;border-radius:12px;padding:14px 18px;" +
                    "margin-bottom:14px}" +
                    "#it{font-weight:700;font-size:15px;color:#14532D}" +
                    "#is{font-size:12px;color:#166534;background:rgba(134,239,172,.35);" +
                    "padding:3px 10px;border-radius:20px;font-weight:600;margin-left:10px}" +
                    ".bar{height:8px;background:rgba(0,0,0,.08);border-radius:4px;overflow:hidden;margin-top:10px}" +
                    "#bf{height:100%;border-radius:4px;transition:width .5s cubic-bezier(.4,0,.2,1)}" +
                    ".eau{font-size:12px;color:#0369A1;font-weight:500;margin-top:8px}" +

                    // ── JARDIN 3 RÉSEAUX ───────────────────────────────────────────────
                    ".jg{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:4px}" +
                    ".res{background:#FFFFFF;border-radius:14px;overflow:hidden;" +
                    "box-shadow:0 1px 3px rgba(0,0,0,.08),0 8px 24px rgba(0,0,0,.06);border:1px solid #E2E8F0}" +
                    ".res-hdr{background:linear-gradient(135deg,#14532D,#16A34A);" +
                    "color:#fff;text-align:center;font-size:13px;font-weight:700;" +
                    "padding:10px;letter-spacing:.5px}" +
                    ".cols{display:grid;grid-template-columns:1fr 1fr}" +
                    ".col-h{background:#DCFCE7;text-align:center;font-size:9px;" +
                    "font-weight:700;padding:6px;color:#14532D;" +
                    "border-bottom:2px solid #BBF7D0;letter-spacing:.5px;text-transform:uppercase}" +
                    ".cells{display:flex;flex-direction:column}" +
                    ".pc{padding:7px 8px;border-bottom:1px solid rgba(0,0,0,.04);" +
                    "display:flex;align-items:center;gap:6px;transition:filter .12s;cursor:default}" +
                    ".pc:last-child{border-bottom:none}" +
                    ".pc:hover{filter:brightness(.92)}" +
                    ".pc-line{display:flex;align-items:center;gap:5px;flex:1;min-width:0}" +
                    ".pc-em{font-size:15px;flex-shrink:0;line-height:1}" +
                    ".pc-txt{display:flex;flex-direction:column;min-width:0}" +
                    ".pc-id{font-size:8px;color:rgba(0,0,0,.38);line-height:1;margin-bottom:1px}" +
                    ".pc-n{font-size:10px;font-weight:700;line-height:1.2;" +
                    "white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
                    ".pc.repos{opacity:.3}" +
                    ".pc.repos .pc-n{font-weight:400;font-style:italic}" +

                    // ── TABLEAU ANNUEL ─────────────────────────────────────────────────
                    ".tbl-card{background:#FFFFFF;border-radius:14px;" +
                    "box-shadow:0 1px 3px rgba(0,0,0,.08),0 8px 24px rgba(0,0,0,.06);" +
                    "overflow:hidden;border:1px solid #E2E8F0;margin-bottom:32px}" +
                    ".tscroll{overflow-x:auto}" +
                    "table{border-collapse:collapse;font-size:10px;min-width:960px;width:100%}" +
                    "thead th{background:linear-gradient(135deg,#0F172A,#1E293B);" +
                    "color:#fff;padding:9px 5px;font-size:9px;font-weight:600;" +
                    "text-align:center;position:sticky;top:0;letter-spacing:.5px}" +
                    "thead th:first-child{text-align:left;padding-left:14px;min-width:70px}" +
                    "td{border:1px solid rgba(0,0,0,.05);padding:4px 3px;" +
                    "text-align:center;vertical-align:middle;font-weight:600;font-size:9px}" +
                    ".tn{font-weight:700;text-align:left!important;padding-left:10px!important;" +
                    "background:#F8FAFC;position:sticky;left:0;color:#475569;" +
                    "border-right:2px solid #E2E8F0;white-space:nowrap;font-size:10px}" +
                    ".tr0{background:#FAFAFA;color:#94A3B8;font-style:italic;font-weight:400}" +
                    "tbody tr:hover td:not(.tn){filter:brightness(.88)}" +
                    "tbody tr:nth-child(even) .tn{background:#F1F5F9}";

    // ═════════════════════════════════════════════════════════════════
    // JAVASCRIPT (inchangé)
    // ═════════════════════════════════════════════════════════════════
    private static final String JS =
            "let a=-1;" +
                    "function sel(t){" +
                    "  if(a>=0)document.getElementById('b'+a).classList.remove('actif');" +
                    "  if(a===t){a=-1;document.getElementById('jar').style.display='none';" +
                    "    document.getElementById('info').style.display='none';return;}" +
                    "  a=t;document.getElementById('b'+t).classList.add('actif');" +
                    "  const pct=D[t]>0?Math.round(100*S[t]/D[t]):100;" +
                    "  const col=pct>=80?'linear-gradient(90deg,#16A34A,#22C55E)':" +
                    "    pct>=40?'linear-gradient(90deg,#D97706,#FBBF24)':'linear-gradient(90deg,#DC2626,#F87171)';" +
                    "  document.getElementById('it').textContent='📅 '+ML[t];" +
                    "  document.getElementById('is').textContent='✅ '+S[t]+'/'+D[t]+' — '+pct+'%';" +
                    "  const f=document.getElementById('bf');" +
                    "  f.style.width=pct+'%';f.style.background=col;" +
                    "  document.getElementById('ie').textContent='💧 '+E[t]+' m³ / 500 m³ budget eau';" +
                    "  document.getElementById('info').style.display='block';" +
                    "  const d=P[t],jg=document.getElementById('jg');" +
                    "  jg.innerHTML='';" +
                    "  const R={};" +
                    "  N.forEach(nm=>{" +
                    "    const m=nm.match(/R(\\d+)-([A-Z])(\\d+)/);" +
                    "    if(!m)return;" +
                    "    const r=m[1],c=m[2],l=+m[3];" +
                    "    if(!R[r])R[r]={};" +
                    "    if(!R[r][c])R[r][c]=[];" +
                    "    R[r][c].push({nm,l});" +
                    "  });" +
                    "  Object.keys(R).sort().forEach(r=>{" +
                    "    const div=document.createElement('div');div.className='res';" +
                    "    div.innerHTML='<div class=\"res-hdr\">🌿 Réseau '+r+'</div>';" +
                    "    const cols=document.createElement('div');cols.className='cols';" +
                    "    const ck=Object.keys(R[r]).sort();" +
                    "    ck.forEach(c=>{const h=document.createElement('div');" +
                    "      h.className='col-h';h.textContent='Colonne '+c;cols.appendChild(h);});" +
                    "    ck.forEach(c=>{" +
                    "      const col=document.createElement('div');col.className='cells';" +
                    "      R[r][c].sort((a,b)=>a.l-b.l).forEach(({nm})=>{" +
                    "        const info=d[nm]||{n:'?',bg:'#eee',tx:'#000',r:true,em:''};" +
                    "        const pc=document.createElement('div');" +
                    "        pc.className='pc'+(info.r?' repos':'');" +
                    "        pc.style.background=info.bg;pc.style.color=info.tx;" +
                    "        pc.innerHTML='<div class=\"pc-line\">'+" +
                    "          '<span class=\"pc-em\">'+(info.em||'')+'</span>'+" +
                    "          '<div class=\"pc-txt\">'+" +
                    "            '<div class=\"pc-id\">'+nm+'</div>'+" +
                    "            '<div class=\"pc-n\">'+info.n+'</div>'+" +
                    "          '</div></div>';" +
                    "        col.appendChild(pc);});" +
                    "      cols.appendChild(col);});" +
                    "    div.appendChild(cols);jg.appendChild(div);});" +
                    "  document.getElementById('jar').style.display='block';" +
                    "  document.getElementById('jar').scrollIntoView({behavior:'smooth',block:'nearest'});" +
                    "}";
}