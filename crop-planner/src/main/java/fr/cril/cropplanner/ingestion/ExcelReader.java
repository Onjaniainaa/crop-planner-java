package fr.cril.cropplanner.ingestion;

import fr.cril.cropplanner.model.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.*;

/**
 * Lecteur des fichiers Excel d'entrée.
 * Charge base_agronomique_thies.xlsx et analyse_consommation_P2_pipeline.xlsx.
 */
public class ExcelReader {

    /**
     * Charge la base agronomique depuis le fichier Excel.
     * Lit les feuilles : Catalogue cultures, Associations, Familles botaniques.
     */
    public static AgronomicDatabase loadAgronomicDB(String path) throws IOException {
        AgronomicDatabase db = new AgronomicDatabase();

        try (Workbook wb = new XSSFWorkbook(new FileInputStream(path))) {
            loadFamilles(wb.getSheet("Familles botaniques"), db);
            loadCultures(wb.getSheet("Catalogue cultures"), db);
            loadAssociations(wb.getSheet("Associations"), db);
        }
        return db;
    }

    /**
     * Charge la matrice Demande(c,t) depuis le fichier d'analyse P2.
     */
    public static int[][] loadDemande(String path, AgronomicDatabase db) throws IOException {
        try (Workbook wb = new XSSFWorkbook(new FileInputStream(path))) {
            Sheet ws = wb.getSheet("Contrainte C04");
            if (ws == null) {
                System.err.println("Feuille 'Contrainte C04' non trouvée");
                return new int[db.getNbCultures()][12];
            }
            return loadDemandeSheet(ws, db);
        }
    }

    // ── Familles botaniques ──
    private static void loadFamilles(Sheet ws, AgronomicDatabase db) {
        if (ws == null) return;
        for (int r = 3; r <= ws.getLastRowNum(); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            String nom = str(row, 0);
            String id = str(row, 1);
            if (nom == null || nom.isEmpty()) continue;

            // Parse temps de retour : "6 (≈3-4 ans)" -> 6
            String retourStr = str(row, 3);
            int retour = 4; // défaut
            if (retourStr != null) {
                try {
                    retour = Integer.parseInt(retourStr.replaceAll("[^0-9].*", "").trim());
                } catch (NumberFormatException ignored) {}
            }
            db.addFamille(new FamilleBotanique(id, nom, retour));
        }
    }

    // ── Catalogue cultures ──
    private static void loadCultures(Sheet ws, AgronomicDatabase db) {
        if (ws == null) return;
        int id = 1;
        for (int r = 3; r <= ws.getLastRowNum(); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            String nom = str(row, 0);
            if (nom == null || nom.isEmpty()) continue;

            String nomLocal = str(row, 1);
            String familleStr = str(row, 2);
            String typeStr = str(row, 3);
            String cycleStr = str(row, 4);
            String eauStr = str(row, 7);
            String espacement = str(row, 9);

            // Parse famille
            FamilleBotanique famille = null;
            if (familleStr != null) {
                String fKey = familleStr.split("\\(")[0].trim().toLowerCase();
                for (FamilleBotanique f : db.getAllFamilles()) {
                    if (f.nom().toLowerCase().startsWith(fKey)
                            || fKey.startsWith(f.nom().toLowerCase().substring(0,
                            Math.min(6, f.nom().length())))) {
                        famille = f;
                        break;
                    }
                }
            }

            // Parse type
            TypeLegume type = parseType(typeStr);

            // Parse cycle : "90-120" -> min=90, max=120
            int[] cycle = parseRange(cycleStr);

            // Parse eau : "5-8" -> min=5, max=8
            double[] eau = parseRangeDouble(eauStr);

            Culture c = new Culture(id++, nom,
                    nomLocal != null ? nomLocal : "—",
                    famille, type,
                    cycle[0], cycle[1],
                    eau[0], eau[1],
                    espacement != null ? espacement : "—"
            );
            db.addCulture(c);

            // C03 — Calendrier saisonnalité depuis colonne 6 "Hivernage (Juil-Sept)"
            // Cultures marquées "Non", "Difficile" ou "Monte" sont interdites Jul/Aoû/Sep
            String hivernage = str(row, 6);
            boolean okHivernage = (hivernage == null)
                    || (!hivernage.toLowerCase().contains("non")
                    && !hivernage.toLowerCase().contains("difficile")
                    && !hivernage.toLowerCase().contains("monte"));
            for (int m = 0; m < 12; m++) {
                boolean moisHiv = (m == 6 || m == 7 || m == 8);
                StatutMois statut = (!okHivernage && moisHiv)
                        ? StatutMois.IMPOSSIBLE : StatutMois.SEMIS;
                db.setCalendrier(c.id(), m, statut);
            }
        }
    }

