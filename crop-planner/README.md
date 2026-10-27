# 🌱 CropPlanner — Planification intelligente de cultures maraîchères

**Une approche par Programmation par Contraintes et SAT en contexte agroécologique tropical**

CropPlanner est un projet de planification agricole visant à organiser les cultures maraîchères dans différentes parcelles, au fil des mois, en tenant compte de contraintes agronomiques et de préférences de planification.

Le projet explore deux approches de résolution : la **Programmation par Contraintes (CP)** avec Choco Solver et la **satisfaisabilité booléenne (SAT)** avec SAT4J.

L'objectif est de rechercher des plans de culture cohérents avec les données agronomiques, les calendriers de disponibilité, les besoins de production et les règles de voisinage entre cultures.

## 🎯 Objectifs du projet

- Modéliser un problème de planification de cultures maraîchères.
- Organiser les cultures sur plusieurs parcelles et périodes.
- Prendre en compte les caractéristiques agronomiques des cultures.
- Respecter les contraintes de disponibilité et de durée des cycles de culture.
- Éviter le voisinage entre certaines cultures incompatibles.
- Favoriser les associations bénéfiques entre cultures.
- Intégrer les besoins mensuels en cultures dans la planification.
- Comparer deux approches de résolution : Choco Solver et SAT4J.

## 🧩 Problématique

Comment construire un plan de culture sur plusieurs parcelles et plusieurs mois tout en respectant les contraintes agronomiques et en recherchant une occupation pertinente du terrain ?

Pour répondre à cette problématique, le projet représente le jardin comme un ensemble de parcelles et le calendrier comme une succession de périodes.

Pour chaque parcelle et chaque période, le modèle détermine une culture ou une période de repos.

La planification doit prendre en compte plusieurs éléments :
- La disponibilité des parcelles.
- Le calendrier de culture.
- La durée moyenne des cycles de croissance.
- Les besoins en cultures selon les mois.
- Les incompatibilités entre cultures voisines.
- Les associations favorables entre cultures.
- L'occupation du sol.

## 🏗️ Architecture générale

Le projet s'appuie sur plusieurs composants.

| Composant | Rôle |
|---|---|
| `AgronomicDatabase` | Stocke les informations agronomiques relatives aux cultures, aux familles botaniques et aux associations. |
| `Culture` | Représente une culture et ses caractéristiques agronomiques. |
| `FamilleBotanique` | Représente une famille botanique et certaines informations associées. |
| `GardenTopology` | Représente la structure du jardin, les parcelles disponibles et les relations de voisinage. |
| `ChocoModel` | Modélise le problème de planification avec Choco Solver. |
| `SAT4JModel` | Représente le problème sous forme de variables et de contraintes logiques SAT. |
| `SolveResult` | Regroupe le plan obtenu, le temps mesuré et le statut de résolution. |

Cette architecture sépare les données agronomiques, la structure du terrain et les mécanismes de résolution.

## ⚙️ Fonctionnalités et contraintes

### 1. Gestion des données agronomiques

Les données sont chargées depuis un classeur Excel à l'aide d'Apache POI.

Les feuilles utilisées dans le code sont notamment :

- `Familles botaniques` : informations relatives aux familles botaniques.
- `Catalogue cultures` : caractéristiques des cultures, telles que leur famille, leur type, leur durée de cycle et leurs besoins en eau.
- `Associations` : relations favorables ou défavorables entre cultures.
- `Contrainte C04` : données de demande mensuelle en cultures.

Le programme transforme ces informations en objets Java afin de les exploiter dans les modèles de résolution.

### 2. Gestion des parcelles et des périodes

Le problème est représenté à l'aide de deux dimensions principales :

- Les parcelles du jardin.
- Les périodes de planification.

Chaque décision de culture est associée à une parcelle et à une période donnée.

Le repos est également représenté comme une option possible dans le modèle.

### 3. Contraintes de disponibilité

