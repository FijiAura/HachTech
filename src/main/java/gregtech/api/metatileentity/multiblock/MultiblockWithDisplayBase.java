package gregtech.api.metatileentity.multiblock;

import gregtech.api.GTValues;
import gregtech.api.block.VariantActiveBlock;
import gregtech.api.capability.*;
import gregtech.api.metatileentity.multiblock.ui.MultiblockUIBuilder;
import gregtech.api.metatileentity.multiblock.ui.MultiblockUIFactory;
import gregtech.api.pattern.PatternMatchContext;
import gregtech.api.pattern.TraceabilityPredicate;
import gregtech.api.unification.OreDictUnifier;
import gregtech.api.unification.material.Materials;
import gregtech.api.unification.ore.OrePrefix;
import gregtech.common.ConfigHolder;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.fluids.IFluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static gregtech.api.capability.GregtechDataCodes.IS_WORKING;
import static gregtech.api.capability.GregtechDataCodes.STORE_TAPED;

public abstract class MultiblockWithDisplayBase extends MultiblockControllerBase implements IMaintenance {

    private static final String NBT_VOIDING_MODE = "VoidingMode";
    private static final String NBT_VOIDING_ITEMS = "VoidingItems";
    private static final String NBT_VOIDING_FLUIDS = "VoidingFluids";
    private MultiblockUIFactory uiFactory;

    private boolean voidingItems = false;
    private boolean voidingFluids = false;
    private VoidingMode voidingMode;
    private boolean fluidInfSink = false;
    private boolean itemInfSink = false;

    // Held since it is frequently accessed
    private IMaintenanceHatch maintenanceHatch;

    /**
     * Items to recover in a muffler hatch
     */
    protected final List<ItemStack> recoveryItems = new ArrayList<>(
            Collections.singleton(OreDictUnifier.get(OrePrefix.dustTiny, Materials.Ash)));

    private int timeActive;

    /**
     * This value stores whether each of the 5 maintenance problems have been fixed.
     * A value of 0 means the problem is not fixed, else it is fixed
     * Value positions correspond to the following from left to right: 0=Wrench, 1=Screwdriver, 2=Soft Mallet, 3=Hard
     * Hammer, 4=Wire Cutter, 5=Crowbar
     */
    protected byte maintenance_problems;
    // Used for tracking if this is the initial state of the machine, for maintenance hatches which automatically fix
    // initial issues.
    private boolean initialMaintenanceDone;

    // Used for data preservation with Maintenance Hatch
    private boolean storedTaped = false;

    protected List<BlockPos> variantActiveBlocks;
    protected boolean lastActive;

    public MultiblockWithDisplayBase(ResourceLocation metaTileEntityId) {
        super(metaTileEntityId);
        this.maintenance_problems = 0b000000;
        this.voidingMode = VoidingMode.VOID_NONE;
    }

    /**
     * Sets the maintenance problem corresponding to index to fixed
     *
     * @param index of the maintenance problem
     */
    @Override
    public void setMaintenanceFixed(int index) {
        this.maintenance_problems |= 1 << index;
    }

    /**
     * Used to cause a single random maintenance problem
     */
    @Override
    public void causeMaintenanceProblems() {
        this.maintenance_problems &= ~(1 << ((int) (GTValues.RNG.nextFloat() * 5)));
        this.getWorld().playSound(null, this.getPos().getX(), this.getPos().getY(), this.getPos().getZ(),
                this.getBreakdownSound(), SoundCategory.BLOCKS, 1.f, 1.f);
    }

    /**
     * @return the byte value representing the maintenance problems
     */
    @Override
    public byte getMaintenanceProblems() {
        return ConfigHolder.machines.enableMaintenance && hasMaintenanceMechanics() ? maintenance_problems : 0b111111;
    }

    /**
     * @return the amount of maintenance problems the multiblock has
     */
    @Override
    public int getNumMaintenanceProblems() {
        return ConfigHolder.machines.enableMaintenance && hasMaintenanceMechanics() ?
                6 - Integer.bitCount(maintenance_problems) : 0;
    }

    /**
     * @return whether the multiblock has any maintenance problems
     */
    @Override
    public boolean hasMaintenanceProblems() {
        return ConfigHolder.machines.enableMaintenance && hasMaintenanceMechanics() && this.maintenance_problems < 63;
    }

