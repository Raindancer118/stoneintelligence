package de.raindancer118.stoneai.config;

import java.nio.file.Path;

/** Turns the {@code ~/…} paths people type into real filesystem paths. */
public final class UserPaths {

    private UserPaths() {
    }

    public static Path expand(String raw) {
        String value = raw.trim();
        if (value.equals("~")) {
            return home();
        }
        if (value.startsWith("~/")) {
            return home().resolve(value.substring(2));
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    /** Renders a path back with the home directory abbreviated, so config files stay portable. */
    public static String shorten(Path path) {
        Path home = home();
        return path.startsWith(home) ? "~/" + home.relativize(path) : path.toString();
    }

    private static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