Une culture ne peut être sélectionnée que si elle est disponible pendant la période considérée, selon le calendrier agronomique chargé.

### 4. Contraintes de demande

Le modèle prend en compte les besoins mensuels en cultures.

Dans l'implémentation Choco présentée, un facteur d'ajustement permet de modifier les quantités demandées afin d'explorer différents niveaux de contrainte.

### 5. Contraintes de durée des cycles

La durée moyenne du cycle de culture est convertie en mois afin de représenter la continuité de la culture sur plusieurs périodes.

Cette contrainte vise à éviter une planification qui interromprait trop tôt un cycle de croissance.

### 6. Incompatibilités entre cultures voisines

Certaines cultures ne doivent pas être placées côte à côte.

Le modèle identifie les paires incompatibles et ajoute des contraintes pour empêcher leur présence simultanée sur deux parcelles voisines pendant une même période.

### 7. Associations favorables

Certaines associations entre cultures sont considérées comme préférables.

Dans le modèle SAT4J présenté, des contraintes souples favorisent la présence de cultures compatibles sur des parcelles voisines.

Ces préférences ne sont pas nécessairement obligatoires : elles peuvent être prises en compte dans l'optimisation sans rendre automatiquement une solution invalide si elles ne sont pas satisfaites.

### 8. Optimisation de l'occupation du sol

Dans le modèle Choco présenté, l'objectif est de maximiser le nombre de couples parcelle-période occupés par une culture plutôt que laissés au repos.

Cet objectif est formulé à l'aide d'une fonction de maximisation.

L'objectif est de rechercher un plan qui respecte les contraintes modélisées tout en augmentant l'occupation du terrain.

## 🧠 Approches de résolution

### Programmation par Contraintes (CP)

Le modèle `ChocoModel` utilise Choco Solver pour représenter les décisions de culture sous forme de variables entières.

Les contraintes sont ajoutées au modèle, puis le solveur recherche des solutions respectant ces règles.

L'implémentation présentée comprend notamment :
- La modélisation des décisions de culture.
- Les contraintes de disponibilité.
- Les contraintes de demande.
- Les contraintes de durée des cycles.
- Un objectif de maximisation de l'occupation du sol.

### Satisfaisabilité booléenne (SAT)

Le modèle `SAT4JModel` traduit les décisions de planification en variables booléennes et en clauses logiques.

L'approche comprend notamment :
- L'encodage du choix d'une culture par parcelle et par période.
- La représentation de contraintes obligatoires.
- La prise en compte d'associations favorables par des contraintes souples.
- La préparation de variables auxiliaires pour l'encodage SAT.

Cette représentation permet d'étudier comment le problème agricole peut être formulé sous forme logique.

### Pourquoi utiliser deux approches ?

L'utilisation de deux modèles permet d'étudier différentes manières de représenter et de résoudre le même problème.

Une comparaison expérimentale peut notamment porter sur :
- Le temps de résolution.
- La faisabilité des solutions.
- Le respect des contraintes.
- La qualité du plan obtenu.
- Le comportement des modèles lorsque la taille du problème augmente.

Les performances respectives doivent être évaluées sur des instances et des conditions de test comparables.

## 🛠️ Technologies utilisées

| Technologie | Utilisation |
|---|---|
| Java | Développement de l'application et modélisation du problème. |
| Choco Solver | Programmation par Contraintes et optimisation. |
| SAT4J | Résolution de problèmes de satisfaisabilité booléenne. |
| Apache POI | Lecture des données agronomiques depuis Excel. |
| IntelliJ IDEA | Environnement de développement. |
| Git et GitHub | Gestion des versions et hébergement du code source. |

## 📂 Structure du projet

Les principaux composants du code sont organisés autour des données agronomiques, de la structure du jardin et des modèles de résolution.

Une fois le dépôt ouvert, la structure réelle du projet peut être présentée sous la forme suivante :