    /**
     * @return whether this multiblock has maintenance mechanics
     */
    @Override
    public boolean hasMaintenanceMechanics() {
        return true;
    }

    public boolean hasMufflerMechanics() {
        return false;
    }

    /**
     * Used to calculate whether a maintenance problem should happen based on machine time active
     */
    public void calculateMaintenance() {
        if (!ConfigHolder.machines.enableMaintenance || !hasMaintenanceMechanics() || maintenanceHatch == null)
            return;

        if (maintenanceHatch.isFullAuto()) {
            return;
        }

        if (++timeActive >= 1000 / maintenanceHatch.getTimeMultiplier()) {
            timeActive = 0;
            if (GTValues.RNG.nextInt(6000) == 0) {
                causeMaintenanceProblems();
                maintenanceHatch.setTaped(false);
            }
        }
    }

    /**
     * Configurable Maintenance modifier applied to external effects, like recipe durations.
     * Ranges from 0.9 to 1.1. Lower is a "better" effect than normal, higher is a "worse" effect than normal.
     */
    public double getMaintenanceDurationMultiplier() {
        if (!ConfigHolder.machines.enableMaintenance || !hasMaintenanceMechanics() || maintenanceHatch == null) {
            return 1.0;
        }
        return maintenanceHatch.getDurationMultiplier();
    }

    @Override
    public boolean isStructureObstructed() {
        return hasMufflerMechanics() && !isMufflerFaceFree();
    }

    @Override
    protected void formStructure(PatternMatchContext context) {
        super.formStructure(context);
        if (this.hasMaintenanceMechanics() && ConfigHolder.machines.enableMaintenance) { // nothing extra if no
                                                                                         // maintenance
            if (getAbilities(MultiblockAbility.MAINTENANCE_HATCH).isEmpty())
                return;
            maintenanceHatch = getAbilities(MultiblockAbility.MAINTENANCE_HATCH).get(0);
            if (maintenanceHatch.startWithoutProblems() && !initialMaintenanceDone) {
                this.maintenance_problems = (byte) 0b111111;
                this.timeActive = 0;
                this.initialMaintenanceDone = true;
            }
            readMaintenanceData(maintenanceHatch);
            if (storedTaped) {
                maintenanceHatch.setTaped(true);
                storeTaped(false);
            }
        }
        this.variantActiveBlocks = context.getOrDefault("VABlock", new LinkedList<>());
        replaceVariantBlocksActive(false);
    }

    @Override
    public void update() {
        super.update();
        if (!getWorld().isRemote) {
            boolean state = isActive();
            if (lastActive != state) {
                this.setLastActive(state);
                this.markDirty();
                this.replaceVariantBlocksActive(lastActive);
            }
            if (state) {
                calculateMaintenance();
            }
        }
    }

    public void setLastActive(boolean lastActive) {
        this.lastActive = lastActive;
        this.writeCustomData(IS_WORKING, buf -> buf.writeBoolean(lastActive));
    }

    /**
     * Stores the taped state of the maintenance hatch
     *
     * @param isTaped is whether the maintenance hatch is taped or not
     */
    @Override
    public void storeTaped(boolean isTaped) {
        this.storedTaped = isTaped;
        writeCustomData(STORE_TAPED, buf -> buf.writeBoolean(isTaped));
    }

    /**
     * reads maintenance data from a maintenance hatch
     *
     * @param hatch is the hatch to read the data from
     */
    private void readMaintenanceData(IMaintenanceHatch hatch) {
        if (hatch.hasMaintenanceData()) {
            Tuple<Byte, Integer> data = hatch.readMaintenanceData();
            this.maintenance_problems = data.getFirst();
            this.timeActive = data.getSecond();
        }
    }

    /**
     * @return whether the muffler hatch's front face is free
     */
    public boolean isMufflerFaceFree() {
        if (!isStructureFormed()) {
            return false;
        }
        if (!hasMufflerMechanics()) {
            return false;
        }

        var mufflers = getAbilities(MultiblockAbility.MUFFLER_HATCH);
        if (mufflers.isEmpty()) {
            return false;
        }
        return mufflers.get(0).isFrontFaceFree();
    }

    @SideOnly(Side.CLIENT)
    public @NotNull EnumParticleTypes getMufflerParticle() {
        return EnumParticleTypes.SMOKE_LARGE;
    }

