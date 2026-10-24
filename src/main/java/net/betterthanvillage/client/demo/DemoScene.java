package net.betterthanvillage.client.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A demo scene file ({@code demo/<name>.txt}): a header with {@code @from <scene>} (start from that scene's snapshot)
 * and {@code @camera [name] x y z yaw pitch} lines, then a {@code [setup]} section run once to build the scene's world
 * snapshot and a {@code [play]} section run on every replay from that snapshot. See {@code demo/README.md}.
 */
final class DemoScene {
    record Camera(String name, double x, double y, double z, float yaw, float pitch) {
        String line() {
            return String.format(Locale.ROOT, "@camera %s %.2f %.2f %.2f %.1f %.1f", name, x, y, z, yaw, pitch);
        }
    }

    final String name;
    final Path file;
    String from;
    final Map<String, Camera> cameras = new LinkedHashMap<>();
    final List<String> setup = new ArrayList<>();
    final List<String> play = new ArrayList<>();

    private DemoScene(String name, Path file) {
        this.name = name;
        this.file = file;
    }

    static Path fileOf(Path dir, String name) {
        return dir.resolve(name + ".txt");
    }

    static List<String> list(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".txt"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    static DemoScene load(Path dir, String name) throws IOException {
        DemoScene scene = new DemoScene(name, fileOf(dir, name));
        List<String> section = null;
        int n = 0;
        for (String raw : Files.readAllLines(scene.file, StandardCharsets.UTF_8)) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("//")) continue;
            if (line.equalsIgnoreCase("[setup]")) {
                section = scene.setup;
            } else if (line.equalsIgnoreCase("[play]")) {
                section = scene.play;
            } else if (line.startsWith("@")) {
                scene.header(line, n);
            } else if (section == null) {
                throw new IOException(scene.file.getFileName() + ":" + n + ": a command before [setup] or [play]");
            } else {
                section.add(line);
            }
        }
        return scene;
    }

    private void header(String line, int n) throws IOException {
        String[] w = line.split("\\s+");
        try {
            if (w[0].equals("@from") && w.length == 2) {
                from = w[1];
                return;
            }
            if (w[0].equals("@camera") && (w.length == 6 || w.length == 7)) {
                int i = w.length - 5;
                String cam = i == 2 ? w[1] : "main";
                cameras.put(cam, new Camera(cam, Double.parseDouble(w[i]), Double.parseDouble(w[i + 1]), Double.parseDouble(w[i + 2]),
                        Float.parseFloat(w[i + 3]), Float.parseFloat(w[i + 4])));
                return;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IOException(file.getFileName() + ":" + n + ": bad header line: " + line);
    }

    /** Writes the camera into the file: replaces its {@code @camera} line or adds one after the header. */
    void saveCamera(Camera camera) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        int insertAt = -1;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("[")) {
                if (insertAt < 0) insertAt = i;
                break;
            }
            if (line.startsWith("@")) insertAt = i + 1;
            String[] w = line.split("\\s+");
            if (w[0].equals("@camera") && (w.length == 7 ? w[1].equals(camera.name()) : camera.name().equals("main"))) {
                lines.set(i, camera.line());
                replaced = true;
                break;
            }
        }
        if (!replaced) lines.add(Math.max(0, insertAt), camera.line());
        Files.write(file, lines, StandardCharsets.UTF_8);
        cameras.put(camera.name(), camera);
    }
}
