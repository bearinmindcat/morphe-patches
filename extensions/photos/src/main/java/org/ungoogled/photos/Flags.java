package org.ungoogled.photos;

import java.util.function.BooleanSupplier;

/** Answers for Photos' own feature switches that a patch pins. */
public final class Flags {
    private Flags() {}

    /** A switch that is always on (Hide AI: Photos' own "Magic Editor off" switch). */
    public static final BooleanSupplier ON = new On();

    private static final class On implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return true;
        }
    }
}
