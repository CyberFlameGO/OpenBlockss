package openblocks.enchantments.flimflams;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import openblocks.api.IFlimFlamAction;
import openmods.utils.CollectionUtils;

public class SheepDyeFlimFlam implements IFlimFlamAction {

	private static final Random random = new Random();

	@Override
	public boolean execute(EntityPlayerMP target) {
		World world = target.world;
		AxisAlignedBB around = target.getEntityBoundingBox().grow(20);
		List<EntitySheep> sheeps = world.getEntitiesWithinAABB(EntitySheep.class, around);
		if (sheeps.isEmpty()) return false;

		EntitySheep chosenOne = sheeps.get(random.nextInt(sheeps.size()));
		chosenOne.setFleeceColor(CollectionUtils.getRandom(Arrays.asList(EnumDyeColor.values())));
		return true;
	}

}
