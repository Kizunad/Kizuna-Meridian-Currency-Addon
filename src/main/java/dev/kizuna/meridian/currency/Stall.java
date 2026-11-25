package dev.kizuna.meridian.currency;

import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/** 摊位与钱匣同 ID。宿主授权可绑定到访、事件及章节，即时复查。 */
public record Stall(UUID id, Identifier catalog, LivingEntity actor, BiPredicate<ServerPlayer, LivingEntity> admits) {
    public Stall {
        Objects.requireNonNull(id);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(admits);
    }

    boolean accessible(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && actor.isAlive() && !actor.isRemoved() && actor.level() == player.level()
                && player.isWithinEntityInteractionRange(actor, 0) && admits.test(player, actor);
    }
}
