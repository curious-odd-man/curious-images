package com.github.curiousoddman.curious_images.util;

import com.helger.css.ECSSVersion;
import com.helger.css.decl.CSSSelector;
import com.helger.css.decl.CSSStyleRule;
import com.helger.css.decl.CascadingStyleSheet;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StylingTests {
    private static final Path CSS_DIR = Paths.get("src/main/resources/styles");
    private static final Path SRC     = Paths.get("src/main/java");

    @Test
    void noSetStyleCallsAllowed() throws IOException {
        List<String> violations;

        try (Stream<Path> files = Files.walk(SRC)) {
            violations = files
                    .filter(p -> p.toString()
                                  .endsWith(".java"))
                    .flatMap(this::scanFile)
                    .collect(Collectors.toList());
        }

        // Exclusions
        violations.removeIf(l -> l.contains("\"-fx-background-color: \" + swatchColorFor(theme) + \";\""));

        if (!violations.isEmpty()) {
            fail("Inline styles are forbidden:\n\n" +
                    String.join("\n", violations));
        }
    }

    private Stream<String> scanFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);

            return Stream.iterate(0, i -> i + 1)
                         .limit(lines.size())
                         .filter(i -> lines.get(i)
                                           .contains(".setStyle("))
                         .map(i -> file + ":" + (i + 1) + " -> " + lines.get(i)
                                                                        .trim());

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void allStylesheetsHaveSameSelectors() throws IOException {

        Map<String, Set<String>> selectorsPerFile = new LinkedHashMap<>();

        try (Stream<Path> files = Files.list(CSS_DIR)) {

            files.filter(f -> f.toString()
                               .endsWith(".css"))
                 .forEach(file -> selectorsPerFile.put(
                         file.getFileName()
                             .toString(),
                         readSelectors(file)));
        }

        Set<String> reference = selectorsPerFile.values()
                                                .iterator()
                                                .next();

        List<Executable> assertions = new ArrayList<>();

        selectorsPerFile.forEach((name, selectors) -> assertions.add(() -> {
            Set<String> missing = new TreeSet<>(reference);
            missing.removeAll(selectors);

            Set<String> extra = new TreeSet<>(selectors);
            extra.removeAll(reference);

            assertTrue(
                    missing.isEmpty() && extra.isEmpty(),
                    () -> """
                            %s
                            Missing: %s
                            Extra: %s
                            """.formatted(name, missing, extra)
            );
        }));

        assertAll(assertions);
    }

    private Set<String> readSelectors(Path cssFile) {

        CSSReaderSettings aSettings = new CSSReaderSettings();
        aSettings.setCSSVersion(ECSSVersion.LATEST);
        CascadingStyleSheet sheet = CSSReader.readFromFile(
                cssFile.toFile(),
                aSettings
        );

        Set<String> selectors = new TreeSet<>();

        for (CSSStyleRule rule : sheet.getAllStyleRules()) {
            for (CSSSelector selector : rule.getAllSelectors()) {
                selectors.add(selector.getAsCSSString());
            }
        }

        return selectors;
    }
}
