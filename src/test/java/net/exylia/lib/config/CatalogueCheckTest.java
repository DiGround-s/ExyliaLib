package net.exylia.lib.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueCheckTest {

    private static final Pattern TEXT = Pattern.compile("(^|\\.)(name|description)$");

    @Test
    void aFaithfulTranslationHasNoProblems() {
        assertEquals(List.of(), CatalogueCheck.problems(CatalogueCheckTest.class, "catalogue-check/good.yml", List.of("es"), TEXT, 34));
    }

    @Test
    void everyKindOfDriftIsNamed() {
        List<String> problems = CatalogueCheck.problems(CatalogueCheckTest.class, "catalogue-check/bad.yml", List.of("es"), TEXT, 34);
        assertTrue(problems.stream().anyMatch(p -> p.contains("a.name lost %count%")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("a.name added {error}")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("b.name is not translated")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("c is not in the base")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("a.description is too wide")), problems.toString());
    }

    @Test
    void aMissingLanguageIsAProblem() {
        assertEquals(List.of("fr/catalogue-check/good.yml is not packaged"),
                CatalogueCheck.problems(CatalogueCheckTest.class, "catalogue-check/good.yml", List.of("fr"), TEXT, 34));
    }
}
