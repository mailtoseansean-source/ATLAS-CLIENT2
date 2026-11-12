package dev.atlasclient.core.debris;

/**
 * Whether ancient debris results reflect the real world. Computed from statistics of what the
 * client has received; never assumes more than the data shows.
 */
public enum DataAvailability {
    NO_DATA("No Nether chunks scanned yet"),
    NOT_IN_NETHER("Ancient debris only generates in the Nether"),
    AUTHORITATIVE_LOCAL("Local world: data comes from your own integrated server"),
    CONSISTENT("Received data looks consistent with natural generation"),
    SUSPECT_FAKE_ORES("Unreliable: far more debris than natural generation - the server likely sends fake ores (anti-xray). Only exposed debris is trustworthy"),
    UNAVAILABLE_HIDDEN("Unavailable: the server appears to hide ore data (anti-xray). Hidden blocks cannot be detected");

    private final String message;

    DataAvailability(String message) { this.message = message; }

    public String message() { return message; }

    public boolean resultsTrustworthy() {
        return this == AUTHORITATIVE_LOCAL || this == CONSISTENT;
    }
}
