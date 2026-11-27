package dev.kizuna.meridian.currency;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import dev.kizuna.meridian.inventory.MobInventories;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

/** 调用方在服务端权限与事件条件通过后报价，重试时始终使用同一业务操作号。 */
public final class CurrencyService {
    public record Parties(String payer, String payee) {}
    public record Order(String kind, String source, String target, long units, String goods) {}
    public record Movement(Map<String, Integer> debit, Map<String, Integer> change) {
        public Movement {
            debit = Map.copyOf(debit);
            change = Map.copyOf(change);
        }
    }
    public record Receipt(UUID operationId, long revision, Parties parties, Order order, Movement money) {}

    private record View(CurrencyAccount account, long sequence, int edits, List<ItemStack> slots) {
        static View read(CurrencyAccount account) {
            return new View(account, account.watermark(), account.edits(), account.contents());
        }

        void validate() {
            // 玩家可能通过原版拖动或丢弃物品；除流水号还核对背包版本及原栈组件。
            if (account.watermark() != sequence || account.edits() != edits) {
                throw new TradeRejected(TradeRejected.Reason.STALE);
            }
            var now = account.contents();
            for (int slot = 0; slot < slots.size(); slot++) {
                if (!ItemStack.matches(slots.get(slot), now.get(slot))) {
                    throw new TradeRejected(TradeRejected.Reason.STALE);
                }
            }
        }
    }

    public static final class Quote {
        private final View payer;
        private final View payee;
        private final SettlementPlan plan;
        private final Order order;
        private final Movement money;

        private Quote(View payer, View payee, SettlementPlan plan, Order order) {
            this.payer = payer;
            this.payee = payee;
            this.plan = plan;
            this.order = order;
            this.money = new Movement(counts(plan.debit()), counts(plan.change()));
        }

        public Movement money() {
            return money;
        }

        public Parties parties() {
            return new Parties(payer.account().id(), payee.account().id());
        }

        public Order order() {
            return order;
        }
    }

    private static final Map<MinecraftServer, CurrencyService> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final DynamicOps<JsonElement> ops;
    private final DynamicOps<Tag> nbtOps;
    private final CurrencyJournal journal;
    private final MerchantService merchants;
    private final Map<UUID, CurrencyAccount> tills = new HashMap<>();

    CurrencyService(MinecraftServer server, Path directory) {
        this.server = server;
        this.ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        this.nbtOps = server.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        this.journal = new CurrencyJournal(directory);
        this.merchants = new MerchantService(server, this, directory);
    }

    public ParcelEscrow parcels() {
        requireThread(server);
        return new ParcelEscrow(this, server);
    }

    public MerchantService merchants() {
        requireThread(server);
        return merchants;
    }

    public static CurrencyService get(MinecraftServer server) {
        requireThread(server);
        return SERVICES.computeIfAbsent(server, host -> new CurrencyService(host,
                host.getWorldPath(LevelResource.ROOT).resolve("meridian_currency")));
    }

    static void close(MinecraftServer server) {
        SERVICES.remove(server);
    }

