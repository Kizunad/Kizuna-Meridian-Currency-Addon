package dev.kizuna.meridian.currency;

import dev.kizuna.meridian.protocol.pb.BodyMessages.MeridianOpeningStage;
import dev.kizuna.meridian.protocol.pb.BodyMessages.Realm;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.minecraft.world.entity.Mob;

enum PurseTier {
    MORTAL, WANDERING_QI, TRUE_QI;

    static PurseTier read(Mob mob) {
        var existing = VanillaDamageFabric.existingMobBody(mob);
        if (existing.isEmpty()) {
            return MORTAL;
        }
        var body = existing.get();
        if (body.getRealmValue() >= Realm.TRUE_QI_VALUE) {
            return TRUE_QI;
        }
        boolean opened = body.getMeridianStatesList().stream()
                .anyMatch(state -> state.getOpening().getStage() == MeridianOpeningStage.OPENED);
        return opened ? WANDERING_QI : MORTAL;
    }
}
