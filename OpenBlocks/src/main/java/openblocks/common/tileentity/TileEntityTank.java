package openblocks.common.tileentity;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidActionResult;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import openblocks.Config;
import openblocks.OpenBlocks;
import openblocks.client.renderer.tileentity.tank.ITankConnections;
import openblocks.client.renderer.tileentity.tank.ITankRenderFluidData;
import openblocks.client.renderer.tileentity.tank.NeighbourMap;
import openblocks.client.renderer.tileentity.tank.TankRenderLogic;
import openblocks.common.FluidXpUtils;
import openblocks.common.FluidXpUtils.IFluidXpConverter;
import openblocks.common.item.ItemTankBlock;
import openmods.api.IActivateAwareTile;
import openmods.api.ICustomHarvestDrops;
import openmods.api.INeighbourAwareTile;
import openmods.api.IPlaceAwareTile;
import openmods.liquids.ContainerBucketFillHandler;
import openmods.model.variant.VariantModelState;
import openmods.sync.ISyncListener;
import openmods.sync.ISyncableObject;
import openmods.sync.SyncMap;
import openmods.sync.SyncableTank;
import openmods.tileentity.SyncedTileEntity;
import openmods.utils.EnchantmentUtils;
import openmods.utils.ItemUtils;

public class TileEntityTank extends SyncedTileEntity implements IActivateAwareTile, IPlaceAwareTile, INeighbourAwareTile, ICustomHarvestDrops, ITickable {

	public static class BucketFillHandler extends ContainerBucketFillHandler {
		@Override
		protected boolean canFill(World world, BlockPos pos, TileEntity te) {
			return te instanceof TileEntityTank;
		}
	}

	private class RenderUpdateListeners implements ISyncListener {

		private FluidStack prevFluidStack;

		private int prevLuminosity;

		private boolean isSameFluid(FluidStack currentFluid) {
			if (currentFluid == null) return prevFluidStack == null;
			return currentFluid.isFluidEqual(prevFluidStack);
		}

		@Override
		public void onSync(Set<ISyncableObject> changes) {
			if (changes.contains(tank)) {
				final FluidStack fluidStack = tank.getFluid();
				if (!isSameFluid(fluidStack)) {
					world.markBlockRangeForRenderUpdate(pos, pos);
					prevFluidStack = fluidStack;

					int luminosity = fluidStack != null? fluidStack.getFluid().getLuminosity(fluidStack) : 0;
					if (luminosity != prevLuminosity) {
						world.checkLight(pos);
						prevLuminosity = luminosity;
					}
				}

				renderLogic.updateFluid(fluidStack);
			}
		}
	}

	private final TankRenderLogic renderLogic;

	private boolean needsTankUpdate;

	@Override
	public void validate() {
		super.validate();

		needsTankUpdate = true;
		if (world.isRemote) renderLogic.initialize(world, pos);
	}

	@Override
	public void invalidate() {
		super.invalidate();
		if (world.isRemote) renderLogic.invalidateConnections();
	}

	protected TileEntityTank getNeighourTank(BlockPos pos) {
		if (!world.isBlockLoaded(pos)) return null;

		Chunk chunk = world.getChunkFromBlockCoords(pos);
		TileEntity te = chunk.getTileEntity(pos, Chunk.CreateEntityType.CHECK);
		return (te instanceof TileEntityTank)? (TileEntityTank)te : null;
	}

	private static final int SYNC_THRESHOLD = 8;
	private static final int UPDATE_THRESHOLD = 20;

	private SyncableTank tank;

	private boolean forceUpdate = true;

	private int ticksSinceLastSync = hashCode() % SYNC_THRESHOLD;

	private boolean needsSync;

	private int ticksSinceLastUpdate = hashCode() % UPDATE_THRESHOLD;

	private boolean needsUpdate;

