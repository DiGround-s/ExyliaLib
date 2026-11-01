package net.exylia.lib.placeholder.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuietRenderTest {

    @Test
    @DisplayName("a quiet render leaves an unknown token as written and does not report it")
    void quietRenderDoesNotReportUnknown() {
        String rendered = TemplateCache.compile("%quiet_preview_token%", Registry.QUIET).render(null);

        assertEquals("%quiet_preview_token%", rendered);
        assertFalse(Registry.wasReportedUnknownForTests("quiet_preview_token"));
    }
}
