package fr.cril.cropplanner.ingestion;

import fr.cril.cropplanner.model.Culture;
import fr.cril.cropplanner.model.FamilleBotanique;
import fr.cril.cropplanner.model.StatutMois;
import fr.cril.cropplanner.model.TypeAssociation;

import java.util.*;

/**
 * Version consolidée de la base de données agronomique.
 * Supporte la résolution itérative et les contraintes Choco/SAT.
 */
public class AgronomicDatabase {

    private final List<Culture> cultures = new ArrayList<>();
    private final Map<String, Culture> culturesByName = new HashMap<>();
    private final Map<Integer, Culture> culturesById = new HashMap<>();
    private final List<FamilleBotanique> familles = new ArrayList<>();
    private final Map<String, TypeAssociation> compatMatrix = new HashMap<>();
    private final Map<String, StatutMois> calendrier = new HashMap<>();

    private int[][] demande;
    private double multiplicateurEau = 1.0;

    // --- ACCÈS AUX DONNÉES ---

    public void addCulture(Culture c) {
        if (c == null) return;
        cultures.add(c);
        culturesByName.put(c.nom().toLowerCase().trim(), c);
        culturesById.put(c.id(), c);
    }

    public void addFamille(FamilleBotanique f) {
        if (f != null) familles.add(f);
    }

    public List<Culture> getAllCultures() {
        return Collections.unmodifiableList(cultures);
    }

    public List<FamilleBotanique> getAllFamilles() {
        return Collections.unmodifiableList(familles);
    }

    public Culture getCultureById(int id) {
        return culturesById.getOrDefault(id, Culture.REPOS);
    }

    public Culture getCultureByName(String name) {
        if (name == null) return null;
        return culturesByName.get(name.toLowerCase().trim());
    }

    public int getNbCultures() {
        return cultures.size();
    }

    // --- LOGIQUE MÉTIER & CALENDRIER ---

    public boolean isDisponible(int cultureId, int mois) {
        if (cultureId <= 0) return true;
        StatutMois s = calendrier.get(cultureId + ":" + (mois % 12));
        return s == null || s.isPossible();
    }

    public int[] getCulturesDisponibles(int mois) {
        List<Integer> ids = new ArrayList<>();
        ids.add(0);
        for (Culture c : cultures) {
            if (isDisponible(c.id(), mois)) ids.add(c.id());
        }
        return ids.stream().distinct().mapToInt(Integer::intValue).toArray();
    }

    public int getDemande(int cultureId, int mois) {
        if (demande == null || cultureId <= 0 || cultureId > demande.length) return 0;
        return demande[cultureId - 1][mois % 12];
    }

    // --- ASSOCIATIONS & COMPATIBILITÉS (CORRECTIONS ICI) ---

    public TypeAssociation getCompatibilite(Culture c1, Culture c2) {
        if (c1.isRepos() || c2.isRepos()) return TypeAssociation.NEUTRE;
        return compatMatrix.getOrDefault(key(c1.nom(), c2.nom()), TypeAssociation.NEUTRE);
    }

    /** Retourne les paires de cultures qui s'aident (Compagnonnage). */
    public List<int[]> getFavorablePairs() {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < cultures.size(); i++) {
            for (int j = i + 1; j < cultures.size(); j++) {
                Culture c1 = cultures.get(i);
                Culture c2 = cultures.get(j);
                if (getCompatibilite(c1, c2) == TypeAssociation.FAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    /** Retourne les paires interdites (Incompatibilités). */
    public List<int[]> getForbiddenPairs() {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < cultures.size(); i++) {
            for (int j = i + 1; j < cultures.size(); j++) {
                Culture c1 = cultures.get(i);
                Culture c2 = cultures.get(j);
                if (getCompatibilite(c1, c2) == TypeAssociation.DEFAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    /** Retourne toutes les paires autorisées (Neutres + Favorables). */
    public List<int[]> getAllowedPairs() {
        List<int[]> pairs = new ArrayList<>();
        List<Culture> all = new ArrayList<>(cultures);
        if (!culturesById.containsKey(0)) all.add(Culture.REPOS);

        for (Culture c1 : all) {
            for (Culture c2 : all) {
                if (getCompatibilite(c1, c2) != TypeAssociation.DEFAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    // --- GESTION DES FAMILLES & EAU ---

    public Map<String, List<Integer>> getCulturesByFamille() {
        Map<String, List<Integer>> map = new HashMap<>();
        for (Culture c : cultures) {
            if (c.famille() != null) {
                map.computeIfAbsent(c.famille().id(), k -> new ArrayList<>()).add(c.id());
            }
        }
        return map;
    }

    public int getRetourMinByFamille(String familleId) {
        return familles.stream()
                .filter(f -> f.id().equals(familleId))
                .map(FamilleBotanique::retourMinPeriodes)
                .findFirst().orElse(1);
    }

    public int[] getEauParCultureArray() {
        int maxId = culturesById.keySet().stream().max(Integer::compare).orElse(0);
        int[] eauArray = new int[maxId + 1];
        for (Culture c : cultures) {
            eauArray[c.id()] = (int) (c.besoinEau() * multiplicateurEau);
        }
        return eauArray;
    }

    // --- MÉTHODES DE CONFIGURATION ---

    public void reduireDemande(double facteur) {
        if (this.demande == null) return;
        for (int i = 0; i < demande.length; i++) {
            for (int j = 0; j < demande[i].length; j++) {
                demande[i][j] = (int) Math.ceil(demande[i][j] * facteur);
            }
        }
    }

    public void setMultiplicateurEau(double m) { this.multiplicateurEau = m; }
    public void setCompatibilite(String c1, String c2, TypeAssociation type) {
        compatMatrix.put(key(c1, c2), type);
        compatMatrix.put(key(c2, c1), type);
    }
    public void setDemande(int[][] d) { this.demande = d; }
    public void setCalendrier(int id, int m, StatutMois s) { calendrier.put(id + ":" + m, s); }

    private String key(String a, String b) {
        return a.toLowerCase().trim() + "|" + b.toLowerCase().trim();
    }
}