	private final IFluidHandler tankCapabilityWrapper = new IFluidHandler() {

		@Override
		public IFluidTankProperties[] getTankProperties() {
			return tank.getTankProperties();
		}

		@Override
		public int fill(FluidStack resource, boolean doFill) {
			if (resource == null) return 0;
			FluidStack copy = resource.copy();
			fillColumn(copy, doFill);

			return resource.amount - copy.amount;
		}

		@Override
		@Nullable
		public FluidStack drain(int maxDrain, boolean doDrain) {
			if (maxDrain <= 0) return null;

			FluidStack contents = tank.getFluid();
			if (contents == null || contents.amount <= 0) return null;

			FluidStack needed = contents.copy();
			needed.amount = maxDrain;

			drainFromColumn(needed, doDrain);

			needed.amount = maxDrain - needed.amount;
			return needed;
		}

		@Override
		@Nullable
		public FluidStack drain(FluidStack resource, boolean doDrain) {
			if (resource == null) return null;

			FluidStack needed = resource.copy();
			drainFromColumn(needed, doDrain);

			needed.amount = resource.amount - needed.amount;
			return needed;
		}
	};

	public TileEntityTank() {
		renderLogic = new TankRenderLogic(tank);
	}

	@Override
	protected void onSyncMapCreate(SyncMap syncMap) {
		syncMap.addSyncListener(changes -> ticksSinceLastSync = 0);

		syncMap.addUpdateListener(new RenderUpdateListeners());
	}

	@Override
	protected void createSyncedFields() {
		tank = new SyncableTank(getTankCapacity());
	}

	public double getFluidRatio() {
		return (double)tank.getFluidAmount() / (double)tank.getCapacity();
	}

	public static int getTankCapacity() {
		return Fluid.BUCKET_VOLUME * Config.bucketsPerTank;
	}

	public int getFluidLightLevel() {
		FluidStack stack = tank.getFluid();
		if (stack != null) {
			Fluid fluid = stack.getFluid();
			if (fluid != null) return fluid.getLuminosity();
		}

		return 0;
	}

	public ITankRenderFluidData getRenderFluidData() {
		return renderLogic.getTankRenderData();
	}

	public ITankConnections getTankConnections() {
		return renderLogic.getTankConnections();
	}

	public VariantModelState getModelState() {
		// TODO maybe cache?
		return new NeighbourMap(world, pos, tank.getFluid()).getState();
	}

	public boolean accepts(FluidStack liquid) {
		if (liquid == null) return true;
		final FluidStack ownFluid = tank.getFluid();
		return ownFluid == null || ownFluid.isFluidEqual(liquid);
	}

	private boolean containsFluid(FluidStack liquid) {
		if (liquid == null) return false;
		final FluidStack ownFluid = tank.getFluid();
		return ownFluid != null && ownFluid.isFluidEqual(liquid);
	}

	public IFluidTank getTank() {
		return tank;
	}

	public CompoundNBT getItemNBT() {
		CompoundNBT nbt = new CompoundNBT();
		tank.writeToNBT(nbt);
		return nbt;
	}

	@Override
	public void onNeighbourChanged(BlockPos neighbourPos, Block neighbourBlock) {
		forceUpdate = true;
		needsTankUpdate = true;
	}

	@Override
	public void onBlockPlacedBy(BlockState state, LivingEntity placer, @Nonnull ItemStack stack) {
		CompoundNBT itemTag = stack.getTagCompound();

		if (itemTag != null && itemTag.hasKey(ItemTankBlock.TANK_TAG)) {
			tank.readFromNBT(itemTag.getCompoundTag(ItemTankBlock.TANK_TAG));
		}
	}

	private static TileEntityTank getValidTank(final TileEntity neighbor) {
		return (neighbor instanceof TileEntityTank && !neighbor.isInvalid())? (TileEntityTank)neighbor : null;
	}

	private TileEntityTank getTankInDirection(Direction direction) {
		final TileEntity neighbor = getTileInDirection(direction);
		return getValidTank(neighbor);
	}

	public TileEntityTank getTankInDirection(int dx, int dy, int dz) {
		final TileEntity neighbor = getTileEntity(this.pos.add(dx, dy, dz));
		return getValidTank(neighbor);
	}

