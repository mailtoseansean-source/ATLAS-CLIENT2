package dev.atlasclient.core.build;

import java.util.Locale;

/**
 * One AutoBuilder job: an optional excavation phase followed by the placement phase. Pause,
 * resume and cancel apply to the whole job. Client thread only.
 */
public final class BuildJob {
    public enum Phase { EXCAVATING, BUILDING, FINISHED }

    private final ExcavationSession excavation; // may be null
    private final BuildSession build;
    private final WorldProbe probe;
    private Phase phase;
    private boolean paused;
    private boolean cancelled;
    private boolean started;

    public BuildJob(ExcavationSession excavation, BuildSession build, WorldProbe probe) {
        this.excavation = excavation;
        this.build = build;
        this.probe = probe;
        this.phase = excavation != null && !excavation.finished() ? Phase.EXCAVATING : Phase.BUILDING;
    }

    public Phase phase() { return phase; }
    public ExcavationSession excavation() { return excavation; }
    public BuildSession build() { return build; }
    public boolean isPaused() { return paused; }
    public boolean isCancelled() { return cancelled; }
    public boolean isStarted() { return started; }

    public boolean isRunning() { return started && !paused && !cancelled && phase != Phase.FINISHED; }

    public void start() {
        if (started) return;
        started = true;
        if (phase == Phase.BUILDING) {
            build.start();
        }
        advance();
    }

    public void pause() {
        if (!isRunning()) return;
        paused = true;
        if (phase == Phase.BUILDING) build.pause();
    }

    public void resume() {
        if (!paused || cancelled) return;
        paused = false;
        if (phase == Phase.BUILDING) build.resume();
    }

    public void cancel() {
        cancelled = true;
        build.cancel();
    }

    /** Moves to the next phase when the current one is done. Call every tick. */
    public void advance() {
        if (!started || cancelled) return;
        if (phase == Phase.EXCAVATING && excavation.finished()) {
            phase = Phase.BUILDING;
            // Blocks that were in the way when the plan was made are gone now: re-check the world.
            build.refresh(probe);
            if (build.state() == BuildSession.State.READY) {
                build.start();
            }
            if (paused) build.pause();
        }
        if (phase == Phase.BUILDING && build.state() == BuildSession.State.COMPLETED) {
            phase = Phase.FINISHED;
        }
    }

    public String summary() {
        if (cancelled) return "Cancelled";
        String prefix = paused ? "Paused - " : "";
        return switch (phase) {
            case EXCAVATING -> {
                ExcavationSession.Progress p = excavation.progress();
                yield String.format(Locale.ROOT, "%sExcavating %d/%d", prefix, p.done() + p.skipped() + p.failed(), p.total());
            }
            case BUILDING -> {
                BuildSession.Progress p = build.progress();
                yield String.format(Locale.ROOT, "%sBuilding %d/%d (%.0f%%)", prefix, p.done() + p.alreadyCorrect(), p.total(), p.fraction() * 100);
            }
            case FINISHED -> {
                BuildSession.Progress p = build.progress();
                yield String.format(Locale.ROOT, "Finished: %d placed, %d already correct, %d incorrect, %d failed, %d skipped",
                        p.done(), p.alreadyCorrect(), p.incorrect(), p.failed(), p.skipped());
            }
        };
    }
}