    /**
     * Sets the recovery items of this multiblock
     *
     * @param recoveryItems is the items to set
     */
    protected void setRecoveryItems(ItemStack... recoveryItems) {
        this.recoveryItems.clear();
        this.recoveryItems.addAll(Arrays.asList(recoveryItems));
    }

    /**
     * @return whether the current multiblock is active or not
     */
    public boolean isActive() {
        return isStructureFormed();
    }

    @Override
    public void invalidateStructure() {
        if (hasMaintenanceMechanics() && ConfigHolder.machines.enableMaintenance) { // nothing extra if no maintenance
            if (!getAbilities(MultiblockAbility.MAINTENANCE_HATCH).isEmpty())
                getAbilities(MultiblockAbility.MAINTENANCE_HATCH).get(0)
                        .storeMaintenanceData(maintenance_problems, timeActive);
        }
        this.lastActive = false;
        this.replaceVariantBlocksActive(false);
        this.fluidInfSink = false;
        this.itemInfSink = false;
        this.maintenanceHatch = null;
        super.invalidateStructure();
    }

    protected void replaceVariantBlocksActive(boolean isActive) {
        if (variantActiveBlocks != null && !variantActiveBlocks.isEmpty()) {
            int id = getWorld().provider.getDimension();

            writeCustomData(GregtechDataCodes.VARIANT_RENDER_UPDATE, buf -> {
                buf.writeInt(id);
                buf.writeBoolean(isActive);
                buf.writeInt(variantActiveBlocks.size());
                for (BlockPos blockPos : variantActiveBlocks) {
                    VariantActiveBlock.setBlockActive(id, blockPos, isActive);
                    buf.writeBlockPos(blockPos);
                }
            });
        }
    }

    public TraceabilityPredicate autoAbilities() {
        return autoAbilities(true, true);
    }

    public TraceabilityPredicate autoAbilities(boolean checkMaintenance, boolean checkMuffler) {
        TraceabilityPredicate predicate = new TraceabilityPredicate();
        if (checkMaintenance && hasMaintenanceMechanics()) {
            predicate = predicate.or(maintenancePredicate());
        }
        if (checkMuffler && hasMufflerMechanics()) {
            predicate = predicate
                    .or(abilities(MultiblockAbility.MUFFLER_HATCH).setMinGlobalLimited(1).setMaxGlobalLimited(1));
        }
        return predicate;
    }

    protected TraceabilityPredicate maintenancePredicate() {
        if (hasMaintenanceMechanics()) {
            return abilities(MultiblockAbility.MAINTENANCE_HATCH)
                    .setMinGlobalLimited(ConfigHolder.machines.enableMaintenance ? 1 : 0).setMaxGlobalLimited(1);
        }
        return new TraceabilityPredicate();
    }

    public boolean shouldShowVoidingModeButton() {
        return true;
    }

    public final int getVoidingMode() {
        return voidingMode.ordinal();
    }

    public final void setVoidingMode(int mode) {
        this.voidingMode = VoidingMode.VALUES[mode];

        this.voidingFluids = mode >= 2;

        this.voidingItems = mode == 1 || mode == 3;

        // After changing the voiding mode, reset the notified buses in case a recipe can run now that voiding mode has
        // been changed
        for (IFluidTank tank : this.getAbilities(MultiblockAbility.IMPORT_FLUIDS)) {
            this.getNotifiedFluidInputList().add((IFluidHandler) tank);
        }
        this.getNotifiedItemInputList()
                .addAll(this.getAbilities(MultiblockAbility.IMPORT_ITEMS));

        markDirty();
    }

    public @NotNull String getVoidingModeTooltip(int mode) {
        return VoidingMode.VALUES[mode].getName();
    }

    @Override
    public boolean usesMui2() {
        return true;
    }

    protected void configureDisplayText(MultiblockUIBuilder builder) {}

    protected void configureErrorText(MultiblockUIBuilder builder) {
        builder.structureFormed(isStructureFormed());
        if (hasMufflerMechanics())
            builder.addMufflerObstructedLine(!isMufflerFaceFree());
        if (hasMaintenanceMechanics())
            builder.addMaintenanceProblemLines(getMaintenanceProblems(), false);
    }

    protected void configureWarningText(MultiblockUIBuilder builder) {
        if (hasMaintenanceMechanics())
            builder.addMaintenanceProblemLines(getMaintenanceProblems(), true);
    }

