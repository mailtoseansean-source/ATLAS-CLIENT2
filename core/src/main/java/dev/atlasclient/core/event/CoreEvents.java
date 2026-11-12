package dev.atlasclient.core.event;

import dev.atlasclient.core.module.Module;

/** Game-independent events. The Fabric layer defines additional events of its own. */
public final class CoreEvents {
    private CoreEvents() { }

    /** Posted after a module has been enabled or disabled. */
    public record ModuleToggled(Module module, boolean enabled) { }

    /** Posted when the player leaves a world/server, or the dimension changes. */
    public record SessionReset(Reason reason, String newWorldKey) {
        public enum Reason {
            DISCONNECT,
            /** A different world or server. */
            WORLD_CHANGE,
            /** Same server or save, another dimension: dimension-tagged results may be kept. */
            DIMENSION_CHANGE,
            SHUTDOWN
        }
    }

    /** Posted after a configuration profile has been loaded. */
    public record ProfileLoaded(String profileName) { }
}
