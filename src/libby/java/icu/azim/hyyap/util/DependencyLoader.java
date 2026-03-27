package icu.azim.hyyap.util;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

public class DependencyLoader {
    public static void load(JavaPlugin plugin) {

        var libraryManager = new com.alessiodp.libby.HytaleLibraryManager(plugin);

        libraryManager.addRepository("https://maven.maxhenkel.de/repository/public");
        var opusLib = com.alessiodp.libby.Library.builder()
            .groupId("de{}maxhenkel{}opus4j") // "{}" is replaced with ".", useful to avoid unwanted changes made by maven-shade-plugin
            .artifactId("opus4j")
            .version("2.1.0")
            .resolveTransitiveDependencies(true)
            .build();

        
        libraryManager.addHytaleModding();
        var libdtc = com.alessiodp.libby.Library.builder()
            .groupId("dev{}bytesizedfox{}dectalk") 
            .artifactId("libdtc")
            .version("1.0.0")
            .resolveTransitiveDependencies(false) //i know there are none i built it 
            .build();

        
        libraryManager.addMavenCentral();
        
        libraryManager.loadLibrary(opusLib);
        libraryManager.loadLibrary(libdtc);
    }
}