    public static UUID operationId(UUID actor, UUID event, Identifier branch, int delivery) {
        if (delivery < 0) {
            throw new IllegalArgumentException("交付序号不能为负");
        }
        String key = String.join("|", actor.toString(), event.toString(), branch.toString(),
                Integer.toString(delivery));
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public CurrencyAccount player(ServerPlayer player) {
        requireThread(server);
        if (player.level().getServer() != server) {
            throw new IllegalArgumentException("玩家不在当前服务器");
        }
        var account = new CurrencyAccount.PlayerAccount(this, player);
        recover(account);
        return account;
    }

    public CurrencyAccount merchant(Mob mob) {
        requireThread(server);
        if (mob.level().getServer() != server || !MobInventories.state(mob).available() || !mob.isAlive()) {
            throw new TradeRejected(TradeRejected.Reason.STALE);
        }
        MobInventories.open(mob);
        return recoveredMobAccount(mob);
    }

    private CurrencyAccount recoveredMobAccount(Mob mob) {
        var account = new CurrencyAccount.MobAccount(this, mob);
        recover(account);
        return account;
    }

    void observe(Mob mob, ServerLevel level, PurseTable table) {
        var account = recoveredMobAccount(mob);
        if (mob.hasAttached(MobPurses.ISSUED)) {
            return;
        }
        if (account.watermark() > 0) {
            mob.setAttached(MobPurses.ISSUED, true /* value */);
            return;
        }
        List<ItemStack> stacks;
        var id = mob.getUUID();
        if (mob.getType() == EntityTypes.WANDERING_TRADER && journal.hasOpening(id)) {
            // 旧版到访已有交易流水，搬回扣除已售货物、计入实收钱款后的余货。
            var old = openTill(id);
            stacks = old.contents();
            tills.remove(id);
        } else {
            stacks = table.generate(mob);
        }
        var pack = MobInventories.inventory(mob);
        for (var stack : stacks) {
            pack.insert(stack);
            if (!stack.isEmpty()) {
                mob.spawnAtLocation(level, stack);
            }
        }
        mob.setAttached(MobPurses.ISSUED, true /* value */);
    }

    /** 初始化时给出各格实物；同一钱匣再次打开只读取原库存和之后的收付。 */
    public CurrencyAccount openTill(UUID id, List<ItemStack> opening) {
        requireThread(server);
        return tills.computeIfAbsent(id, key -> {
            var seed = opening.stream().map(this::openingSlot).toList();
            var slots = journal.openTill(key, seed).stream().map(this::decode).toList();
            var till = new CurrencyAccount.Till(this, "till/" + key, slots);
            recover(till);
            return till;
        });
    }

    public CurrencyAccount openTill(UUID id) {
        return openTill(id, List.of());
    }

    boolean isParked(UUID merchant) {
        var history = journal.historySince("mob/" + merchant, 0);
        for (var entry : history.reversed()) {
            String kind = entry.receipt().order().kind();
            if (kind.equals("merchant_arrive") || kind.equals("merchant_depart")) {
                return kind.equals("merchant_depart");
            }
        }
        return false;
    }

    public Quote quote(CurrencyAccount payer, CurrencyAccount payee, Price price) {
        return quote(payer, payee, price, ItemStack.EMPTY);
    }

    public Quote quote(CurrencyAccount payer, CurrencyAccount payee, Price price, ItemStack goods) {
        prepare(payer, payee);
        var buyer = View.read(payer);
        var seller = View.read(payee);
        var catalog = CurrencyMod.catalog();
        var currency = catalog.currency(price.currencyId());
        long amount = catalog.value(price);
        var wallets = new SettlementPlan.Wallets(buyer.slots(), seller.slots());
        var planner = new PaymentPlanner(wallets, currency, goods);
        var plan = planner.plan(amount);
        String delivery = GsonHelper.toStableString(encode(goods));
        var order = new Order("sale", currency.id().toString(), "", price.units(), delivery);
        return new Quote(buyer, seller, plan, order);
    }

    /** 双方以指定组件和件数的实物互换；确认时沿用相同的流水和库存版本校验。 */
    public Quote barter(CurrencyAccount from, CurrencyAccount to, ItemStack tender, ItemStack goods) {
        if (tender.isEmpty() || goods.isEmpty()) {
            throw new IllegalArgumentException("物物交换双方须有实物");
        }
        String offered = GsonHelper.toStableString(snapshot(tender));
        String requested = GsonHelper.toStableString(snapshot(goods));
        var order = new Order("barter", offered, "", 1, requested);
        return transfer(from, to, slots -> {
            var payment = SettlementPlan.takeExact(slots.payer(), tender);
            var delivery = SettlementPlan.takeExact(slots.payee(), goods);
            SettlementPlan.insert(slots.payer(), delivery);
            SettlementPlan.insert(slots.payee(), payment);
            return order;
        });
    }

    Quote transfer(CurrencyAccount from, CurrencyAccount to, Function<SettlementPlan.Wallets, Order> exchange) {
        prepare(from, to);
        var payer = View.read(from);
        var payee = View.read(to);
        var slots = new SettlementPlan.Wallets(SettlementPlan.copy(payer.slots()), SettlementPlan.copy(payee.slots()));
        var order = exchange.apply(slots);
        var plan = new SettlementPlan(slots.payer(), slots.payee(), List.of(), List.of());
        return new Quote(payer, payee, plan, order);
    }

    public Quote exchange(CurrencyAccount customer, CurrencyAccount till, Identifier input,
                          Identifier output, int lots) {
        prepare(customer, till);
        if (lots <= 0) {
            throw new IllegalArgumentException("拆兑份数必须为正");
        }
        var rule = CurrencyMod.catalog().exchange(input, output);
        var paid = multiply(rule.input(), lots);
        var received = multiply(rule.output(), lots);
        var payer = View.read(customer);
        var payee = View.read(till);
        var wallets = new SettlementPlan.Wallets(payer.slots(), payee.slots());
        var plan = SettlementPlan.transfer(wallets, List.of(paid), List.of(received), ItemStack.EMPTY);
        var order = new Order("exchange", input.toString(), output.toString(), lots, "");
        return new Quote(payer, payee, plan, order);
    }

    public Optional<Receipt> receipt(UUID operationId) {
        requireThread(server);
        var entry = journal.find(Objects.requireNonNull(operationId));
        return Optional.ofNullable(entry).map(CurrencyJournal.Entry::receipt);
    }

    public Receipt settle(UUID operationId, Quote quote) {
        var entry = commit(operationId, quote);
        // 钱货与回执已同时决定。各侧按自己的水位应用，登录或钱匣重开可接续。
        recover(quote.payer.account());
        recover(quote.payee.account());
        return entry.receipt();
    }

    CurrencyJournal.Entry commit(UUID operationId, Quote quote) {
        Objects.requireNonNull(operationId);
        requireThread(server);
        var payer = quote.payer;
        var payee = quote.payee;
        validateAccount(payer.account());
        validateAccount(payee.account());
        var existing = journal.find(operationId);
        if (existing != null) {
            var receipt = existing.receipt();
            if (!receipt.parties().equals(quote.parties()) || !receipt.order().equals(quote.order)) {
                throw new TradeRejected(TradeRejected.Reason.OPERATION_CONFLICT);
            }
            return existing;
        }

        payer.validate();
        payee.validate();
        var receipt = new Receipt(operationId, journal.next(), quote.parties(), quote.order, quote.money);
        var debit = image(payer.account(), quote.plan.payer());
        var credit = image(payee.account(), quote.plan.payee());
        var entry = new CurrencyJournal.Entry(receipt, debit, credit);
        journal.append(entry);
        return entry;
    }

    void recover(CurrencyAccount account) {
        for (var entry : journal.historySince(account.id(), account.watermark())) {
            var receipt = entry.receipt();
            var slots = entry.payee();
            if (receipt.parties().payer().equals(account.id())) {
                slots = entry.payer();
            }
            account.install(receipt.revision(), slots);
        }
    }

    private void prepare(CurrencyAccount payer, CurrencyAccount payee) {
        requireThread(server);
        validateAccount(payer);
        validateAccount(payee);
        if (payer.id().equals(payee.id())) {
            throw new IllegalArgumentException("收付双方必须不同");
        }
        recover(payer);
        recover(payee);
    }

    private void validateAccount(CurrencyAccount account) {
        if (account.owner != this) {
            throw new IllegalArgumentException("账户或报价属于另一个世界或服务实例");
        }
    }

    static void requireThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("货币操作必须在服务端主线程执行");
        }
    }

    private List<CurrencyJournal.Slot> image(CurrencyAccount account, List<ItemStack> after) {
        // 原版背包上次落盘可能早于报价前的移格。包括空位的成交后整幅槽位才能覆盖旧位置。
        var slots = new ArrayList<CurrencyJournal.Slot>(after.size());
        for (int slot = 0; slot < after.size(); slot++) {
            slots.add(new CurrencyJournal.Slot(account.storageSlot(slot), snapshot(after.get(slot))));
        }
        return slots;
    }

    private JsonElement encode(ItemStack stack) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow();
    }

    JsonElement snapshot(ItemStack stack) {
        // JSON 数字不保留 NBT 的整数宽度；带组件的格子按原版有类型的 SNBT 保存。
        if (stack.isEmpty() || stack.getComponentsPatch().isEmpty()) {
            return encode(stack);
        }
        var tag = (CompoundTag) ItemStack.OPTIONAL_CODEC.encodeStart(nbtOps, stack).getOrThrow();
        var image = new JsonObject();
        image.add("stack_snbt", TagParser.FLATTENED_CODEC.encodeStart(JsonOps.INSTANCE, tag).getOrThrow());
        return image;
    }

    private JsonElement openingSlot(ItemStack stack) {
        if (stack.getCount() > stack.getMaxStackSize()) {
            throw new IllegalArgumentException("钱匣初始格超过实物堆叠上限");
        }
        return snapshot(stack);
    }

    ItemStack decode(JsonElement data) {
        if (data instanceof JsonObject object && object.has("stack_snbt")) {
            var tag = TagParser.FLATTENED_CODEC.parse(JsonOps.INSTANCE, object.get("stack_snbt")).getOrThrow();
            return ItemStack.OPTIONAL_CODEC.parse(nbtOps, tag).getOrThrow();
        }
        return ItemStack.OPTIONAL_CODEC.parse(ops, data).getOrThrow();
    }

    private static CurrencyCatalog.Bundle multiply(CurrencyCatalog.Bundle bundle, int lots) {
        return new CurrencyCatalog.Bundle(bundle.item(), Math.multiplyExact(bundle.count(), lots));
    }

    private static Map<String, Integer> counts(List<CurrencyCatalog.Bundle> bundles) {
        var counts = new HashMap<String, Integer>();
        for (var bundle : bundles) {
            counts.put(bundle.item().toString(), bundle.count());
        }
        return counts;
    }
}