```text
crop-planner/
├── src/
│   └── main/
│       └── java/
│           └── fr/
│               └── cril/
│                   └── cropplanner/
│                       ├── model/
│                       │   ├── Culture.java
│                       │   ├── FamilleBotanique.java
│                       │   ├── AgronomicDatabase.java
│                       │   └── GardenTopology.java
│                       └── ...
├── données-agronomiques.xlsx
├── README.md
└── ...
```

*Cette arborescence est illustrative : elle doit être ajustée à l'organisation exacte des fichiers présents dans le dépôt.*

## 🚀 Installation et exécution

### Prérequis

Pour utiliser le projet, il faut notamment disposer de :
- Un JDK compatible avec la version de Java utilisée par le code.
- IntelliJ IDEA ou un autre environnement de développement Java.
- Les dépendances Choco Solver, SAT4J et Apache POI.
- Le fichier Excel contenant les données agronomiques nécessaires.

### Récupérer le projet

Clonez le dépôt GitHub :

```bash
git clone <URL_DU_DEPOT>
```

Puis ouvrez le projet dans IntelliJ IDEA.

### Configurer les dépendances

Vérifiez que les bibliothèques nécessaires sont disponibles dans le projet.

Si celui-ci utilise Maven ou Gradle, les dépendances doivent être déclarées dans le fichier de configuration correspondant.

### Préparer les données

Placez le fichier Excel à l'emplacement attendu par le programme et vérifiez la présence des feuilles nécessaires :

- `Familles botaniques`
- `Catalogue cultures`
- `Associations`
- `Contrainte C04`

Le chemin du fichier doit correspondre à celui utilisé lors du chargement des données.

### Lancer l'application

Exécutez la classe principale du projet depuis IntelliJ IDEA, en utilisant la configuration de lancement prévue par l'application.

**Remarque :** la classe principale, la configuration des dépendances et les commandes exactes de lancement restent à préciser selon l'organisation réelle du dépôt.

## 📊 Évaluation et résultats

Le projet permet d'étudier deux approches de résolution pour un problème de planification agricole.

Pour présenter des résultats expérimentaux, il est recommandé de documenter :
- Le nombre de parcelles.
- Le nombre de périodes.
- Le nombre de cultures disponibles.
- Les contraintes activées.
- Le temps de résolution mesuré.
- Le statut de résolution.
- La qualité et la validité agronomique du plan obtenu.

Les résultats devront être complétés à partir des exécutions réelles du programme.

## 🔬 Contexte du projet

Ce projet a été réalisé dans le cadre d'un stage de Master 2 Informatique, parcours Intelligence Artificielle.

Il porte sur la modélisation et la résolution d'un problème de planification de cultures maraîchères en contexte agroécologique tropical, en explorant la programmation par contraintes et la satisfaisabilité booléenne.

## 👩‍💻 Compétences mobilisées

- Programmation Java.
- Modélisation de problèmes combinatoires.
- Programmation par Contraintes.
- Satisfaisabilité booléenne (SAT).
- Encodage de contraintes logiques.
- Manipulation et structuration de données agronomiques.
- Lecture de fichiers Excel avec Apache POI.
- Conception et comparaison de modèles de résolution.
- Utilisation de Git et GitHub.

## 📌 Perspectives d'amélioration

Plusieurs pistes peuvent être étudiées selon les besoins du projet :

- Améliorer la représentation des cycles de culture.
- Enrichir les contraintes agronomiques.
- Ajouter des indicateurs de qualité des plans obtenus.
- Évaluer les performances sur différentes tailles d'instances.
- Comparer expérimentalement les approches CP et SAT.
- Améliorer la visualisation des plans de culture.

## 📄 Licence

Aucune licence de réutilisation n'est indiquée pour le moment. Les conditions de réutilisation du code devront être précisées si le projet est destiné à être partagé publiquement.

---

**CropPlanner — Optimisation et planification intelligente de cultures maraîchères.**








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
