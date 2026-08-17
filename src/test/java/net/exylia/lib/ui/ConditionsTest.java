package net.exylia.lib.ui;

import net.exylia.lib.ui.internal.Conditions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a slot is shown.
 *
 * <p>The strings here are the shapes deployed menus write, resolved as they
 * would be by the time a condition is tested.
 */
class ConditionsTest {

    @Test
    @DisplayName("no condition means the slot is shown")
    void absentConditionShows() {
        assertTrue(Conditions.test(null));
        assertTrue(Conditions.test(""));
        assertTrue(Conditions.test("   "));
    }

    @Test
    @DisplayName("equality is how almost every real condition is written")
    void equality() {
        // ExyliaClans, menus/lfc_browse.yml and friends: 72 of the 81
        // conditions in the ecosystem are this.
        assertTrue(Conditions.test("active == active"));
        assertFalse(Conditions.test("expired == active"));
        assertTrue(Conditions.test("none != active"));
        assertFalse(Conditions.test("active != active"));
    }

    @Test
    @DisplayName("comparing is case-insensitive, because config values are typed by hand")
    void equalityIgnoresCase() {
        assertTrue(Conditions.test("TRUE == true"));
        assertTrue(Conditions.test("Active == active"));
    }

    @Test
    @DisplayName("numbers compare as numbers")
    void numeric() {
        assertTrue(Conditions.test("5 > 3"));
        assertFalse(Conditions.test("3 > 5"));
        assertTrue(Conditions.test("3 >= 3"));
        assertTrue(Conditions.test("2 <= 3"));
        assertTrue(Conditions.test("0 < 1"));
    }

    @Test
    @DisplayName(">= is not read as > followed by rubbish")
    void twoCharacterOperatorsWinFirst() {
        // Trying ">" first would split "5 >= 3" into "5" and "= 3", which is
        // not a number, so a true condition would come back false.
        assertTrue(Conditions.test("5 >= 3"));
        assertTrue(Conditions.test("3 <= 5"));
    }

    @Test
    @DisplayName("a bare boolean works, so a condition can be one placeholder")
    void bareBoolean() {
        assertTrue(Conditions.test("true"));
        assertFalse(Conditions.test("false"));
    }

    @Test
    @DisplayName("a condition that cannot be read hides the slot")
    void unreadableHides() {
        // A slot that should have been hidden and is shown hands a button to
        // somebody who should not have it; the other way round is merely
        // invisible. So an unresolved placeholder fails closed.
        assertFalse(Conditions.test("%never_registered%"));
        assertFalse(Conditions.test("nonsense"));
        assertFalse(Conditions.test("%coins% > 100"), "a placeholder that did not resolve");
    }

    @Test
    @DisplayName("text comparisons work for the menus that use them")
    void textOperators() {
        assertTrue(Conditions.test("hello world contains world"));
        assertTrue(Conditions.test("practice:kits startsWith practice"));
        assertTrue(Conditions.test("practice:kits endsWith kits"));
        assertFalse(Conditions.test("hello contains goodbye"));
    }

    @Test
    @DisplayName("a condition naming a placeholder is known to need resolving")
    void dynamicIsRecognised() {
        assertTrue(Conditions.isDynamic("%lfc_state% == none"));
        assertFalse(Conditions.isDynamic("true"));
    }
}
