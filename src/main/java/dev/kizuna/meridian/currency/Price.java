package dev.kizuna.meridian.currency;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/** units 是本币最小单位，结算组与换算率由目录决定。 */
public record Price(Identifier currencyId, long units) {
    private static final Codec<Long> UNITS = Codec.PASSTHROUGH.comapFlatMap(Price::readUnits,
            units -> new Dynamic<>(JsonOps.INSTANCE, new JsonPrimitive(units)));
    public static final Codec<Price> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("currency_id").forGetter(Price::currencyId),
            UNITS.fieldOf("units").forGetter(Price::units)
    ).apply(instance, Price::new));

    public Price {
        Objects.requireNonNull(currencyId);
        if (units <= 0) {
            throw new IllegalArgumentException("金额必须是正整数");
        }
    }

    private static DataResult<Long> readUnits(Dynamic<?> field) {
        // 拒绝小数和超范围输入，普通 long 解码会直接截断部分数字。
        var value = field.convert(JsonOps.INSTANCE).getValue();
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return DataResult.error(() -> "金额需要整数");
        }
        try {
            long units = value.getAsBigDecimal().longValueExact();
            if (units > 0) {
                return DataResult.success(units);
            }
        } catch (ArithmeticException invalid) {
            return DataResult.error(() -> "金额超出整数范围");
        }
        return DataResult.error(() -> "金额必须为正");
    }
}
