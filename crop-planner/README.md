# CropPlanner — Bio Jêmm, Thiès, Sénégal

Planification spatio-temporelle de cultures maraîchères pour l'autonomie
alimentaire du foyer d'accueil de l'Association pour le Sourire d'un Enfant.

## Architecture

```
src/main/java/fr/cril/cropplanner/
├── model/
│   ├── Culture.java              Record : culture maraîchère
│   ├── FamilleBotanique.java     Record : famille botanique
│   ├── TypeLegume.java           Enum : FRUIT, RACINE, FEUILLE, ...
│   ├── TypeAssociation.java      Enum : FAVORABLE, DEFAVORABLE, NEUTRE
│   ├── StatutMois.java           Enum : SEMIS, CROISSANCE, RECOLTE, ...
│   └── AgronomicDatabase.java    Base de connaissances (catalogue + compat + demande)
├── ingestion/
│   └── ExcelReader.java          Lecture des fichiers Excel (Apache POI)
├── transformation/
│   └── GardenTopology.java       Grille du potager + graphe d'adjacence
├── solver/
│   ├── cp/
│   │   └── ChocoModel.java       Modèle CP (Choco Solver) — C01 à C07
│   └── sat/
│       └── SAT4JModel.java       Modèle MaxSAT (SAT4J) — encodage booléen
├── validation/
│   └── PlanVerifier.java         Vérification indépendante des solutions
├── export/
│   └── HTMLExporter.java         Génération du rapport HTML interactif
└── Main.java                     Pipeline P1→P6 complet
```

## Fichiers d'entrée

Placer dans `data/` :
- `base_agronomique_thies.xlsx` — 22 cultures, 134 associations, calendrier
- `analyse_consommation_P2_pipeline.xlsx` — matrice Demande(c,t)
- `biojemm_plan_culture.xlsx` — plans 2025/2026 (validation)

## Prérequis

- Java 17+
- Maven 3.8+

## Compilation

```bash
mvn clean compile
```

## Exécution

```bash
# Solveur CP (Choco) — par défaut
mvn exec:java -Dexec.mainClass="fr.cril.cropplanner.Main" \
  -Dexec.args="--base data/base_agronomique_thies.xlsx \
               --conso data/analyse_consommation_P2_pipeline.xlsx \
               --solver cp --time 300"

# Solveur MaxSAT (SAT4J)
mvn exec:java -Dexec.mainClass="fr.cril.cropplanner.Main" \
  -Dexec.args="--solver sat --time 300"

# Les deux solveurs (comparaison)
mvn exec:java -Dexec.mainClass="fr.cril.cropplanner.Main" \
  -Dexec.args="--solver both --time 300"
```

## Sortie

- `output/plan_cp_domwdeg.html` — plan CP avec rapport HTML
- `output/plan_sat.html` — plan MaxSAT avec rapport HTML
- Console : plan en mode texte + métriques + rapport de vérification

## Pipeline

```
P1: Ingestion      Excel → AgronomicDatabase + GardenTopology
P2: Transformation Consommation → Demande(c,t)
P3: Modélisation   Variables X[i][t] + Contraintes C01-C07
P4: Résolution     Choco / SAT4J (timeout configurable)
P5: Validation     PlanVerifier (rotation, adjacence, demande)
P6: Visualisation  HTMLExporter (grille colorée + rapport)
```

## Contraintes implémentées

| ID  | Contrainte         | CP (Choco)            | SAT (SAT4J)            |
|-----|--------------------|-----------------------|------------------------|
| C01 | Rotation familles  | ifThen + arithm       | Clauses binaires       |
| C02 | Adjacence          | table (tuples)        | Clauses binaires       |
| C03 | Saisonnalité       | Domaine restreint     | Clauses unitaires ¬    |
| C04 | Demande            | count                 | AtLeast (ALO)          |
| C07 | Objectif (assoc+)  | Réification + sum     | Clauses souples WPMS   |
