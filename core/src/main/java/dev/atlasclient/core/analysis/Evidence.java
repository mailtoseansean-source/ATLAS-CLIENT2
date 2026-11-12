package dev.atlasclient.core.analysis;

import java.util.List;

/**
 * One piece of evidence behind a chunk's score.
 *
 * <p>{@link Kind#OBSERVATION} entries are verified facts about the block data the client received
 * (these blocks exist at these coordinates). {@link Kind#INFERENCE} entries are heuristic
 * interpretations of those facts (for example "growth suggests the chunk was loaded for a long
 * time") and are never presented as proof of player activity.</p>
 */
public record Evidence(String id, Kind kind, Strength strength, double contribution, int count,
                       String description, List<long[]> samplePositions) {
    public enum Kind { OBSERVATION, INFERENCE, CAVEAT }

    public enum Strength { WEAK, MODERATE, STRONG }

    public Evidence {
        samplePositions = samplePositions == null ? List.of() : List.copyOf(samplePositions);
    }
}
