package com.vega.fiidii.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class PathResolver {

    private static final Path ROOT = findRoot();

    private static Path findRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            // fiidii.md is our project-specific root marker
            if (Files.exists(current.resolve("fiidii.md"))) {
                return current;
            }
            current = current.getParent();
        }
        // Fallback to legacy behavior if marker not found
        return Paths.get("").toAbsolutePath().getParent();
    }

    public static Path root() {
        return ROOT;
    }

    public static Path resolve(String path) {
        return ROOT.resolve(path);
    }
}