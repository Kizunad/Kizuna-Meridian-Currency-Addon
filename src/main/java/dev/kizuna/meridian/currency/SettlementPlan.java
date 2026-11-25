package dev.kizuna.meridian.currency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/** 先在双方副本里腾位，再归并实物，找零和货物都占用真实槽位。 */
record SettlementPlan(List<ItemStack> payer, List<ItemStack> payee, List<CurrencyCatalog.Bundle> debit,
                      List<CurrencyCatalog.Bundle> change) {
    record Wallets(List<ItemStack> payer, List<ItemStack> payee) {}
    private enum Side { PAYER, PAYEE }
    private record Pool(ItemStack sample, int available) {}
    private record Demand(Side side, int count, List<Pool> pools) {}
    private record Pick(Side side, ItemStack sample, int count) {}

    static List<ItemStack> copy(List<ItemStack> slots) {
        return new ArrayList<>(slots.stream().map(ItemStack::copy).toList());
    }

    static SettlementPlan transfer(Wallets wallets, List<CurrencyCatalog.Bundle> debit,
                                   List<CurrencyCatalog.Bundle> change, ItemStack goods) {
        var payee = copy(wallets.payee());
        var delivery = new ArrayList<ItemStack>();
        if (!goods.isEmpty()) {
            delivery.addAll(take(payee, stack -> ItemStack.isSameItemSameComponents(stack, goods), goods.getCount()));
        }
        var demands = new ArrayList<Demand>();
        addDemands(demands, wallets.payer(), debit, Side.PAYER);
        addDemands(demands, payee, change, Side.PAYEE);
        var packing = new Packing(new Wallets(wallets.payer(), payee), demands, delivery);
        var result = packing.nextDemand(0);
        if (result == null) {
            throw new TradeRejected(TradeRejected.Reason.SPACE);
        }
        return new SettlementPlan(result.payer(), result.payee(), List.copyOf(debit), List.copyOf(change));
    }

    private static void addDemands(List<Demand> demands, List<ItemStack> slots,
                                   List<CurrencyCatalog.Bundle> bundles, Side side) {
        for (var bundle : bundles) {
            var item = BuiltInRegistries.ITEM.getOptional(bundle.item()).orElseThrow();
            var pools = new ArrayList<Pool>();
            for (var stack : slots) {
                if (stack.is(item)) {
                    addToPool(pools, stack);
                }
            }
            if (pools.stream().mapToInt(Pool::available).sum() < bundle.count()) {
                throw new TradeRejected(TradeRejected.Reason.GOODS);
            }
            demands.add(new Demand(side, bundle.count(), pools));
        }
    }

    private static void addToPool(List<Pool> pools, ItemStack stack) {
        // 刻字等组件影响合栈能力；报价要能改选收款方已有的同组件铜钱。
        for (int i = 0; i < pools.size(); i++) {
            var pool = pools.get(i);
            if (ItemStack.isSameItemSameComponents(pool.sample(), stack)) {
                pools.set(i, new Pool(pool.sample(), Math.addExact(pool.available(), stack.getCount())));
                return;
            }
        }
        pools.add(new Pool(stack, stack.getCount()));
    }

    private record Packing(Wallets base, List<Demand> demands, List<ItemStack> goods, List<Pick> picks) {
        Packing(Wallets base, List<Demand> demands, List<ItemStack> goods) {
            this(base, demands, goods, new ArrayList<>());
        }

        Wallets nextDemand(int index) {
            if (index == demands.size()) {
                return fit();
            }
            return select(index, 0, demands.get(index).count());
        }

        private Wallets select(int index, int variant, int remaining) {
            if (remaining == 0) {
                return nextDemand(index + 1);
            }
            var demand = demands.get(index);
            var pools = demand.pools();
            if (variant == pools.size()) {
                return null;
            }
            var pool = pools.get(variant);
            int rest = pools.subList(variant + 1, pools.size()).stream().mapToInt(Pool::available).sum();
            int minimum = Math.max(0, remaining - rest);
            for (int count = Math.min(remaining, pool.available()); count >= minimum; count--) {
                picks.add(new Pick(demand.side(), pool.sample(), count));
                var result = select(index, variant + 1, remaining - count);
                picks.removeLast();
                if (result != null) {
                    return result;
                }
            }
            return null;
        }

        private Wallets fit() {
            var payer = copy(base.payer());
            var payee = copy(base.payee());
            var payment = new ArrayList<ItemStack>();
            var refund = new ArrayList<ItemStack>();
            for (var pick : picks) {
                if (pick.count() == 0) {
                    continue;
                }
                Predicate<ItemStack> matches = stack -> ItemStack.isSameItemSameComponents(stack, pick.sample());
                if (pick.side() == Side.PAYER) {
                    payment.addAll(take(payer, matches, pick.count()));
                } else {
                    refund.addAll(take(payee, matches, pick.count()));
                }
            }
            try {
                insert(payer, refund);
                insert(payer, copy(goods));
                insert(payee, payment);
                return new Wallets(payer, payee);
            } catch (TradeRejected noSpace) {
                return null;
            }
        }
    }

    static int count(List<ItemStack> slots, Identifier id) {
        var item = BuiltInRegistries.ITEM.getOptional(id).orElseThrow();
        return slots.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).reduce(0, Math::addExact);
    }

    private static List<ItemStack> take(List<ItemStack> slots, Predicate<ItemStack> matches, int count) {
        // 优先取小栈；同物品带不同组件时仍按各自实物交割。
        var indices = IntStream.range(0, slots.size()).boxed()
                .filter(index -> matches.test(slots.get(index)))
                .sorted(Comparator.comparingInt(index -> slots.get(index).getCount()))
                .toList();
        var taken = new ArrayList<ItemStack>();
        int remaining = count;
        for (int index : indices) {
            var stack = slots.get(index);
            var part = stack.split(Math.min(remaining, stack.getCount()));
            remaining -= part.getCount();
            taken.add(part);
            if (remaining == 0) {
                return taken;
            }
        }
        throw new TradeRejected(TradeRejected.Reason.GOODS);
    }

    private static void insert(List<ItemStack> slots, List<ItemStack> incoming) {
        for (var stack : incoming) {
            for (var existing : slots) {
                if (ItemStack.isSameItemSameComponents(existing, stack)) {
                    int moved = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
                    existing.grow(moved);
                    stack.shrink(moved);
                    if (stack.isEmpty()) {
                        break;
                    }
                }
            }
            if (!stack.isEmpty()) {
                int empty = IntStream.range(0, slots.size()).filter(i -> slots.get(i).isEmpty()).findFirst()
                        .orElseThrow(() -> new TradeRejected(TradeRejected.Reason.SPACE));
                slots.set(empty, stack);
            }
        }
    }
}
