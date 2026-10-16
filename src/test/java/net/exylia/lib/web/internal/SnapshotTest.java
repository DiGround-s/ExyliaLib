package net.exylia.lib.web.internal;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The placeholders a snapshot resolves for the preview are the ones the template compiler would read. */
class SnapshotTest {

    @Test
    void everyPercentPairOnALineIsCollectedWhateverItHolds() {
        Set<String> tokens = new LinkedHashSet<>();
        Snapshot.collect(JsonParser.parseString("""
                {"lines": ["%player_name% and %pinger_online_172.18.0.1:40001%",
                           "%progress_bar_{bungee_survivalpvp}_c:&#ffc58f-_l:10_m:300_fullbar:SERVER LLENO!%",
                           "%eco_balance:comma|none% 50% off %"],
                 "nested": {"x": "%a%%b%"}}
                """), tokens);
        assertEquals(List.of("%player_name%", "%pinger_online_172.18.0.1:40001%",
                "%progress_bar_{bungee_survivalpvp}_c:&#ffc58f-_l:10_m:300_fullbar:SERVER LLENO!%",
                "%eco_balance:comma|none%", "% off %", "%a%", "%b%"), List.copyOf(tokens));
    }
}
