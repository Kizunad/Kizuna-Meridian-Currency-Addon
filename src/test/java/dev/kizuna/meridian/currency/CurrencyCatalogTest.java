package dev.kizuna.meridian.currency;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import static org.junit.Assert.*;

public final class CurrencyCatalogTest {
    private static final long CRYSTAL_RATE = 7;
    private static final long CRYSTAL_UNITS = 5;

    @Test
    public void extraSettlementGroupNeedsOnlyItsOwnCatalog() {
        var gem = JsonParser.parseString("""
                {"currencies":[{"id":"fixture:spirit","settlement_group":"fixture:crystals","rate":7}],
                 "denominations":[{"item":"fixture:shard","currency_id":"fixture:spirit","units":5,"stack_limit":16}],
                 "exchanges":[]}
                """).getAsJsonObject();
        var catalog = new CurrencyCatalog(List.of(base(), gem));
        var shard = catalog.denomination(Identifier.parse("fixture:shard"));
        assertEquals(CRYSTAL_RATE * CRYSTAL_UNITS, shard.value());
        var copper = catalog.currency(Identifier.parse("meridian_currency:copper"));
        assertNotEquals(copper.group(), shard.currency().group());
    }

    @Test
    public void invalidRatesAndReferencesFailDuringCatalogLoad() {
        var invalid = base();
        var row = invalid.getAsJsonArray("currencies").get(0).getAsJsonObject();
        row.addProperty("rate", 0);
        assertThrows(IllegalArgumentException.class, () -> new CurrencyCatalog(List.of(invalid)));
        row.addProperty("rate", "1");
        assertThrows(IllegalArgumentException.class, () -> new CurrencyCatalog(List.of(invalid)));
        row.addProperty("rate", Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> new CurrencyCatalog(List.of(invalid)));
        var dangling = base();
        dangling.getAsJsonArray("denominations").get(0).getAsJsonObject().addProperty("currency_id", "test:missing");
        assertThrows(IllegalArgumentException.class, () -> new CurrencyCatalog(List.of(dangling)));
    }

    @Test
    public void priceCodecPreservesExactIntegerUnits() {
        var row = new JsonObject();
        row.addProperty("currency_id", "meridian_currency:copper");
        row.addProperty("units", Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, Price.CODEC.parse(JsonOps.INSTANCE, row).getOrThrow().units());
        for (var invalid : List.of("true", "0", "-1", "1.5", "9223372036854775808")) {
            row.add("units", JsonParser.parseString(invalid));
            assertTrue(Price.CODEC.parse(JsonOps.INSTANCE, row).error().isPresent());
        }
    }

    @Test
    public void priceRejectsZeroUnknownCurrencyAndOverflow() {
        var catalog = new CurrencyCatalog(List.of(base()));
        var silver = Identifier.parse("meridian_currency:silver");
        assertThrows(IllegalArgumentException.class, () -> new Price(silver, 0));
        assertThrows(ArithmeticException.class, () -> catalog.value(new Price(silver, Long.MAX_VALUE)));
        var unknown = new Price(Identifier.parse("test:missing"), 1);
        assertThrows(IllegalArgumentException.class, () -> catalog.value(unknown));
    }

    private static JsonObject base() {
        var stream = CurrencyCatalogTest.class.getResourceAsStream("/data/meridian_currency/currency.json");
        try (var reader = new InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }
}
