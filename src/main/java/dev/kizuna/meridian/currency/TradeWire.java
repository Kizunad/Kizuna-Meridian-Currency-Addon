package dev.kizuna.meridian.currency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 客户端只发送选货意图或原报价凭据；金额、实物与提示均由服务端投影。 */
public final class TradeWire {
    public static final int INVALIDATED = -1;

    private TradeWire() {}

    public record Row(Identifier id, ItemStack goods, Component price, int available, String countFormat) {
        static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Row::id, ItemStack.STREAM_CODEC, Row::goods,
                ComponentSerialization.STREAM_CODEC, Row::price, ByteBufCodecs.VAR_INT, Row::available,
                ByteBufCodecs.STRING_UTF8, Row::countFormat, Row::new);
    }

    public record Coin(Identifier item, int count) {
        static final StreamCodec<RegistryFriendlyByteBuf, Coin> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Coin::item, ByteBufCodecs.VAR_INT, Coin::count, Coin::new);
    }

    public record Stock(long version, List<Row> buy, List<Row> sell, List<Coin> wallet, Component balance) {
        static final StreamCodec<RegistryFriendlyByteBuf, Stock> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Stock::version, Row.CODEC.apply(ByteBufCodecs.list()), Stock::buy,
                Row.CODEC.apply(ByteBufCodecs.list()), Stock::sell,
                Coin.CODEC.apply(ByteBufCodecs.list()), Stock::wallet,
                ComponentSerialization.STREAM_CODEC, Stock::balance, Stock::new);
    }

    public record Open(UUID session, Component merchant, Component greeting, Stock stock)
            implements CustomPacketPayload {
        public static final Type<Open> TYPE = typeId("trade_open");
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Open::session, ComponentSerialization.STREAM_CODEC, Open::merchant,
                ComponentSerialization.STREAM_CODEC, Open::greeting, Stock.CODEC, Open::stock, Open::new);
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Pick(UUID session, MerchantService.Selection selection) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = typeId("trade_pick");
        public static final StreamCodec<RegistryFriendlyByteBuf, Pick> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUUID(value.session);
            var choice = value.selection();
            buf.writeVarLong(choice.version());
            buf.writeEnum(choice.page());
            buf.writeVarInt(choice.index());
            buf.writeVarInt(choice.lots());
            buf.writeVarInt(choice.sequence());
        }, buf -> new Pick(buf.readUUID(), new MerchantService.Selection(buf.readVarLong(),
                buf.readEnum(MerchantOffers.Page.class), buf.readVarInt(), buf.readVarInt(), buf.readVarInt())));
        @Override public Type<Pick> type() { return TYPE; }
    }

    public record Accept(UUID session, Identifier branch, int sequence) implements CustomPacketPayload {
        public static final Type<Accept> TYPE = typeId("trade_accept");
        public static final StreamCodec<RegistryFriendlyByteBuf, Accept> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Accept::session, Identifier.STREAM_CODEC, Accept::branch,
                ByteBufCodecs.VAR_INT, Accept::sequence, Accept::new);
        @Override public Type<Accept> type() { return TYPE; }
    }

    public record Close(UUID session) implements CustomPacketPayload {
        public static final Type<Close> TYPE = typeId("trade_close");
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC =
                UUIDUtil.STREAM_CODEC.map(Close::new, Close::session).cast();
        @Override public Type<Close> type() { return TYPE; }
    }

    public record Bill(Identifier branch, Component total, List<Coin> debit, List<Coin> change) {
        static final StreamCodec<RegistryFriendlyByteBuf, Bill> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, Bill::branch, ComponentSerialization.STREAM_CODEC, Bill::total,
                Coin.CODEC.apply(ByteBufCodecs.list()), Bill::debit, Coin.CODEC.apply(ByteBufCodecs.list()),
                Bill::change, Bill::new);
    }

    public record Reply(UUID session, int sequence, Component notice, Optional<Bill> bill, Stock stock)
            implements CustomPacketPayload {
        public static final Type<Reply> TYPE = typeId("trade_reply");
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Reply::session, ByteBufCodecs.VAR_INT, Reply::sequence,
                ComponentSerialization.STREAM_CODEC, Reply::notice, ByteBufCodecs.optional(Bill.CODEC),
                Reply::bill, Stock.CODEC, Reply::stock, Reply::new);
        @Override public Type<Reply> type() { return TYPE; }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeId(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("meridian_currency", path));
    }
}
