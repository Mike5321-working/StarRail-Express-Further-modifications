package com.habitrain.core.scene.client.replay;

import net.minecraft.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ReplayFolderOpener {
    private ReplayFolderOpener() {}

    static void open(Path directory) throws IOException {
        Path path = Files.createDirectories(directory.toAbsolutePath().normalize());
        String opener = switch (Util.getPlatform()) {
            case WINDOWS -> windowsExplorer();
            case OSX -> "/usr/bin/open";
            default -> "xdg-open";
        };
        // Pass the folder as a single native path and let startup failures reach the caller.
        new ProcessBuilder(opener, path.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
                .getOutputStream().close();
    }

    private static String windowsExplorer() throws IOException {
        // The vanilla file URI handler can exit without opening a directory on Windows.
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            throw new IOException("SystemRoot is unavailable; cannot launch Explorer");
        }
        return Path.of(systemRoot, "explorer.exe").toString();
    }
}
