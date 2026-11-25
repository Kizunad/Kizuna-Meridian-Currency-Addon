package dev.kizuna.meridian.currency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** 每种面额的净件数为正表示付款，为负表示用钱匣现有的实物找零。 */
final class PaymentPlanner {
    private record Stock(CurrencyCatalog.Coin coin, int payment, int refund) {}
    private final SettlementPlan.Wallets wallets;
    private final ItemStack goods;
    private final List<Stock> stock;
    private final long[] maximum;
    private final long[] change;
    private final int[] counts;
    private TradeRejected.Reason refusal = TradeRejected.Reason.CHANGE;

    PaymentPlanner(SettlementPlan.Wallets wallets, CurrencyCatalog.Currency currency, ItemStack goods) {
        this.wallets = wallets;
        this.goods = goods;
        this.stock = new ArrayList<>();
        for (var coin : CurrencyMod.catalog().denominations()) {
            if (coin.currency().group().equals(currency.group())) {
                addStock(coin);
            }
        }
        stock.sort(Comparator.comparingLong((Stock row) -> row.coin().value()).reversed());
        int size = stock.size();
        counts = new int[size];
        maximum = new long[size + 1];
        change = new long[size + 1];
        for (int i = size - 1; i >= 0; i--) {
            var row = stock.get(i);
            long value = row.coin().value();
            maximum[i] = Math.addExact(maximum[i + 1], Math.multiplyExact(value, row.payment()));
            change[i] = Math.addExact(change[i + 1], Math.multiplyExact(value, row.refund()));
        }
    }

    private void addStock(CurrencyCatalog.Coin coin) {
        int payment = SettlementPlan.count(wallets.payer(), coin.item());
        int refund = SettlementPlan.count(wallets.payee(), coin.item());
        if (!goods.isEmpty() && goods.is(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(coin.item()).orElseThrow())) {
            refund -= goods.getCount();
        }
        if (payment > 0 || refund > 0) {
            stock.add(new Stock(coin, payment, Math.max(0, refund)));
        }
    }

    SettlementPlan plan(long price) {
        if (maximum[0] < price) {
            throw new TradeRejected(TradeRejected.Reason.FUNDS);
        }
        // 先找刚好付清的组合；受格位或面额约束时再用双方库存求解。
        var exact = search(0, price, 0);
        if (exact != null) {
            return exact;
        }
        var best = search(0, price, change[0]);
        if (best == null) {
            throw new TradeRejected(refusal);
        }
        // 有零钱可找时选实物找零总值最小的一组，避免拿走多余的大锭再等额退钱。
        long low = 1;
        long high = refundValue(best) - 1;
        while (low <= high) {
            long limit = low + ((high - low) >>> 1);
            var candidate = search(0, price, limit);
            if (candidate == null) {
                low = limit + 1;
            } else {
                best = candidate;
                high = refundValue(candidate) - 1;
            }
        }
        return best;
    }

    private long refundValue(SettlementPlan plan) {
        long sum = 0;
        for (var bundle : plan.change()) {
            long value = CurrencyMod.catalog().denomination(bundle.item()).value();
            sum = Math.addExact(sum, Math.multiplyExact(value, bundle.count()));
        }
        return sum;
    }

    private SettlementPlan search(int index, long remaining, long budget) {
        long capacity = Math.min(budget, change[index]);
        if (remaining > maximum[index] || remaining < -capacity) {
            return null;
        }
        if (index == stock.size()) {
            return tryDelivery();
        }
        var row = stock.get(index);
        long value = row.coin().value();
        long available = -Math.min(row.refund(), budget / value);
        long followingChange = Math.min(change[index + 1], budget);
        // 界限夹在可用件数内，避免对价格逐文建表或从一端盲目穷举。
        long low = Math.max(available, Math.ceilDiv(saturatedSubtract(remaining, maximum[index + 1]), value));
        long high = Math.min(row.payment(), Math.floorDiv(saturatedSubtract(remaining, -followingChange), value));
        for (long count = high; count >= low; count--) {
            counts[index] = Math.toIntExact(count);
            long transfer = value * count;
            long remainingBudget = budget + Math.min(0, transfer);
            var result = search(index + 1, remaining - transfer, remainingBudget);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static long saturatedSubtract(long first, long second) {
        // 单侧库存总值已精确校验，两侧一起估界时才可能超出 long。
        if (second < 0 && first > Long.MAX_VALUE + second) {
            return Long.MAX_VALUE;
        }
        if (second > 0 && first < Long.MIN_VALUE + second) {
            return Long.MIN_VALUE;
        }
        return first - second;
    }

    private SettlementPlan tryDelivery() {
        var debit = new ArrayList<CurrencyCatalog.Bundle>();
        var changeDue = new ArrayList<CurrencyCatalog.Bundle>();
        for (int index = 0; index < counts.length; index++) {
            int count = counts[index];
            var id = stock.get(index).coin().item();
            if (count > 0) {
                debit.add(new CurrencyCatalog.Bundle(id, count));
            } else if (count < 0) {
                changeDue.add(new CurrencyCatalog.Bundle(id, -count));
            }
        }
        try {
            return SettlementPlan.transfer(wallets, debit, changeDue, goods);
        } catch (TradeRejected rejected) {
            refusal = rejected.reason();
            return null;
        }
    }
}
