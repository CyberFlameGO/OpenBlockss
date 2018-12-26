package openblocks.common.tileentity;

import java.util.List;
import javax.annotation.Nonnull;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;
import openblocks.common.TrophyHandler.Trophy;
import openblocks.common.item.ItemTrophyBlock;
import openmods.api.IActivateAwareTile;
import openmods.api.ICustomHarvestDrops;
import openmods.api.ICustomPickItem;
import openmods.api.IPlaceAwareTile;
import openmods.sync.SyncableEnum;
import openmods.tileentity.SyncedTileEntity;
import openmods.utils.ItemUtils;

public class TileEntityTrophy extends SyncedTileEntity implements IPlaceAwareTile, IActivateAwareTile, ICustomHarvestDrops, ICustomPickItem, ITickable {

	private final String TAG_COOLDOWN = "cooldown";

	private int cooldown = 0;
	private SyncableEnum<Trophy> trophyIndex;

	public TileEntityTrophy() {}

	@Override
	protected void createSyncedFields() {
		trophyIndex = new SyncableEnum<>(Trophy.PigZombie);
	}

	public Trophy getTrophy() {
		return trophyIndex.get();
	}

	@Override
	public void update() {
		if (!world.isRemote) {
			Trophy trophy = getTrophy();
			if (trophy != null) trophy.executeTickBehavior(this);
			if (cooldown > 0) cooldown--;
		}
	}

	@Override
	public boolean onBlockActivated(EntityPlayer player, EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ) {
		if (!world.isRemote && hand == EnumHand.MAIN_HAND) {
			Trophy trophyType = getTrophy();
			if (trophyType != null) {
				trophyType.playSound(world, pos);
				if (cooldown <= 0) cooldown = trophyType.executeActivateBehavior(this, player);
			}
		}
		return true;
	}

	@Override
	public void onBlockPlacedBy(IBlockState state, EntityLivingBase placer, @Nonnull ItemStack stack) {
		Trophy trophy = ItemTrophyBlock.getTrophy(stack);
		if (trophy != null) trophyIndex.set(trophy);

		if (stack.hasTagCompound()) {
			NBTTagCompound tag = stack.getTagCompound();
			this.cooldown = tag.getInteger(TAG_COOLDOWN);
		}
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		super.readFromNBT(tag);
		this.cooldown = tag.getInteger(TAG_COOLDOWN);
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag) {
		tag = super.writeToNBT(tag);
		tag.setInteger("cooldown", cooldown);
		return tag;
	}

	@Override
	public boolean suppressBlockHarvestDrops() {
		return true;
	}

	@Nonnull
	private ItemStack getAsItem() {
		final Trophy trophy = getTrophy();
		if (trophy != null) {
			ItemStack stack = trophy.getItemStack();
			if (!stack.isEmpty()) {
				NBTTagCompound tag = ItemUtils.getItemTag(stack);
				tag.setInteger(TAG_COOLDOWN, cooldown);
				return stack;
			}
		}

		return ItemStack.EMPTY;
	}

	@Override
	public void addHarvestDrops(EntityPlayer player, List<ItemStack> drops, IBlockState blockState, int fortune, boolean isSilkTouch) {
		ItemStack stack = getAsItem();
		if (!stack.isEmpty()) drops.add(stack);
	}

	@Override
	@Nonnull
	public ItemStack getPickBlock(EntityPlayer player) {
		final Trophy trophy = getTrophy();
		return trophy != null? trophy.getItemStack() : ItemStack.EMPTY;
	}

}
