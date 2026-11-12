package dev.atlasclient.core.module;

public enum Category {
    WORLD("World"),
    RENDER("Render"),
    CAMERA("Camera"),
    BUILDING("Building"),
    MOVEMENT("Movement"),
    UTILITY("Utility"),
    CLIENT("Client");

    private final String displayName;

    Category(String displayName) { this.displayName = displayName; }

    public String displayName() { return displayName; }
}
