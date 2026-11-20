package com.gtnhplanner.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.core.base.Uuid;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.web.Web;

/** A card's recipe by reference: the model asks the installed {@link RecipeSource} and keeps only the reference. */
class RecipesTest {

    /** A host's port value: a resource and an amount. */
    private static final class Held {

        final ResourceKey key;
        int amount;

        Held(final ResourceKey key, final int amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    private static final PortType<Held> HELD = new PortType<>() {

        @Override
        public String getKey() {
            return "held";
        }

        @Override
        public ResourceKey resource(final Held value) {
            return value.key;
        }

        @Override
        public String key(final Held value) {
            return value.key.toString();
        }

        @Override
        public int extractAmount(final Held value) {
            return value.amount;
        }

        @Override
        public void setAmount(final Held value, final int amount) {
            value.amount = amount;
        }

        @Override
        public Held copy(final Held value) {
            return new Held(value.key, value.amount);
        }

        @Override
        public String formatDisplayName(final Held value) {
            return value.key.id();
        }

        @Override
        public String formatAmount(final float perSecond) {
            return "";
        }

        @Override
        public int color(final Held value) {
            return -1;
        }

        @Override
        public int getPinInputColor() {
            return 0;
        }

        @Override
        public int getPinOutputColor() {
            return 0;
        }

        @Override
        public int getArrowColor() {
            return 0;
        }

        @Override
        public boolean same(final Held a, final Held b) {
            return a.key.equals(b.key);
        }

        @Override
        public boolean folds(final Held a, final Held b) {
            return a.key.equals(b.key);
        }
    };

    private static Port<Held> port(final String key, final int amount) {
        return new Port<>(HELD, new Held(ResourceKey.parse(key), amount), 1f);
    }

    /** A host that has one recipe, "r1": two planks and a plank in, a stick out, 40 ticks. */
    private static final class OneRecipe implements RecipeSource {

        int reads;
        final List<Node> powered = new ArrayList<>();

        @Override
        public boolean readRecipe(final Node card) {
            reads++;
            if (!"r1".equals(card.recipe.id())) return false;
            card.inputs.clear();
            card.outputs.clear();
            card.properties.clear();
            card.inputs.add(port("item:minecraft:planks", 2));
            card.inputs.add(port("item:minecraft:planks", 1));
            card.outputs.add(port("item:minecraft:stick", 4));
            card.properties.put(RecipeProperty.DURATION_TICKS, 40);
            return true;
        }

        @Override
        public void readPower(final Node card) {
            powered.add(card);
        }

        @Nullable
        @Override
        public Web.Recipe record(final Node card) {
            if (!"r1".equals(card.recipe.id())) return null;
            final Web.Recipe r = new Web.Recipe();
            r.id = card.recipe.id();
            return r;
        }
    }

    @BeforeAll
    static void defaultProfile() {
        if (MachineProfileRegistry.get(MachineProfileRegistry.defaultId()) == null) MachineProfileRegistry
            .register(MachineProfile.builder(MachineProfileRegistry.defaultId(), "Default").build());
    }

    @AfterEach
    void noSource() {
        RecipeSource.install(RecipeSource.NONE);
    }

    @Test
    void aReferenceIsItsIdAndIndex() {
        assertSame(RecipeRef.NONE, RecipeRef.of(null, 0));
        assertNull(RecipeRef.NONE.id());
        assertEquals(RecipeRef.of("{\"handlerName\":\"x\"}", 3), RecipeRef.of("{\"handlerName\":\"x\"}", 3));
        assertNotEquals(RecipeRef.of("a", 1), RecipeRef.of("a", 2));
        assertNotEquals(RecipeRef.of("a", 1), RecipeRef.of("b", 1));
        assertEquals(RecipeRef.of(null, 2), RecipeRef.of(null, 2), "an index without an id is kept: the save writes it");
        assertEquals(2, RecipeRef.of(null, 2).index());
    }

    @Test
    void aRecipeCardReadsItsPortsFromTheSource() {
        final OneRecipe source = new OneRecipe();
        RecipeSource.install(source);
        final Node card = new Node(new Uuid(0, 1), 10, 20, "Workbench", RecipeRef.of("r1", 0), new Object(), null);
        assertEquals(1, source.reads, "read once, when made");
        assertEquals(1, card.inputs.size(), "inputs of one resource fold into one port");
        assertEquals(3, card.inputs.get(0).amount());
        assertEquals(1, card.outputs.size());
        assertEquals(40, card.getRecipeDuration());
        assertEquals("r1", card.record().id, "the record comes from the source");
        assertEquals(MachineProfileRegistry.defaultId(), card.machineConfig.profileId);
    }

    @Test
    void aRecipeTheSourceCannotFindLeavesTheCardAsItIs() {
        final OneRecipe source = new OneRecipe();
        RecipeSource.install(source);
        final Node card = new Node(new Uuid(0, 2), 0, 0);
        card.recipe = RecipeRef.of("gone", 4);
        card.inputs.add(port("item:minecraft:dirt", 1));
        card.refresh();
        assertEquals(1, source.reads);
        assertEquals(1, card.inputs.size(), "the card keeps what it had");
        assertEquals("item:minecraft:dirt", card.inputs.get(0).resource().toString());
        assertNull(card.record());
    }

    @Test
    void aPowerCardIsReadByTheSourceAsAPowerCard() {
        final OneRecipe source = new OneRecipe();
        RecipeSource.install(source);
        final Node card = Node.power(Node.CUSTOM_RATE, java.util.Map.of(), 0, 0);
        assertEquals(List.of(card), source.powered);
        assertEquals(0, source.reads);
        assertTrue(card.countAlwaysExact(), "a custom rate card's count is Exactly");
        card.setMachineCountFixed(true);
        assertTrue(card.isMachineCountFixed());
        final Node tank = Node.power(Node.FLUID_TANK, java.util.Map.of(), 0, 0);
        tank.setMachineCountFixed(true);
        assertFalse(tank.isMachineCountFixed(), "a fluid tank has no count to set");
    }

    @Test
    void withNoSourceInstalledNothingIsRead() {
        final Node card = new Node(new Uuid(0, 3), 0, 0);
        card.recipe = RecipeRef.of("r1", 0);
        card.outputs.add(port("item:minecraft:stick", 1));
        card.refresh();
        assertEquals(1, card.outputs.size());
        assertNull(card.record());
    }
}
