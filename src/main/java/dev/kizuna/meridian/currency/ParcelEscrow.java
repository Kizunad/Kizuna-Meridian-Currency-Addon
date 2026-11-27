package dev.kizuna.meridian.currency;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.kizuna.meridian.inventory.MobInventory;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** 实物货款随成交回执封存。拆封和补签由原订单号派生，独立于交易实体的寿命。 */
public final class ParcelEscrow {
    public enum Contents { SILVER, PAPER }

    public record Seal(ItemStack wrapper, CurrencyCatalog.Bundle payment, Contents contents, long dawn) {
        public Seal {
            if (wrapper.isEmpty()) {
                throw new IllegalArgumentException("封包不能为空");
            }
            wrapper = wrapper.copyWithCount(1);
            Objects.requireNonNull(payment);
            Objects.requireNonNull(contents);
        }

        @Override
        public ItemStack wrapper() {
            return wrapper.copy();
        }
    }

    public record Offer(UUID order, ItemStack goods, Seal seal) {
        public Offer {
            if (goods.isEmpty()) {
                throw new IllegalArgumentException("货物不能为空");
            }
            goods = goods.copy();
            Objects.requireNonNull(order);
            Objects.requireNonNull(seal);
        }

        @Override
        public ItemStack goods() {
            return goods.copy();
        }
    }

    private record Payment(String denomination, int count, Contents contents, long dawn) { }
    private record Terms(UUID order, UUID merchant, JsonElement goods, JsonElement wrapper, Payment payment) { }

    private record Deposit(Terms terms, List<JsonElement> silver) { }

    private static final Gson JSON = new Gson();
    private static final long DAY = 24_000;
    private final CurrencyService service;
    private final MinecraftServer server;

    ParcelEscrow(CurrencyService service, MinecraftServer server) {
        this.service = service;
        this.server = server;
    }

    private static UUID key(UUID id, String action, int sequence) {
        return CurrencyService.operationId(id, id, Identifier.fromNamespaceAndPath("meridian_currency", action),
                sequence);
    }

    public static long nextDawn(long now) {
        return Math.multiplyExact(Math.incrementExact(Math.floorDiv(now, DAY)), DAY);
    }

    /** 邀请出现前试放货、包与足额原币；场次、在场距离与夜间开放时段由柜台裁定。 */
    public void preview(ServerPlayer player, Mob merchant, Offer offer) {
        // 完整模拟两侧槽位及托管原银；缺货或放不下时抛 TradeRejected。
        deposit(player, merchant, offer);
    }

    public CurrencyService.Receipt sell(ServerPlayer player, Mob merchant, Offer offer) {
        var existing = service.receipt(offer.order());
        if (existing.isPresent()) {
            var stored = read(player, offer.order()).terms();
            if (!stored.equals(terms(offer, merchant))) {
                throw new TradeRejected(TradeRejected.Reason.OPERATION_CONFLICT);
            }
            if (merchant.isAlive() && !merchant.isRemoved()) {
                service.merchant(merchant);
            }
            return existing.get();
        }
        return service.settle(offer.order(), deposit(player, merchant, offer));
    }

    private Terms terms(Offer offer, Mob merchant) {
        var seal = offer.seal();
        var bundle = seal.payment();
        var payment = new Payment(bundle.item().toString(), bundle.count(), seal.contents(), seal.dawn());
        return new Terms(offer.order(), merchant.getUUID(), service.snapshot(offer.goods()),
                service.snapshot(seal.wrapper()), payment);
    }

    private CurrencyService.Quote deposit(ServerPlayer player, Mob mob, Offer offer) {
        var account = service.merchant(mob);
        var terms = terms(offer, mob);
        var payment = terms.payment();
        if (payment.dawn() != nextDawn(server.overworld().getOverworldClockTime())) {
            throw stale();
        }
        var coin = CurrencyMod.catalog().denomination(offer.seal().payment().item());
        var item = BuiltInRegistries.ITEM.getOptional(coin.item()).orElseThrow();
        var wrapperId = BuiltInRegistries.ITEM.getKey(offer.seal().wrapper().getItem());
        if (CurrencyMod.catalog().denominations().stream().anyMatch(row -> row.item().equals(wrapperId))) {
            throw new IllegalArgumentException("封包不能使用货币物品");
        }
        return service.transfer(service.player(player), account, slots -> {
            var goods = SettlementPlan.takeExact(slots.payer(), offer.goods());
            var silver = SettlementPlan.take(slots.payee(), stack -> stack.is(item), payment.count());
            SettlementPlan.insert(slots.payee(), goods);
            SettlementPlan.insert(slots.payer(), List.of(parcel(terms, 0)));
            var deposit = new Deposit(terms, silver.stream().map(service::snapshot).toList());
            return new CurrencyService.Order("parcel_sale", payment.denomination(), "", payment.count(),
                    JSON.toJson(deposit));
        });
    }

    private Deposit read(ServerPlayer player, UUID order) {
        var sale = service.receipt(order).orElseThrow(ParcelEscrow::stale);
        if (!sale.order().kind().equals("parcel_sale") || !sale.parties().payer().equals(service.player(player).id())) {
            throw stale();
        }
        return JSON.fromJson(sale.order().goods(), Deposit.class);
    }

    private static TradeRejected stale() {
        return new TradeRejected(TradeRejected.Reason.STALE);
    }

