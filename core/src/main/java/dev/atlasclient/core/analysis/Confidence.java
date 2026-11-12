package dev.atlasclient.core.analysis;

/**
 * How sure the Base Finder is that a place was built or used by players. Everything up to
 * {@link #VERY_HIGH} is a weighted estimate. {@link #CONFIRMED} is only given when the client has
 * directly received blocks that world generation cannot produce (concrete, placed leaves, beehives,
 * saplings...) as part of an observed artificial arrangement, never from inference alone.
 */
public enum Confidence {
    NONE("None"),
    VERY_LOW("Very Low"),
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
    VERY_HIGH("Very High"),
    CONFIRMED("Confirmed Structure");

    private final String label;

    Confidence(String label) { this.label = label; }

    public String label() { return label; }

    public boolean atLeast(Confidence other) { return ordinal() >= other.ordinal(); }
}
