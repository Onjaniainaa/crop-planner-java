package fr.cril.cropplanner.export;

import fr.cril.cropplanner.ingestion.AgronomicDatabase;
import fr.cril.cropplanner.model.*;
import fr.cril.cropplanner.transformation.GardenTopology;
import fr.cril.cropplanner.validation.PlanVerifier;

import java.io.*;
import java.util.*;

/**
 * Génère un rapport HTML interactif montrant le plan de culture
 * et les résultats de vérification.
 */
public class HTMLExporter {

    private static final String[] MONTHS = {
        "Jan","Fév","Mar","Avr","Mai","Jun","Jul","Aoû","Sep","Oct","Nov","Déc"
    };

    private static final Map<String, String> FAMILY_COLORS = Map.ofEntries(
        Map.entry("Solanacées", "#FF6B6B"),
        Map.entry("Alliacées", "#C084FC"),
        Map.entry("Brassicacées", "#4ADE80"),
        Map.entry("Malvacées", "#FB923C"),
        Map.entry("Apiacées", "#FBBF24"),
        Map.entry("Astéracées", "#A3E635"),
        Map.entry("Cucurbitacées", "#22D3EE"),
        Map.entry("Fabacées", "#34D399"),
        Map.entry("Chénopodiacées", "#F472B6"),
        Map.entry("Lamiacées", "#94A3B8"),
        Map.entry("Convolvulacées", "#E879F9"),
        Map.entry("Euphorbiacées", "#A78BFA")
    );

    /**
     * Exporte le plan en HTML.
     * @param plan plan[carré][période] = cultureId
     * @param report rapport de vérification
     * @param outputPath chemin du fichier HTML
     */
    public static void export(int[][] plan, AgronomicDatabase db,
                              GardenTopology topo, int nbPeriodes,
                              PlanVerifier.Report report,
                              String solverName, long timeMs,
                              String outputPath) throws IOException {
        StringBuilder html = new StringBuilder();

        html.append("""
            <!DOCTYPE html><html lang="fr"><head><meta charset="UTF-8">
            <title>Plan de culture — Bio Jêmm</title>
            <style>
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:system-ui,sans-serif;background:#fafafa;color:#1e293b;padding:24px}
            h1{font-size:22px;margin-bottom:4px} h2{font-size:17px;margin:20px 0 8px}
            .sub{color:#64748b;font-size:13px;margin-bottom:16px}
            .metrics{display:flex;gap:10px;flex-wrap:wrap;margin:12px 0}
            .m{background:#fff;border:1px solid #e2e8f0;border-radius:8px;padding:12px 16px;text-align:center;min-width:120px}
            .m b{display:block;font-size:22px} .m span{font-size:11px;color:#64748b}
            .grid-wrap{overflow-x:auto;margin:12px 0}
            table{border-collapse:collapse;font-size:12px;width:100%}
            th{background:#334155;color:#fff;padding:6px 4px;font-size:10px;font-weight:500}
            td{border:1px solid #e2e8f0;padding:4px;text-align:center;font-size:10px;min-width:60px}
            .repos{background:#f1f5f9;color:#94a3b8;font-style:italic}
            .err{background:#fef2f2;color:#dc2626} .warn{background:#fffbeb;color:#d97706}
            ul{padding-left:20px;font-size:13px;line-height:1.8}
            .legend{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0;font-size:11px}
            .lg{display:flex;align-items:center;gap:4px}
            .lc{width:12px;height:12px;border-radius:2px;border:1px solid rgba(0,0,0,.1)}
            </style></head><body>
            """);

        html.append("<h1>Plan de culture optimisé — Bio Jêmm, Thiès</h1>");
        html.append("<p class='sub'>Solveur : ").append(solverName)
            .append(" — Temps : ").append(timeMs).append(" ms</p>");

        // ── Métriques ──
        html.append("<div class='metrics'>");
        for (var e : report.getScores().entrySet()) {
            html.append("<div class='m'><b>").append(String.format("%.1f", e.getValue()))
                .append("</b><span>").append(e.getKey()).append("</span></div>");
        }
        html.append("<div class='m'><b>").append(report.getNbErrors())
            .append("</b><span>Erreurs</span></div>");
        html.append("<div class='m'><b>").append(report.getNbWarnings())
            .append("</b><span>Avertissements</span></div>");
        html.append("</div>");

        // ── Légende familles ──
        html.append("<div class='legend'>");
        FAMILY_COLORS.forEach((fam, color) ->
            html.append("<div class='lg'><div class='lc' style='background:").append(color)
                .append("'></div>").append(fam).append("</div>"));
        html.append("</div>");

        // ── Grille du plan ──
        html.append("<h2>Grille du potager par période</h2><div class='grid-wrap'><table><tr><th>Carré</th>");
        for (int t = 0; t < nbPeriodes; t++) {
            html.append("<th>").append(MONTHS[t % 12]).append("</th>");
        }
        html.append("</tr>");

        for (int i = 0; i < plan.length; i++) {
            if (!topo.isDisponible(i)) continue;
            html.append("<tr><td><b>").append(topo.nomCarre(i)).append("</b></td>");
            for (int t = 0; t < nbPeriodes; t++) {
                int cId = plan[i][t];
                if (cId == 0) {
                    html.append("<td class='repos'>Repos</td>");
                } else {
                    Culture c = db.getCultureById(cId);
                    String color = c != null && c.famille() != null ?
                        FAMILY_COLORS.getOrDefault(c.famille().nom(), "#e5e7eb") : "#e5e7eb";
                    String name = c != null ? c.nom() : "?";
                    html.append("<td style='background:").append(color)
                        .append(";font-weight:500'>").append(name).append("</td>");
                }
            }
            html.append("</tr>");
        }
        html.append("</table></div>");

        // ── Violations ──
        if (!report.getErrors().isEmpty()) {
            html.append("<h2>Erreurs</h2><ul>");
            for (String e : report.getErrors())
                html.append("<li class='err'>").append(e).append("</li>");
            html.append("</ul>");
        }
        if (!report.getWarnings().isEmpty()) {
            html.append("<h2>Avertissements</h2><ul>");
            for (String w : report.getWarnings())
                html.append("<li class='warn'>").append(w).append("</li>");
            html.append("</ul>");
        }

        html.append("</body></html>");

        try (Writer writer = new FileWriter(outputPath)) {
            writer.write(html.toString());
        }
        System.out.println("  Rapport HTML exporté : " + outputPath);
    }
}