    //  Associations
    private static void loadAssociations(Sheet ws, AgronomicDatabase db) {
        if (ws == null) return;

        Row headerRow = ws.getRow(2);
        if (headerRow == null) return;

        List<String> colNames = new ArrayList<>();
        for (int c = 1; c <= headerRow.getLastCellNum(); c++) {
            String name = str(headerRow, c);
            colNames.add(name);
        }

        // Lire lignes
        for (int r = 3; r <= ws.getLastRowNum(); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            String c1 = str(row, 0);
            if (c1 == null || c1.isEmpty()) continue;

            for (int c = 1; c < colNames.size(); c++) {
                String c2 = colNames.get(c);
                if (c2 == null) continue;
                String val = str(row, c);
                if (val != null) val = val.trim()
                        .replace('−', '-').replace('–', '-').replace('—', '-');
                TypeAssociation assoc = TypeAssociation.fromSymbol(val);
                if (assoc != TypeAssociation.NEUTRE) {
                    db.setCompatibilite(c1, c2, assoc);
                }
            }
        }
    }

    // DEMANDE
    private static int[][] loadDemandeSheet(Sheet ws, AgronomicDatabase db) {
        int nbCultures = db.getNbCultures();
        int[][] demande = new int[nbCultures][12];

        for (int r = 4; r <= ws.getLastRowNum(); r++) {
            Row row = ws.getRow(r);
            if (row == null) continue;
            String name = str(row, 1);
            if (name == null || name.isEmpty()) continue;


            Culture c = db.getCultureByName(name);
            if (c == null) {
                for (Culture cx : db.getAllCultures()) {
                    if (cx.nom().toLowerCase().contains(name.toLowerCase().substring(0,
                            Math.min(4, name.length())))) {
                        c = cx;
                        break;
                    }
                }
            }
            if (c == null) continue;

            for (int m = 0; m < 12; m++) {
                Cell cell = row.getCell(2 + m);
                if (cell != null && cell.getCellType() == CellType.NUMERIC) {
                    demande[c.id() - 1][m] = (int) cell.getNumericCellValue();
                }
            }
        }
        return demande;
    }

    // ── Utilitaires ──
    private static String str(Row row, int col) {
        if (row == null) return null;
        Cell cell = row.getCell(col);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
            default -> null;
        };
    }

    private static TypeLegume parseType(String s) {
        if (s == null) return TypeLegume.FRUIT;
        return switch (s.toLowerCase().split("/")[0].trim()) {
            case "fruit" -> TypeLegume.FRUIT;
            case "racine" -> TypeLegume.RACINE;
            case "feuille" -> TypeLegume.FEUILLE;
            case "graine" -> TypeLegume.GRAINE;
            case "tubercule" -> TypeLegume.TUBERCULE;
            case "vivace" -> TypeLegume.VIVACE;
            default -> {
                if (s.toLowerCase().contains("arom")) yield TypeLegume.AROMATE;
                yield TypeLegume.FRUIT;
            }
        };
    }

    private static int[] parseRange(String s) {
        if (s == null) return new int[]{60, 90};
        try {
            if (s.contains("-")) {
                String[] parts = s.split("-");
                return new int[]{
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim())
                };
            }
            int v = Integer.parseInt(s.replaceAll("[^0-9]", "").trim());
            return new int[]{v, v};
        } catch (NumberFormatException e) {
            return new int[]{60, 90};
        }
    }

    private static double[] parseRangeDouble(String s) {
        if (s == null) return new double[]{3, 5};
        try {
            if (s.contains("-")) {
                String[] parts = s.split("-");
                return new double[]{
                        Double.parseDouble(parts[0].trim()),
                        Double.parseDouble(parts[1].trim())
                };
            }
            double v = Double.parseDouble(s.replaceAll("[^0-9.]", "").trim());
            return new double[]{v, v};
        } catch (NumberFormatException e) {
            return new double[]{3, 5};
        }
    }
}