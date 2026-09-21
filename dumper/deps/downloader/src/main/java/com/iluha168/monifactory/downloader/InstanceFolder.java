package com.iluha168.monifactory.downloader;

/**
 * Where a pack entry belongs in a Minecraft instance. The manifest says only "project 1187672,
 * file 6127958" - the project's CurseForge class is the only thing that says what the file is, and
 * guessing from the extension gets a shader pack or a data pack wrong.
 */
enum InstanceFolder {
    MODS(6, "mods"),
    RESOURCE_PACKS(12, "resourcepacks"),
    SHADER_PACKS(6552, "shaderpacks");

    private final int classId;
    private final String directory;

    InstanceFolder(int classId, String directory) {
        this.classId = classId;
        this.directory = directory;
    }

    String directory() {
        return directory;
    }

    /**
     * Refuses to guess. Worlds, data packs and Bukkit plugins have no obvious home in a client
     * instance, so a pack that starts shipping one should stop the build and get a human decision
     * rather than land somewhere the game never looks.
     */
    static InstanceFolder of(CurseForge.Project project) {
        for (var folder : values()) {
            if (folder.classId == project.classId()) {
                return folder;
            }
        }
        throw new IllegalStateException(
            "'" + project.name() + "' (project " + project.projectId() + ") is CurseForge class "
                + project.classId() + ", which this build has no instance folder for. Add one to InstanceFolder.");
    }
}