	@Override
	public boolean onBlockActivated(PlayerEntity player, Hand hand, Direction side, float hitX, float hitY, float hitZ) {
		if (hand == Hand.MAIN_HAND) {
			final ItemStack heldItem = player.getHeldItemMainhand();
			if (!heldItem.isEmpty()) {
				final FluidActionResult result = tryEmptyItem(player, heldItem.copy());
				if (result.success) {
					if (!player.isCreative())
						player.setHeldItem(Hand.MAIN_HAND, result.result);
					return true;
				}
			} else
				return tryDrainXp(player);
		}

		return false;
	}

	protected boolean tryDrainXp(PlayerEntity player) {
		final FluidStack fluid = tank.getFluid();
		final Optional<IFluidXpConverter> maybeConverter = FluidXpUtils.getConverter(fluid);
		if (maybeConverter.isPresent()) {
			final IFluidXpConverter converter = maybeConverter.get();
			int requiredXp = MathHelper.ceil(player.xpBarCap() * (1 - player.experience));
			int requiredXpFluid = converter.xpToFluid(requiredXp);

			FluidStack drained = tankCapabilityWrapper.drain(requiredXpFluid, false);
			if (drained != null) {
				int xp = converter.fluidToXp(drained.amount);
				if (xp > 0) {
					int actualDrain = converter.xpToFluid(xp);
					EnchantmentUtils.addPlayerXP(player, xp);
					tankCapabilityWrapper.drain(actualDrain, true);
					return true;
				}
			}
		}

		return false;
	}

	protected FluidActionResult tryEmptyItem(PlayerEntity player, @Nonnull ItemStack container) {
		// not using FluidUtils.tryEmptyContainer, since it limits stack size to 1
		final IFluidHandlerItem containerFluidHandler = FluidUtil.getFluidHandler(container);
		if (containerFluidHandler != null) {
			FluidStack transfer = FluidUtil.tryFluidTransfer(tankCapabilityWrapper, containerFluidHandler, Fluid.BUCKET_VOLUME, true);
			if (transfer != null) {
				SoundEvent soundevent = transfer.getFluid().getEmptySound(transfer);
				player.playSound(soundevent, 1f, 1f);
				return new FluidActionResult(containerFluidHandler.getContainer());
			}
		}

		return FluidActionResult.FAILURE;
	}

	@Override
	public void update() {
		ticksSinceLastSync++;
		ticksSinceLastUpdate++;

		if (Config.shouldTanksUpdate && !world.isRemote && forceUpdate) {
			if (needsTankUpdate) {
				tank.updateNeighbours(world, pos);
				needsTankUpdate = false;
			}

			forceUpdate = false;

			FluidStack contents = tank.getFluid();
			if (contents != null && contents.amount > 0 && pos.getY() > 0) {
				tryFillBottomTank(contents);
				contents = tank.getFluid();
			}

			if (contents != null && contents.amount > 0) {
				tryBalanceNeighbors(contents);
			}

			needsSync = true;
			markUpdated();
		}

		if (needsSync && !world.isRemote && ticksSinceLastSync > SYNC_THRESHOLD) {
			needsSync = false;
			sync();
		}

		if (needsUpdate && ticksSinceLastUpdate > UPDATE_THRESHOLD) {
			needsUpdate = false;
			ticksSinceLastUpdate = 0;
			world.notifyNeighborsOfStateChange(pos, getBlockType(), false);
		}

		if (world.isRemote) renderLogic.validateConnections(world, getPos());
	}

	private void tryGetNeighbor(List<TileEntityTank> result, FluidStack fluid, Direction side) {
		TileEntityTank neighbor = getTankInDirection(side);
		if (neighbor != null && neighbor.accepts(fluid)) result.add(neighbor);
	}

