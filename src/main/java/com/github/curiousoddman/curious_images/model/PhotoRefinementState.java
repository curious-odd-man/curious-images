package com.github.curiousoddman.curious_images.model;

import java.util.Arrays;
import java.util.stream.Stream;

public enum PhotoRefinementState {
    NO(-2),
    RATHER_NO(-1),
    UNASSIGNED(0),
    RATHER_YES(1),
    YES(2);

    private final short dbValue;

    PhotoRefinementState(int dbValue) {
        this.dbValue = (short) dbValue;
    }

    public short dbValue() {
        return dbValue;
    }

    public static PhotoRefinementState fromDbValue(short dbValue) {
        Stream<PhotoRefinementState> stream = Arrays.stream(values());
        return stream
                     .filter(s -> s.dbValue() == dbValue)
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("Unknown refinement state value: " + dbValue));
    }
}
