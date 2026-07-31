package com.github.curiousoddman.curious_images.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;

@Getter
@RequiredArgsConstructor
public enum PhotoRefinementState {
    NO(-2, "No", "no", "No"),
    RATHER_NO(-1, "Rather No", "rather-no", "R−"),
    UNASSIGNED(0, "Unassigned", "unassigned", "—"),
    RATHER_YES(1, "Rather Yes", "rather-yes", "R+"),
    YES(2, "Yes", "yes", "Yes");

    private final int    dbValue;
    private final String displayLabel;
    private final String cssSuffix;
    private final String shortLabel;

    public static PhotoRefinementState fromDbValue(int dbValue) {
        return Arrays.stream(values())
                     .filter(s -> s.dbValue == dbValue)
                     .findFirst()
                     .orElseThrow(() -> new IllegalArgumentException("Unknown refinement state value: " + dbValue));
    }
}