	private void tryBalanceNeighbors(FluidStack contents) {
		List<TileEntityTank> neighbors = Lists.newArrayList();
		tryGetNeighbor(neighbors, contents, Direction.NORTH);
		tryGetNeighbor(neighbors, contents, Direction.SOUTH);
		tryGetNeighbor(neighbors, contents, Direction.EAST);
		tryGetNeighbor(neighbors, contents, Direction.WEST);

		final int count = neighbors.size();
		if (count == 0) return;

		int sum = contents.amount;
		for (TileEntityTank n : neighbors)
			sum += n.tank.getFluidAmount();

		final int suggestedAmount = sum / (count + 1);
		if (Math.abs(suggestedAmount - contents.amount) < Config.tankFluidUpdateThreshold) return; // Don't balance small amounts to reduce server load

		FluidStack suggestedStack = contents.copy();
		suggestedStack.amount = suggestedAmount;

		for (TileEntityTank n : neighbors) {
			int amount = n.tank.getFluidAmount();
			int diff = amount - suggestedAmount;
			if (diff != 1 && diff != 0 && diff != -1) {
				n.tank.setFluid(suggestedStack.copy());
				n.tankChanged();
				sum -= suggestedAmount;
				n.forceUpdate = true;
			} else {
				sum -= amount;
			}
		}

		FluidStack s = tank.getFluid();
		if (sum != s.amount) {
			s.amount = sum;
			tankChanged();
		}
	}

	private void notifyNeigbours() {
		needsUpdate = true;
	}

	private void tankChanged() {
		notifyNeigbours();
		tank.markDirty();
	}

	private void markContentsUpdated() {
		notifyNeigbours();
		forceUpdate = true;
	}

	private void tryFillBottomTank(FluidStack fluid) {
		TileEntity te = world.getTileEntity(pos.down());
		if (te instanceof TileEntityTank) {
			int amount = ((TileEntityTank)te).internalFill(fluid, true);
			if (amount > 0) internalDrain(amount, true);
		}
	}

	private FluidStack internalDrain(int amount, boolean doDrain) {
		FluidStack drained = tank.drain(amount, doDrain);
		if (drained != null && doDrain) markContentsUpdated();
		return drained;
	}

	private void drainFromColumn(FluidStack needed, boolean doDrain) {
		if (!containsFluid(needed) || needed.amount <= 0) return;

		if (pos.getY() < 255) {
			TileEntity te = world.getTileEntity(pos.up());
			if (te instanceof TileEntityTank) ((TileEntityTank)te).drainFromColumn(needed, doDrain);
		}

		if (needed.amount <= 0) return;

		FluidStack drained = internalDrain(needed.amount, doDrain);
		if (drained == null) return;

		needed.amount -= drained.amount;
	}

	private int internalFill(FluidStack resource, boolean doFill) {
		int amount = tank.fill(resource, doFill);
		if (amount > 0 && doFill) markContentsUpdated();
		return amount;
	}

	private void fillColumn(FluidStack resource, boolean doFill) {
		if (!accepts(resource) || resource.amount <= 0) return;

		int amount = internalFill(resource, doFill);

		resource.amount -= amount;

		if (resource.amount > 0 && pos.getY() < 255) {
			TileEntity te = world.getTileEntity(pos.up());
			if (te instanceof TileEntityTank) ((TileEntityTank)te).fillColumn(resource, doFill);
		}
	}

	@Override
	public boolean suppressBlockHarvestDrops() {
		return true;
	}

	@Override
	public void addHarvestDrops(PlayerEntity player, List<ItemStack> drops, BlockState blockState, int fortune, boolean isSilkTouch) {
		ItemStack stack = new ItemStack(OpenBlocks.Blocks.tank);

		if (tank.getFluidAmount() > 0) {
			CompoundNBT tankTag = getItemNBT();
			CompoundNBT itemTag = ItemUtils.getItemTag(stack);
			itemTag.setTag("tank", tankTag);
		}

		drops.add(stack);
	}

	@Override
	public boolean shouldRenderInPass(int pass) {
		return pass == 1;
	}

	@Override
	public boolean hasFastRenderer() {
		return true;
	}

	@Override
	public boolean hasCapability(Capability<?> capability, Direction facing) {
		return capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY
				|| super.hasCapability(capability, facing);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getCapability(Capability<T> capability, Direction facing) {
		if (capability == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY)
			return (T)tankCapabilityWrapper;

		return super.getCapability(capability, facing);
	}
}
