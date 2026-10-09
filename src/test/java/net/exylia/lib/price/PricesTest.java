package net.exylia.lib.price;

import net.exylia.lib.FakeServer;
import net.exylia.lib.price.internal.PriceRuntime;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricesTest {

    /** An item with nothing behind it: {@code new ItemStack(...)} needs a real server. */
    private static final class Piece extends ItemStack {
        final Material material;

        Piece(Material material) {
            this.material = material;
        }

        @Override
        public @NotNull Material getType() {
            return material;
        }
    }

    private static final ItemStack STONE = new Piece(Material.STONE);
    private static final ItemStack DIRT = new Piece(Material.DIRT);

    private Plugin shop;
    private Plugin worth;

    @BeforeEach
    void setUp() {
        FakeServer.install();
        FakeServer.reset();
        shop = FakeServer.newPlugin("Shop");
        worth = FakeServer.newPlugin("Worth");
    }

    @AfterEach
    void tearDown() {
        PriceRuntime.release("Shop");
        PriceRuntime.release("Worth");
    }

    private static PriceProvider only(Material material, String price, String provider) {
        return stack -> stack.getType() == material
                ? Optional.of(new PriceQuote(new BigDecimal(price), "", provider))
                : Optional.empty();
    }

    @Test
    @DisplayName("nothing registered is empty")
    void empty() {
        assertTrue(Prices.sell(STONE).isEmpty());
        assertTrue(Prices.sell(STONE, 64).isEmpty());
        assertFalse(Prices.sellable(STONE));
        assertTrue(Prices.buy(STONE).isEmpty());
        assertTrue(Prices.sell(null).isEmpty());
    }

    @Test
    @DisplayName("the highest priority answers first, whatever the registration order")
    void priority() {
        Prices.register(worth, only(Material.STONE, "1", "worth"));
        Prices.register(shop, only(Material.STONE, "2", "shop"), 100);
        assertEquals("shop", Prices.sell(STONE).orElseThrow().provider());
        assertEquals(new BigDecimal("2.00"), Prices.sell(STONE).orElseThrow().each());
    }

    @Test
    @DisplayName("an empty, zero or failing answer falls through to the next provider")
    void fallsThrough() {
        Prices.register(shop, only(Material.DIRT, "2", "shop"), 100);
        Prices.register(shop, stack -> Optional.of(new PriceQuote(BigDecimal.ZERO, "", "free")), 50);
        Prices.register(shop, stack -> { throw new IllegalStateException("broken"); }, 10);
        Prices.register(worth, only(Material.STONE, "1", "worth"));
        assertEquals("worth", Prices.sell(STONE).orElseThrow().provider());
        assertEquals("shop", Prices.sell(DIRT).orElseThrow().provider());
    }

    @Test
    @DisplayName("equal priorities answer in registration order")
    void ties() {
        Prices.register(shop, only(Material.STONE, "2", "first"));
        Prices.register(worth, only(Material.STONE, "1", "second"));
        assertEquals("first", Prices.sell(STONE).orElseThrow().provider());
    }

    @Test
    @DisplayName("a lot is divided once: three of a lot of three are the lot, never 0.99")
    void lots() {
        PriceQuote quote = new PriceQuote(BigDecimal.ONE, 3, "", "shop");
        assertEquals(new BigDecimal("1.00"), quote.total(3));
        assertEquals(new BigDecimal("0.33"), quote.each());
        assertEquals(new BigDecimal("21.33"), quote.total(64));
        Prices.register(shop, stack -> Optional.of(quote));
        assertEquals(new BigDecimal("21.33"), Prices.sell(STONE, 64).orElseThrow());
    }

    @Test
    @DisplayName("released with its plugin, or unregistered on its own")
    void lifecycle() {
        PriceProvider shopStone = only(Material.STONE, "2", "shop");
        Prices.register(shop, shopStone, 100);
        Prices.register(worth, only(Material.STONE, "1", "worth"));
        Prices.unregister(shopStone);
        assertEquals("worth", Prices.sell(STONE).orElseThrow().provider());
        PriceRuntime.release("Worth");
        assertFalse(Prices.sellable(STONE));
    }

    @Test
    @DisplayName("buy asks only the providers that sell")
    void buy() {
        Prices.register(shop, new PriceProvider() {
            @Override
            public @NotNull Optional<PriceQuote> sell(@NotNull ItemStack stack) {
                return Optional.empty();
            }

            @Override
            public @NotNull Optional<PriceQuote> buy(@NotNull ItemStack stack) {
                return Optional.of(new PriceQuote(new BigDecimal("6"), "", "shop"));
            }
        });
        assertEquals(new BigDecimal("6"), Prices.buy(STONE).orElseThrow().price());
        assertFalse(Prices.sellable(STONE));
    }
}
