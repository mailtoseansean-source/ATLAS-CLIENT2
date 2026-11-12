package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Conservative estimate of a suspected base's condition from observable blocks only.
 *
 * <p>Important limitation: the client never receives the contents of containers it has not opened,
 * so "intact" can only mean that storage blocks and functional builds are still present, not that
 * they still hold items. When the evidence is insufficient the status is {@link #UNKNOWN}.</p>
 */
public enum BaseStatus {
    UNKNOWN("Unknown"),
    LIKELY_INTACT("Likely intact"),
    POSSIBLY_RAIDED("Possibly raided"),
    LIKELY_RAIDED("Likely raided");

    private final String label;

    BaseStatus(String label) { this.label = label; }

    public String label() { return label; }

    public record Estimate(BaseStatus status, List<String> reasons) { }

    public static Estimate estimate(Confidence confidence, Map<Indicator, Integer> counts, boolean netherDimension,
                                    boolean structureMarkers) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Container contents are server-side and not visible to the client; status reflects visible blocks only.");
        if (!confidence.atLeast(Confidence.MEDIUM)) {
            reasons.add(0, "Not enough evidence of a base to estimate its condition.");
            return new Estimate(UNKNOWN, reasons);
        }
        if (structureMarkers) {
            reasons.add(0, "Generated-structure markers present; status not estimated to avoid confusing a village or dungeon with a base.");
            return new Estimate(UNKNOWN, reasons);
        }
        int storage = counts.getOrDefault(Indicator.STORAGE, 0);
        int beds = counts.getOrDefault(Indicator.BED, 0);
        int work = counts.getOrDefault(Indicator.WORKSTATION, 0);
        int built = counts.getOrDefault(Indicator.CRAFTED_BUILDING, 0) + counts.getOrDefault(Indicator.PLAYER_ONLY, 0)
                + counts.getOrDefault(Indicator.REDSTONE, 0) + counts.getOrDefault(Indicator.LIGHT_SOURCE, 0);
        int fire = netherDimension ? 0 : counts.getOrDefault(Indicator.FIRE, 0);

        boolean heavyBuild = built >= 25 || work >= 3;
        if (storage >= 4 && (beds + work) >= 2 && fire == 0) {
            reasons.add(0, storage + " storage containers and " + (beds + work) + " beds/workstations are still in place, with no fire observed.");
            return new Estimate(LIKELY_INTACT, reasons);
        }
        if (heavyBuild && storage == 0 && fire > 0) {
            reasons.add(0, "Substantial player building (" + built + " blocks) with no storage containers left and " + fire
                    + " fire blocks outside the Nether.");
            return new Estimate(LIKELY_RAIDED, reasons);
        }
        if (heavyBuild && storage == 0) {
            reasons.add(0, "Substantial player building (" + built + " blocks) but no storage containers observed; storage may have been removed, or never placed here.");
            return new Estimate(POSSIBLY_RAIDED, reasons);
        }
        if (fire > 0 && built >= 10) {
            reasons.add(0, fire + " fire blocks among player-built structures outside the Nether.");
            return new Estimate(POSSIBLY_RAIDED, reasons);
        }
        reasons.add(0, "Visible blocks do not clearly indicate the base's condition.");
        return new Estimate(UNKNOWN, reasons);
    }
}
