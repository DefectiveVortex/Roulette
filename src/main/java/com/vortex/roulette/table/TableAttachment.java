package com.vortex.roulette.table;

import com.vortex.roulette.game.RoundListener;

/**
 * Something that lives on a table and follows its rounds without the table knowing what it is: the wheel and its
 * ball, the board of recent numbers. The table adds it to its round as a listener, calls {@link #show} when the
 * first player sits down and {@link #hide} when the table is empty and its round has cleared (also when the table
 * is removed and when the plugin disables). Both must be safe to call twice.
 *
 * <p>Entities an attachment spawns must be non-persistent and carry the scoreboard tag {@link #ENTITY_TAG}.
 */
public interface TableAttachment extends RoundListener {
    String ENTITY_TAG = "roulette";

    void show();

    void hide();
}
