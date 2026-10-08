package com.vortex.roulette.table;

import com.vortex.roulette.game.TableRules;
import com.vortex.roulette.util.SafeYaml;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * tables.yml. The file belongs to the admin: an entry that cannot be read is reported and left in the file, a
 * file that is not YAML is moved aside and rebuilt from what still parses, and every write is atomic.
 */
final class TableStore {
    private static final String HEADER = """
            Roulette tables. Written by /roulette table create, remove and set; edit by hand only while the
            server is stopped. Each table keeps its own limits and timings (see config.yml 'table:' for what
            they mean). x, y, z is the table's first block at the wheel end, facing is the direction the layout
            runs from the wheel.""";

    private final File file;
    private final Logger log;
    private YamlConfiguration doc = new YamlConfiguration();
    /** Set when a broken file could not be moved aside: then it is never written over. */
    private boolean frozen;

    TableStore(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    List<TableDef> load(TableRules fallback) {
        SafeYaml.LoadResult result = SafeYaml.load(file, log, false);
        frozen = false;
        switch (result.status()) {
            case MISSING -> doc = new YamlConfiguration();
            case BROKEN -> {
                if (result.brokenCopy() == null) {
                    frozen = true;
                    doc = SafeYaml.salvage(file);
                    log.severe("tables.yml is not valid YAML (" + result.error() + ") and could not be moved aside."
                            + " Loading the tables that can still be read; tables.yml will NOT be written until it"
                            + " is fixed or removed and the server restarted.");
                } else {
                    doc = SafeYaml.salvage(result.brokenCopy());
                    log.severe("tables.yml was not valid YAML (" + result.error() + "). The original is kept as "
                            + result.brokenCopy().getName() + "; tables.yml is rebuilt from the tables that could"
                            + " still be read.");
                    save();
                }
            }
            default -> doc = result.config();
        }

        List<TableDef> tables = new ArrayList<>();
        ConfigurationSection section = doc.getConfigurationSection("tables");
        if (section == null) {
            return tables;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            try {
                if (entry == null) {
                    throw new IllegalArgumentException("it is not a table entry");
                }
                tables.add(TableDef.read(id, entry, fallback));
            } catch (IllegalArgumentException e) {
                log.warning("tables.yml: table '" + id + "' is skipped: " + e.getMessage()
                        + ". The entry stays in the file; fix it and restart, or remove it.");
            }
        }
        return tables;
    }

    /** True if the entry exists in the file, readable or not: such a name cannot be given to a new table. */
    boolean hasEntry(String id) {
        return doc.contains("tables." + id);
    }

    boolean put(TableDef def) {
        doc.set("tables." + def.id(), null);
        def.write(doc.createSection("tables." + def.id()));
        return save();
    }

    boolean remove(String id) {
        doc.set("tables." + id, null);
        return save();
    }

    private boolean save() {
        if (frozen) {
            log.warning("tables.yml was not written: the broken file is still in the way.");
            return false;
        }
        try {
            doc.options().setHeader(HEADER.lines().toList());
            SafeYaml.saveAtomically(doc, file);
            return true;
        } catch (IOException e) {
            log.severe("Could not write tables.yml: " + e.getMessage());
            return false;
        }
    }
}
