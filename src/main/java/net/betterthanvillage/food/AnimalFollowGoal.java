package net.betterthanvillage.food;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.Item;

import java.util.EnumSet;
import java.util.Map;
import java.util.WeakHashMap;

/** Animal follows a villager that holds its lure item (04 §4.9). One goal per animal, added on first use. */
public final class AnimalFollowGoal extends Goal {
    private static final Map<Animal, AnimalFollowGoal> GOALS = new WeakHashMap<>();

    private final Animal animal;
    private Villager leader;
    private Item item;
    private int repath;

    private AnimalFollowGoal(Animal animal) {
        this.animal = animal;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static void follow(Animal animal, Villager leader, Item item) {
        AnimalFollowGoal goal = GOALS.computeIfAbsent(animal, a -> {
            AnimalFollowGoal g = new AnimalFollowGoal(a);
            a.goalSelector.addGoal(1, g);
            return g;
        });
        goal.leader = leader;
        goal.item = item;
    }

    public static void release(Animal animal) {
        AnimalFollowGoal goal = GOALS.get(animal);
        if (goal != null) {
            goal.leader = null;
            goal.item = null;
        }
    }

    @Override
    public boolean canUse() {
        return animal.isAlive() && leader != null && leader.isAlive() && !leader.isSleeping() && leader.getTradingPlayer() == null
                && item != null && leader.getMainHandItem().is(item) && animal.distanceToSqr(leader) <= 144;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void tick() {
        if (leader == null) return;
        animal.getLookControl().setLookAt(leader, 30, 30);
        if (animal.distanceToSqr(leader) < 2.25) {
            animal.getNavigation().stop();
        } else if (repath-- <= 0) {
            repath = 5;
            animal.getNavigation().moveTo(leader, 1.05);
        }
    }

    @Override
    public void stop() {
        animal.getNavigation().stop();
    }
}
