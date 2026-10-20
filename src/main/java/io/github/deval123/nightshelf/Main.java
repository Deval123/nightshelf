package io.github.deval123.nightshelf;

import java.io.IOException;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv();
        if (args.length > 0 && args[0].equals("scan")) {
            scan(config);
        } else {
            System.out.println(config);
        }
    }

    private static void scan(Config config) throws IOException {
        if (config.clipbankDir() == null) {
            System.err.println("CLIPBANK_DIR is not set");
            System.exit(2);
        }
        var clips = LibraryScanner.scan(config.clipbankDir());
        long uncatalogued = clips.stream().filter(c -> !c.catalogued()).count();
        System.out.println(clips.size() + " clips (" + uncatalogued + " not in catalog)");
    }
}