    protected MultiblockUIFactory createUIFactory() {
        return new MultiblockUIFactory(this)
                .configureDisplayText(this::configureDisplayText)
                .configureWarningText(this::configureWarningText)
                .configureErrorText(this::configureErrorText);
    }

    @Override
    public final ModularPanel buildUI(PosGuiData guiData, PanelSyncManager panelSyncManager) {
        if (uiFactory == null) uiFactory = createUIFactory();
        return this.uiFactory.buildUI(guiData, panelSyncManager);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setByte("Maintenance", maintenance_problems);
        data.setBoolean("InitialMaintenance", initialMaintenanceDone);
        data.setInteger("ActiveTimer", timeActive);
        data.setBoolean(NBT_VOIDING_ITEMS, voidingItems);
        data.setBoolean(NBT_VOIDING_FLUIDS, voidingFluids);
        data.setInteger(NBT_VOIDING_MODE, voidingMode.ordinal());
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        maintenance_problems = data.getByte("Maintenance");
        initialMaintenanceDone = data.getBoolean("InitialMaintenance");
        timeActive = data.getInteger("ActiveTimer");
        if (data.hasKey(NBT_VOIDING_ITEMS)) {
            voidingItems = data.getBoolean(NBT_VOIDING_ITEMS);
        }

        if (data.hasKey(NBT_VOIDING_FLUIDS)) {
            voidingFluids = data.getBoolean(NBT_VOIDING_FLUIDS);
        }

        if (data.hasKey(NBT_VOIDING_MODE)) {
            voidingMode = VoidingMode.values()[data.getInteger(NBT_VOIDING_MODE)];
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        buf.writeByte(maintenance_problems);
        buf.writeInt(timeActive);
        buf.writeBoolean(voidingFluids);
        buf.writeBoolean(voidingItems);
        buf.writeInt(voidingMode.ordinal());
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        maintenance_problems = buf.readByte();
        timeActive = buf.readInt();
        voidingFluids = buf.readBoolean();
        voidingItems = buf.readBoolean();
        voidingMode = VoidingMode.values()[buf.readInt()];
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        super.receiveCustomData(dataId, buf);
        if (dataId == STORE_TAPED) {
            storedTaped = buf.readBoolean();
        }
        if (dataId == GregtechDataCodes.VARIANT_RENDER_UPDATE) {
            int minX;
            int minY;
            int minZ;
            minX = minY = minZ = Integer.MAX_VALUE;
            int maxX;
            int maxY;
            int maxZ;
            maxX = maxY = maxZ = Integer.MIN_VALUE;

            int id = buf.readInt();
            boolean isActive = buf.readBoolean();
            int size = buf.readInt();
            for (int i = 0; i < size; i++) {
                BlockPos blockPos = buf.readBlockPos();
                VariantActiveBlock.setBlockActive(id, blockPos, isActive);
                minX = Math.min(minX, blockPos.getX());
                minY = Math.min(minY, blockPos.getY());
                minZ = Math.min(minZ, blockPos.getZ());
                maxX = Math.max(maxX, blockPos.getX());
                maxY = Math.max(maxY, blockPos.getY());
                maxZ = Math.max(maxZ, blockPos.getZ());
            }

            if (getWorld().provider.getDimension() == id) {
                getWorld().markBlockRangeForRenderUpdate(new BlockPos(minX, minY, minZ),
                        new BlockPos(maxX, maxY, maxZ));
            }
        }
        if (dataId == IS_WORKING) {
            lastActive = buf.readBoolean();
        }
    }

    @Override
    public <T> T getCapability(Capability<T> capability, EnumFacing side) {
        T capabilityResult = super.getCapability(capability, side);
        if (capabilityResult != null) return capabilityResult;
        if (capability == GregtechTileCapabilities.CAPABILITY_MAINTENANCE) {
            if (this.hasMaintenanceMechanics() && ConfigHolder.machines.enableMaintenance) {
                return GregtechTileCapabilities.CAPABILITY_MAINTENANCE.cast(this);
            }
        }
        return null;
    }

    @Override
    public boolean canVoidRecipeFluidOutputs() {
        return voidingFluids || fluidInfSink;
    }

    @Override
    public boolean canVoidRecipeItemOutputs() {
        return voidingItems || itemInfSink;
    }

    public void enableFluidInfSink() {
        this.fluidInfSink = true;
    }

    public void enableItemInfSink() {
        this.itemInfSink = true;
    }
}
