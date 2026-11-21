package com.gtnhplanner.core.view.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.conformance.Conformance;
import com.gtnhplanner.core.base.JsonTree;
import com.gtnhplanner.core.view.Tones;
import com.gtnhplanner.harness.LangText;

/**
 * A card the board only shows (the mod's display-only card, a website card it cannot run): the foot says VIEW ONLY and
 * why, its count's rule has no menu and asks for nothing, and its menu only deletes it. The website never sets the
 * flag, so these are the core's own checks, over a shared case with the flag added.
 */
class CardReadOnlyTest {

    private static LangText lang;

    @BeforeAll
    static void english() {
        lang = LangText.english();
    }

    @AfterAll
    static void restore() {
        lang.close();
    }

    /** The first shared card case, read only for {@code reason}. */
    private static CardInput.Case readOnly(final String reason, final boolean solved) throws IOException {
        final Map<String, Object> file = JsonTree.object(JsonTree.parse(Conformance.text("view/card/cases.json")));
        final Map<String, Object> input = JsonTree.object(
            JsonTree.object(
                JsonTree.array(file.get("cases"))
                    .get(0))
                .get("input"));
        final Map<String, Object> card = new LinkedHashMap<>(JsonTree.object(input.get("card")));
        final Map<String, Object> flag = new LinkedHashMap<>();
        flag.put("reason", reason);
        flag.put("solved", solved);
        card.put("readOnly", flag);
        final Map<String, Object> menu = new LinkedHashMap<>(JsonTree.object(card.get("menu")));
        menu.put("readOnly", true);
        card.put("menu", menu);
        return new CardInput.Case(CardInputJson.dials(input.get("dials")), CardInputJson.card(card));
    }

    @Test
    void theFootSaysViewOnlyAndWhy() throws IOException {
        final CardInput.Case c = readOnly("kind", true);
        final CardView v = CardViews.card(c.card(), c.dials(), Cards.SIX, CardViews.Host.MOD);
        assertEquals("VIEW ONLY", v.foot().word());
        assertEquals(Tones.Role.PLAIN, v.foot().tone());
        assertTrue(
            List.of("a recipe only the website has", "website recipe")
                .contains(
                    v.foot()
                        .say()),
            v.foot()
                .say());
        assertNull(v.frame(), "no frame tone: nothing is wrong with it");
    }

    @Test
    void aCardOutsideTheSolveSaysSo() throws IOException {
        final CardInput.Case c = readOnly("off", false);
        final CardView v = CardViews.card(c.card(), c.dials(), Cards.SIX, CardViews.Host.MOD);
        assertTrue(
            v.foot()
                .say()
                .endsWith("not solved")
                || v.foot()
                    .say()
                    .equals("switched off"),
            v.foot()
                .say());
    }

    @Test
    void itsCountIsFixedAndItsMenuOnlyDeletes() throws IOException {
        final CardInput.Case c = readOnly("missing", true);
        final CardView v = CardViews.card(c.card(), c.dials(), Cards.SIX, CardViews.Host.MOD);
        assertTrue(
            v.count()
                .ruleFixed(),
            "the rule word has no menu");
        assertFalse(v.unset(), "it asks for no count");
        assertTrue(
            v.count()
                .title()
                .startsWith("From a website plan"),
            v.count()
                .title());
        assertEquals(
            List.of("delete"),
            v.menu()
                .stream()
                .map(CardView.MenuRow::id)
                .toList());
    }

    @Test
    void everyReasonHasItsWords() {
        for (final String reason : List.of("off", "kind", "missing", "absent")) {
            assertFalse(
                CardViews.t("read_only." + reason)
                    .startsWith("gtnhplanner.card."),
                reason);
            assertFalse(
                CardViews.t("read_only." + reason + ".short")
                    .startsWith("gtnhplanner.card."),
                reason);
        }
    }
}