    private ItemStack parcel(Terms terms, int serial) {
        var stack = service.decode(terms.wrapper());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString("meridian_currency_order", terms.order().toString());
            tag.putString("meridian_currency_seal", Integer.toString(serial));
        });
        return stack;
    }

    private void valid(UUID order, int serial) {
        if (serial < 0 || serial == Integer.MAX_VALUE
                || serial > 0 && service.receipt(key(order, "parcel_reissue", serial)).isEmpty()
                || service.receipt(key(order, "parcel_reissue", serial + 1)).isPresent()) {
            throw stale();
        }
    }

    /** 下一序号作为补领操作号。旧包失效，重试同一序号返回同一笔。 */
    public CurrencyService.Receipt reissue(ServerPlayer player, UUID order, int nextSerial) {
        var deposit = read(player, order);
        if (nextSerial <= 0 || nextSerial == Integer.MAX_VALUE
                || service.receipt(key(order, "parcel_open", 0)).isPresent()) {
            throw stale();
        }
        var operation = key(order, "parcel_reissue", nextSerial);
        var issued = service.receipt(operation);
        if (issued.isPresent()) {
            return issued.get();
        }
        valid(order, nextSerial - 1);
        var terms = deposit.terms();
        var account = service.player(player);
        var previous = parcel(terms, nextSerial - 1);
        if (account.contents().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, previous))) {
            throw stale();
        }
        var quote = service.transfer(account, estate(terms.merchant()), slots -> {
            SettlementPlan.insert(slots.payer(), List.of(parcel(terms, nextSerial)));
            return action("parcel_reissue", order);
        });
        return service.settle(operation, quote);
    }

    public CurrencyService.Receipt open(ServerPlayer player, UUID order, int serial) {
        var deposit = read(player, order);
        var terms = deposit.terms();
        var operation = key(order, "parcel_open", 0);
        var opened = service.receipt(operation);
        if (opened.isPresent()) {
            if (serial != opened.get().order().units()) {
                throw stale();
            }
            return opened.get();
        }
        valid(order, serial);
        if (server.overworld().getOverworldClockTime() < terms.payment().dawn()) {
            throw stale();
        }
        var account = service.player(player);
        var recipient = beneficiary(terms.merchant());
        var opening = new CurrencyService.Order("parcel_open", order.toString(), "", serial, "");
        CurrencyService.Quote quote;
        try {
            quote = redemption(account, recipient, opening, deposit);
        } catch (TradeRejected failure) {
            if (failure.reason() != TradeRejected.Reason.SPACE || !(recipient instanceof CurrencyAccount.MobAccount)) {
                throw failure;
            }
            quote = redemption(account, estate(terms.merchant()), opening, deposit);
        }
        return service.settle(operation, quote);
    }

    private CurrencyService.Quote redemption(CurrencyAccount player, CurrencyAccount recipient,
                                            CurrencyService.Order opening, Deposit deposit) {
        var terms = deposit.terms();
        var token = parcel(terms, Math.toIntExact(opening.units()));
        return service.transfer(player, recipient, slots -> {
            SettlementPlan.takeExact(slots.payer(), token);
            var silver = deposit.silver().stream().map(service::decode).toList();
            if (terms.payment().contents() == Contents.SILVER) {
                SettlementPlan.insert(slots.payer(), silver);
            } else {
                SettlementPlan.insert(slots.payee(), silver);
                SettlementPlan.insert(slots.payer(), List.of(new ItemStack(Items.PAPER)));
            }
            return opening;
        });
    }

    private CurrencyAccount beneficiary(UUID merchant) {
        for (var level : server.getAllLevels()) {
            if (level.getEntity(merchant) instanceof Mob mob && mob.isAlive() && !service.isParked(merchant)) {
                return service.merchant(mob);
            }
        }
        return estate(merchant);
    }

    private CurrencyAccount estate(UUID merchant) {
        return service.openTill(key(merchant, "merchant_estate", 0),
                Collections.nCopies(MobInventory.SIZE, ItemStack.EMPTY));
    }

    /** 撤场先交存真实槽位，成功后调用方才移除实体。visit 在一次到访及其所有重试中固定。 */
    public void depart(Mob mob, UUID visit) {
        var operation = custodyId(mob, visit, "merchant_depart");
        var pack = service.merchant(mob);
        var archive = estate(mob.getUUID());
        if (service.receipt(operation).isEmpty()) {
            moveInventory(operation, action("merchant_depart", visit), pack, archive);
        }
    }

    /** 开柜及重绑时调用，退场期间退回的款项仍然属于这个 UUID 的背囊。 */
    public void arrive(Mob mob, UUID visit) {
        if (service.receipt(custodyId(mob, visit, "merchant_depart")).isPresent()) {
            throw stale();
        }
        var operation = custodyId(mob, visit, "merchant_arrive");
        var archive = estate(mob.getUUID());
        var pack = service.merchant(mob);
        if (service.receipt(operation).isEmpty()) {
            moveInventory(operation, action("merchant_arrive", visit), archive, pack);
        } else if (archive.contents().stream().anyMatch(stack -> !stack.isEmpty())) {
            String claim = "merchant_refund/" + archive.watermark();
            moveInventory(custodyId(mob, visit, claim), action("merchant_refund", visit), archive, pack);
        }
    }

    private static UUID custodyId(Mob mob, UUID visit, String kind) {
        var branch = Identifier.fromNamespaceAndPath("meridian_currency", kind);
        return CurrencyService.operationId(mob.getUUID(), visit, branch, 0);
    }

    private void moveInventory(UUID operation, CurrencyService.Order order, CurrencyAccount from, CurrencyAccount to) {
        var quote = service.transfer(from, to, slots -> {
            var stock = slots.payer().stream().filter(stack -> !stack.isEmpty()).toList();
            SettlementPlan.insert(slots.payee(), stock);
            Collections.fill(slots.payer(), ItemStack.EMPTY);
            return order;
        });
        service.settle(operation, quote);
    }

    private static CurrencyService.Order action(String kind, UUID order) {
        return new CurrencyService.Order(kind, order.toString(), "", 1, "");
    }
}
