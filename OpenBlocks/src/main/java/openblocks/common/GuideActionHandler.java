package openblocks.common;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import openblocks.common.tileentity.TileEntityGuide;
import openblocks.events.GuideActionEvent;

public class GuideActionHandler {

	@SubscribeEvent
	public void onEvent(GuideActionEvent evt) {
		if (evt.sender != null) {
			final World world = evt.sender.getEntityWorld();
			if (world.isBlockLoaded(evt.blockPos)) {
				final TileEntity te = world.getTileEntity(evt.blockPos);
				if (te instanceof TileEntityGuide) {
					((TileEntityGuide)te).onCommand(evt.sender, evt.command);
				}
			}
		}
	}

}
