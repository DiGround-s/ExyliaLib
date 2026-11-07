package net.exylia.lib.screen.internal;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScreenRoutesTest {

    @Test
    void readsIdsWithNamespacesAndState() {
        Map<String, Map<String, String>> routes = ScreenRoutes.parse(
                " player/queue_kit:tab=play,category=%category_id%  practice:settings:tab=settings plain ");
        assertEquals(Map.of("tab", "play", "category", "%category_id%"), routes.get("player/queue_kit"));
        assertEquals(Map.of("tab", "settings"), routes.get("practice:settings"));
        assertEquals(Map.of(), routes.get("plain"));
        assertEquals(3, routes.size());
    }

    @Test
    void fillsStateFromTheMenuAndLeavesUnansweredKeysOut() {
        ScreenRoutes.Route route = new ScreenRoutes.Route(null,
                ScreenRoutes.parse("kits:tab=play,category=%category_id%,kit=%kit_id%").get("kits"));
        assertEquals(Map.of("tab", "play", "category", "uhc"), route.state(Map.of("category_id", "uhc")));
    }
}
