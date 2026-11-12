package dev.atlasclient.core.schematic;

import dev.atlasclient.core.nbt.Nbt;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Detects and loads supported schematic formats. */
public final class SchematicLoader {
    private SchematicLoader() { }

    public static boolean isSupported(Path file) {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".schem") || n.endsWith(".litematic");
    }

    public static Schematic load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }
        long size = Files.size(file);
        if (size > 64L * 1024 * 1024) {
            throw new IOException("File too large (" + size + " bytes)");
        }
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Nbt.Named named = Nbt.readFile(file);
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        String fallback = dot > 0 ? base.substring(0, dot) : base;
        if (n.endsWith(".litematic") || named.tag().contains("Regions")) {
            return LitematicIO.read(named.tag(), fallback, file.toString());
        }
        if (n.endsWith(".schematic")) {
            throw new IOException("Legacy MCEdit .schematic files (numeric block ids) are not supported; re-save as .schem or .litematic");
        }
        return SpongeSchematicIO.read(named, fallback, file.toString());
    }

    /** Lists supported files (recursively, max depth 4) relative to {@code dir}, sorted. */
    public static List<String> list(Path dir) {
        List<String> out = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            collect(dir, dir, out, 0);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static void collect(Path root, Path dir, List<String> out, int depth) {
        if (depth > 4 || out.size() > 2000) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    collect(root, p, out, depth + 1);
                } else if (isSupported(p)) {
                    out.add(root.relativize(p).toString().replace('\\', '/'));
                }
            }
        } catch (IOException ignored) {
            // unreadable directory: skip
        }
    }
}
