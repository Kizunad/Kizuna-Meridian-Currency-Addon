package dev.kizuna.meridian.currency;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 只能经创建它的服务报价与交割。公开快照不持有实物栈引用。 */
public abstract sealed class CurrencyAccount permits CurrencyAccount.PlayerAccount, CurrencyAccount.Till {
    final CurrencyService owner;
    private final String id;

    CurrencyAccount(CurrencyService owner, String id) {
        this.owner = owner;
        this.id = id;
    }

    public final String id() {
        return id;
    }

    public abstract List<ItemStack> contents();
    abstract long watermark();
    abstract int edits();
    abstract void install(long sequence, List<CurrencyJournal.Slot> slots);

    int storageSlot(int index) {
        return index;
    }

    static final class PlayerAccount extends CurrencyAccount {
        private final ServerPlayer player;
        private final List<Integer> accessibleSlots;

        PlayerAccount(CurrencyService owner, ServerPlayer player) {
            super(owner, "player/" + player.getUUID());
            this.player = player;
            // 随身格遵循正式菜单权限，口袋附属屏蔽的旧格既不能付款也不能收找零。
            var inventory = player.getInventory();
            accessibleSlots = player.inventoryMenu.slots.stream()
                    .filter(slot -> slot.container == inventory && slot.getContainerSlot() < Inventory.INVENTORY_SIZE)
                    .filter(slot -> slot.mayPickup(player))
                    .map(Slot::getContainerSlot)
                    .sorted()
                    .toList();
        }

        @Override
        public List<ItemStack> contents() {
            var inventory = player.getInventory();
            return accessibleSlots.stream().map(inventory::getItem).map(ItemStack::copy).toList();
        }

        @Override
        int storageSlot(int index) {
            // 日志使用原版库存槽号，重启后即使菜单布局变化也能回到原格。
            return accessibleSlots.get(index);
        }

        @Override
        long watermark() {
            return player.getAttachedOrElse(CurrencyMod.LAST_PAYMENT, 0L);
        }

        @Override
        int edits() {
            var online = player.level().getServer().getPlayerList().getPlayer(player.getUUID());
            if (player.isRemoved() || online != player) {
                throw new TradeRejected(TradeRejected.Reason.STALE);
            }
            return player.getInventory().getTimesChanged();
        }

        @Override
        void install(long sequence, List<CurrencyJournal.Slot> slots) {
            // 附件和背包写入同一份玩家存档；原版重新读到旧档时可按流水号重放。
            Inventory inventory = player.getInventory();
            for (var slot : slots) {
                int index = slot.index();
                var target = owner.decode(slot.stack());
                if (!ItemStack.matches(inventory.getItem(index), target)) {
                    inventory.setItem(index, target);
                }
            }
            player.setAttached(CurrencyMod.LAST_PAYMENT, sequence);
            inventory.setChanged();
            player.inventoryMenu.broadcastChanges();
        }
    }

    static final class Till extends CurrencyAccount {
        private final List<ItemStack> slots;
        private long sequence;

        Till(CurrencyService owner, String id, List<ItemStack> opening) {
            super(owner, id);
            slots = new ArrayList<>(opening);
        }

        @Override
        public List<ItemStack> contents() {
            return SettlementPlan.copy(slots);
        }

        @Override
        long watermark() {
            return sequence;
        }

        @Override
        int edits() {
            // 钱匣只经结算服务写入，每次写入都改变流水号。
            return 0;
        }

        @Override
        void install(long revision, List<CurrencyJournal.Slot> changes) {
            for (var change : changes) {
                slots.set(change.index(), owner.decode(change.stack()));
            }
            sequence = revision;
        }
    }
}
