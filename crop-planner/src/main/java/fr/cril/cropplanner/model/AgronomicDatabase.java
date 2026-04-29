package fr.cril.cropplanner.model;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Base de connaissances agronomiques.
 * Contient le catalogue des cultures, la matrice de compatibilité,
 * le calendrier de saisonnalité, et les règles de rotation.
 * Chargée depuis base_agronomique_thies.xlsx par ExcelReader.
 */
public class AgronomicDatabase {

    private final List<Culture> cultures = new ArrayList<>();
    private final Map<String, Culture> culturesByName = new HashMap<>();
    private final Map<Integer, Culture> culturesById = new HashMap<>();
    private final List<FamilleBotanique> familles = new ArrayList<>();
    private final Map<String, FamilleBotanique> famillesByName = new HashMap<>();

    // Matrice de compatibilité : (nomC1, nomC2) -> TypeAssociation
    private final Map<String, TypeAssociation> compatMatrix = new HashMap<>();

    // Calendrier saisonnalité : (cultureId, mois 0-11) -> StatutMois
    private final Map<String, StatutMois> calendrier = new HashMap<>();

    // Demande alimentaire : (cultureId, mois 0-11) -> nb carrés minimum
    private int[][] demande;

    // ── Construction ──

    public void addCulture(Culture c) {
        cultures.add(c);
        culturesByName.put(c.nom().toLowerCase(), c);
        culturesById.put(c.id(), c);
    }

    public void addFamille(FamilleBotanique f) {
        familles.add(f);
        famillesByName.put(f.nom().toLowerCase(), f);
    }

    public void setCompatibilite(String c1, String c2, TypeAssociation type) {
        compatMatrix.put(key(c1, c2), type);
    }

    public void setCalendrier(int cultureId, int mois, StatutMois statut) {
        calendrier.put(cultureId + ":" + mois, statut);
    }

    public void setDemande(int[][] demande) {
        this.demande = demande;
    }

    // ── Requêtes ──

    public List<Culture> getAllCultures() {
        return Collections.unmodifiableList(cultures);
    }

    public Culture getCultureById(int id) {
        return culturesById.getOrDefault(id, Culture.REPOS);
    }

    public Culture getCultureByName(String name) {
        return culturesByName.get(name.toLowerCase());
    }

    public int getNbCultures() { return cultures.size(); }

    public List<FamilleBotanique> getAllFamilles() {
        return Collections.unmodifiableList(familles);
    }

    /**
     * Retourne la compatibilité entre deux cultures.
     * Recherche par nom normalisé.
     */
    public TypeAssociation getCompatibilite(Culture c1, Culture c2) {
        if (c1.isRepos() || c2.isRepos()) return TypeAssociation.NEUTRE;
        TypeAssociation r = compatMatrix.get(key(c1.nom(), c2.nom()));
        return r != null ? r : TypeAssociation.NEUTRE;
    }

    public TypeAssociation getCompatibilite(String name1, String name2) {
        TypeAssociation r = compatMatrix.get(key(name1, name2));
        return r != null ? r : TypeAssociation.NEUTRE;
    }

    /**
     * Retourne les IDs de cultures possibles à la période t.
     * Inclut toujours 0 (repos).
     */
    public int[] getCulturesDisponibles(int mois) {
        List<Integer> ids = new ArrayList<>();
        ids.add(0); // repos toujours possible
        for (Culture c : cultures) {
            StatutMois s = calendrier.get(c.id() + ":" + mois);
            if (s == null || s.isPossible()) {
                ids.add(c.id());
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Retourne la demande en nombre de carrés pour la culture c au mois t.
     */
    public int getDemande(int cultureId, int mois) {
        if (demande == null || cultureId <= 0 || cultureId > demande.length)
            return 0;
        return demande[cultureId - 1][mois];
    }

    /**
     * Retourne toutes les paires (c1Id, c2Id) autorisées
     * (i.e. non défavorables). Utilisé pour la contrainte table CP.
     */
    public List<int[]> getAllowedPairs() {
        List<int[]> pairs = new ArrayList<>();
        // Repos avec tout
        for (Culture c : cultures) {
            pairs.add(new int[]{0, c.id()});
            pairs.add(new int[]{c.id(), 0});
        }
        pairs.add(new int[]{0, 0});
        // Cultures entre elles
        for (Culture c1 : cultures) {
            for (Culture c2 : cultures) {
                if (getCompatibilite(c1, c2) != TypeAssociation.DEFAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    /**
     * Retourne les paires (c1Id, c2Id) interdites (défavorables).
     */
    public List<int[]> getForbiddenPairs() {
        List<int[]> pairs = new ArrayList<>();
        for (Culture c1 : cultures) {
            for (Culture c2 : cultures) {
                if (getCompatibilite(c1, c2) == TypeAssociation.DEFAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    /**
     * Retourne les paires favorables avec leurs IDs.
     */
    public List<int[]> getFavorablePairs() {
        List<int[]> pairs = new ArrayList<>();
        for (Culture c1 : cultures) {
            for (Culture c2 : cultures) {
                if (c1.id() < c2.id()
                    && getCompatibilite(c1, c2) == TypeAssociation.FAVORABLE) {
                    pairs.add(new int[]{c1.id(), c2.id()});
                }
            }
        }
        return pairs;
    }

    /**
     * Tableau des besoins en eau par culture (index = cultureId).
     * L'index 0 correspond au repos (eau = 0).
     */
    public int[] getEauParCulture() {
        int max = cultures.stream().mapToInt(Culture::id).max().orElse(0);
        int[] eau = new int[max + 1];
        for (Culture c : cultures) {
            eau[c.id()] = (int) Math.round(c.besoinEauMoyen() * 10); // décilitres
        }
        return eau;
    }

    /**
     * Construit la map familleId -> liste de cultureIds de cette famille.
     */
    public Map<String, List<Integer>> getCulturesByFamille() {
        Map<String, List<Integer>> map = new HashMap<>();
        for (Culture c : cultures) {
            if (c.famille() != null) {
                map.computeIfAbsent(c.famille().id(), k -> new ArrayList<>())
                   .add(c.id());
            }
        }
        return map;
    }

    /** Affiche un résumé. */
    public void printSummary() {
        System.out.println("=== Base agronomique ===");
        System.out.println("  Cultures: " + cultures.size());
        System.out.println("  Familles: " + familles.size());
        System.out.println("  Associations: " + compatMatrix.size());
        System.out.println("  Paires interdites: " + getForbiddenPairs().size());
        System.out.println("  Paires favorables: " + getFavorablePairs().size());
    }

    private String key(String a, String b) {
        return a.toLowerCase().trim() + "|" + b.toLowerCase().trim();
    }
}
