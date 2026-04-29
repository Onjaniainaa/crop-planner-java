package fr.cril.cropplanner.model;

/** Type d'association entre deux cultures. */
public enum TypeAssociation {
    FAVORABLE(+1),
    DEFAVORABLE(-1),
    NEUTRE(0);

    private final int value;

    TypeAssociation(int value) { this.value = value; }

    public int getValue() { return value; }

    public static TypeAssociation fromSymbol(String s) {
        if (s == null) return NEUTRE;
        return switch (s.trim()) {
            case "+" -> FAVORABLE;
            case "-", "\u2212" -> DEFAVORABLE;
            default -> NEUTRE;
        };
    }
}
