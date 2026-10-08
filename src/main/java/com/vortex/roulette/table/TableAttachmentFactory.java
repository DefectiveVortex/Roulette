package com.vortex.roulette.table;

/** Makes one attachment per table. Register with {@link TableManager#addAttachment}. */
@FunctionalInterface
public interface TableAttachmentFactory {
    TableAttachment create(RouletteTable table);
}
