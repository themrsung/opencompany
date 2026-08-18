package com.coreintra.businesstime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the shared conformance corpus that the TypeScript implementation also runs.
 *
 * <p>Two implementations of the same time model drift, and the drift is
 * invisible until a document is stamped one way on the server and displayed
 * another way in the browser. The only reliable defence is one corpus that both
 * suites load: {@code spec/business-time-vectors.json}. Add a case there first,
 * then make both sides pass it.
 *
 * <p>Deliberately hand-parsed. Pulling a JSON library into the domain core to
 * read a test fixture would put a dependency in the module that is supposed to
 * have none.
 */
class SharedVectorConformanceTest {

    private static String json;

    @BeforeAll
    static void loadCorpus() throws IOException {
        File file = locateVectors();
        assertThat(file)
                .as("shared vector corpus must exist; it is the contract between the Java and "
                        + "TypeScript implementations")
                .exists();
        json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static File locateVectors() {
        // Surefire runs with the module directory as CWD; the corpus lives at
        // the repository root. Walk up rather than hard-coding a depth, so this
        // keeps working if the module moves.
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 6 && dir != null; i++) {
            File candidate = new File(dir, "spec/business-time-vectors.json");
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("spec/business-time-vectors.json not found above " + new File("").getAbsolutePath());
    }

    @Test
    @DisplayName("every valid vector parses to the stated date, offset and absolute time")
    void validVectors() {
        List<String> blocks = objectsInArray("valid");
        assertThat(blocks).as("corpus should not be empty").isNotEmpty();

        for (String block : blocks) {
            String wire = stringField(block, "wire");
            BusinessInstant instant = BusinessInstant.parse(wire);

            assertThat(instant.businessDate())
                    .as("businessDate of %s", wire)
                    .isEqualTo(LocalDate.parse(stringField(block, "date")));
            assertThat(instant.offsetSeconds())
                    .as("offsetSeconds of %s", wire)
                    .isEqualTo(intField(block, "offsetSeconds"));
            assertThat(instant.isOutsideCalendarDay())
                    .as("outsideCalendarDay of %s", wire)
                    .isEqualTo(boolField(block, "outsideCalendarDay"));
            assertThat(instant.absoluteDateTime())
                    .as("absolute of %s", wire)
                    .isEqualTo(LocalDateTime.parse(stringField(block, "absolute")));
            assertThat(instant.toWireString())
                    .as("%s should already be canonical", wire)
                    .isEqualTo(wire);
        }
    }

    @Test
    @DisplayName("every invalid vector is rejected, for the stated reason")
    void invalidVectors() {
        List<String> blocks = objectsInArray("invalid");
        assertThat(blocks).isNotEmpty();

        for (String block : blocks) {
            final String wire = stringField(block, "wire");
            String reason = stringField(block, "reasonContains");
            assertThatThrownBy(() -> BusinessInstant.parse(wire))
                    .as("%s must be rejected", wire)
                    .isInstanceOf(BusinessInstantParseException.class)
                    .hasMessageContaining(reason);
        }
    }

    @Test
    @DisplayName("non-canonical but valid inputs normalise to the stated canonical form")
    void canonicalisationVectors() {
        for (String block : objectsInArray("canonicalisation")) {
            String input = stringField(block, "input");
            String canonical = stringField(block, "canonical");
            assertThat(BusinessInstant.parse(input).toWireString())
                    .as("%s (%s)", input, stringField(block, "why"))
                    .isEqualTo(canonical);
        }
    }

    @Test
    @DisplayName("the corpus ordering is reproduced by the comparator")
    void orderingVector() {
        List<String> ascending = stringsInArray("ascending");
        assertThat(ascending).hasSizeGreaterThan(1);

        List<BusinessInstant> shuffled = new ArrayList<BusinessInstant>();
        for (int i = ascending.size() - 1; i >= 0; i--) {
            shuffled.add(BusinessInstant.parse(ascending.get(i)));
        }
        Collections.sort(shuffled, BusinessInstant.COMPARATOR);

        List<String> actual = new ArrayList<String>();
        for (BusinessInstant instant : shuffled) {
            actual.add(instant.toWireString());
        }
        assertThat(actual).containsExactlyElementsOf(ascending);
    }

    // --- a deliberately small JSON reader, sufficient for this flat corpus ---

    private static List<String> objectsInArray(String arrayKey) {
        int start = json.indexOf("\"" + arrayKey + "\"");
        if (start < 0) {
            throw new IllegalStateException("no array named " + arrayKey);
        }
        int open = json.indexOf('[', start);
        int close = matching(json, open, '[', ']');
        String body = json.substring(open + 1, close);

        List<String> objects = new ArrayList<String>();
        int i = 0;
        while (i < body.length()) {
            int objOpen = body.indexOf('{', i);
            if (objOpen < 0) {
                break;
            }
            int objClose = matching(body, objOpen, '{', '}');
            objects.add(body.substring(objOpen, objClose + 1));
            i = objClose + 1;
        }
        return objects;
    }

    private static List<String> stringsInArray(String arrayKey) {
        int start = json.indexOf("\"" + arrayKey + "\"");
        int open = json.indexOf('[', start);
        int close = matching(json, open, '[', ']');
        String body = json.substring(open + 1, close);

        List<String> values = new ArrayList<String>();
        int i = 0;
        while (true) {
            int quote = body.indexOf('"', i);
            if (quote < 0) {
                break;
            }
            int end = body.indexOf('"', quote + 1);
            values.add(body.substring(quote + 1, end));
            i = end + 1;
        }
        return values;
    }

    private static int matching(String text, int openIndex, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' && (i == 0 || text.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (inString) {
                continue;
            }
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("unbalanced " + open + " from index " + openIndex);
    }

    private static String stringField(String object, String key) {
        int k = object.indexOf("\"" + key + "\"");
        if (k < 0) {
            throw new IllegalStateException("no field " + key + " in " + object);
        }
        int colon = object.indexOf(':', k);
        int quote = object.indexOf('"', colon);
        StringBuilder value = new StringBuilder();
        for (int i = quote + 1; i < object.length(); i++) {
            char c = object.charAt(i);
            if (c == '\\') {
                value.append(object.charAt(++i));
                continue;
            }
            if (c == '"') {
                break;
            }
            value.append(c);
        }
        return value.toString();
    }

    private static int intField(String object, String key) {
        int k = object.indexOf("\"" + key + "\"");
        int colon = object.indexOf(':', k);
        int end = colon + 1;
        while (end < object.length() && "-0123456789".indexOf(object.charAt(end)) < 0) {
            end++;
        }
        int numStart = end;
        while (end < object.length() && "-0123456789".indexOf(object.charAt(end)) >= 0) {
            end++;
        }
        return Integer.parseInt(object.substring(numStart, end));
    }

    private static boolean boolField(String object, String key) {
        int k = object.indexOf("\"" + key + "\"");
        int colon = object.indexOf(':', k);
        String rest = object.substring(colon + 1).trim();
        return rest.startsWith("true");
    }
}
