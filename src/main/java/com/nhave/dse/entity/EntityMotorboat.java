package com.nhave.dse.entity;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.nhave.dse.items.ItemMotorboat;
import com.nhave.dse.items.ItemShader;
import com.nhave.dse.network.ConsumeBoatPowerPacket;
import com.nhave.dse.network.DSEPacketHandler;
import com.nhave.dse.registry.ModConfig;
import com.nhave.dse.registry.ModItems;
import com.nhave.nhc.helpers.ItemHelper;
import com.nhave.nhc.util.ItemUtil;

import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.client.CPacketSteerBoat;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class EntityMotorboat extends EntityBoat
{
    private static final DataParameter<Integer> TIME_SINCE_HIT = EntityDataManager.<Integer>createKey(EntityMotorboat.class, DataSerializers.VARINT);
    private static final DataParameter<Integer> FORWARD_DIRECTION = EntityDataManager.<Integer>createKey(EntityMotorboat.class, DataSerializers.VARINT);
    private static final DataParameter<Float> DAMAGE_TAKEN = EntityDataManager.<Float>createKey(EntityMotorboat.class, DataSerializers.FLOAT);
    private static final DataParameter<Integer> BOAT_TYPE = EntityDataManager.<Integer>createKey(EntityMotorboat.class, DataSerializers.VARINT);
    private static final DataParameter<Boolean>[] DATA_ID_PADDLE = new DataParameter[] {EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN), EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN)};
    
    /** Upgrades */
	private static final DataParameter<ItemStack> SHADER = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.ITEM_STACK);
	private static final DataParameter<Boolean> PADDLES = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Boolean> STORAGE = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Boolean> BOOSTER = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Boolean> MAGNET = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.BOOLEAN);
	private static final DataParameter<Integer> POWER = EntityDataManager.createKey(EntityMotorboat.class, DataSerializers.VARINT);
	
    private final float[] paddlePositions;
    /** How much of current speed to retain. Value zero to one. */
    private float momentum;
    private float outOfControlTicks;
    private float deltaRotation;
    private int lerpSteps;
    private double boatPitch;
    private double lerpY;
    private double lerpZ;
    private double boatYaw;
    private double lerpXRot;
    public boolean leftInputDown;
    public boolean rightInputDown;
    private boolean forwardInputDown;
    private boolean backInputDown;
	public boolean isBoosting;
    private double waterLevel;
    /**
     * How much the boat should glide given the slippery blocks it's currently gliding over.
     * Halved every tick.
     */
    private float boatGlide;
    private EntityMotorboat.Status status;
    private EntityMotorboat.Status previousStatus;
    private double lastYd;
	public float propellerRotation = 0F;
	
	public int boatPowerCapacity;
    public int boatPowerUsage;
    public int boatBoostModifier;
    
    public EntityMotorboat(World worldIn)
    {
        super(worldIn);
        this.paddlePositions = new float[2];
        this.preventEntitySpawning = true;
        this.setSize(1.375F, 0.5625F);
        
        this.boatPowerCapacity = ModConfig.boatPowerCapacity;
        this.boatPowerUsage = ModConfig.boatPowerUsage;
        this.boatBoostModifier = ModConfig.boatBoostModifier;
    }

    public EntityMotorboat(World worldIn, double x, double y, double z)
    {
        this(worldIn);
        this.setPosition(x, y, z);
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.prevPosX = x;
        this.prevPosY = y;
        this.prevPosZ = z;
    }

    /**
     * returns if this entity triggers Block.onEntityWalking on the blocks they walk on. used for spiders and wolves to
     * prevent them from trampling crops
     */
    protected boolean canTriggerWalking()
    {
        return false;
    }

    protected void entityInit()
    {
        this.dataManager.register(TIME_SINCE_HIT, Integer.valueOf(0));
        this.dataManager.register(FORWARD_DIRECTION, Integer.valueOf(1));
        this.dataManager.register(DAMAGE_TAKEN, Float.valueOf(0.0F));
        this.dataManager.register(BOAT_TYPE, Integer.valueOf(EntityMotorboat.Type.OAK.ordinal()));

        for (DataParameter<Boolean> dataparameter : DATA_ID_PADDLE)
        {
            this.dataManager.register(dataparameter, Boolean.valueOf(false));
        }

		this.dataManager.register(SHADER, ItemStack.EMPTY);
		this.dataManager.register(PADDLES, false);
		this.dataManager.register(STORAGE, false);
		this.dataManager.register(BOOSTER, false);
		this.dataManager.register(MAGNET, false);
		this.dataManager.register(POWER, 0);
    }

    /**
     * Returns a boundingBox used to collide the entity with other entities and blocks. This enables the entity to be
     * pushable on contact, like boats or minecarts.
     */
    @Nullable
    public AxisAlignedBB getCollisionBox(Entity entityIn)
    {
        return entityIn.canBePushed() ? entityIn.getEntityBoundingBox() : null;
    }

    /**
     * Returns the collision bounding box for this entity
     */
    @Nullable
    public AxisAlignedBB getCollisionBoundingBox()
    {
        return this.getEntityBoundingBox();
    }

    /**
     * Returns true if this entity should push and be pushed by other entities when colliding.
     */
    public boolean canBePushed()
    {
        return true;
    }

    /**
     * Returns the Y offset from the entity's position for any entity riding this one.
     */
    public double getMountedYOffset()
    {
        return -0.1D;
    }

    /**
     * Called when the entity is attacked.
     */
    public boolean attackEntityFrom(DamageSource source, float amount)
    {
        if (this.isEntityInvulnerable(source))
        {
            return false;
        }
        else if (!this.world.isRemote && !this.isDead)
        {
            if (source instanceof EntityDamageSourceIndirect && source.getTrueSource() != null && this.isPassenger(source.getTrueSource()))
            {
                return false;
            }
            else
            {
                this.setForwardDirection(-this.getForwardDirection());
                this.setTimeSinceHit(10);
                this.setDamageTaken(this.getDamageTaken() + amount * 10.0F);
                this.markVelocityChanged();
                boolean flag = source.getTrueSource() instanceof EntityPlayer && ((EntityPlayer)source.getTrueSource()).capabilities.isCreativeMode;

                if (flag || this.getDamageTaken() > 40.0F)
                {
                    if (!flag && this.world.getGameRules().getBoolean("doEntityDrops"))
                    {
                    	this.entityDropItem(getBoatItemStack(), 0.0F);
                    }

                    this.setDead();
                }

                return true;
            }
        }
        else
        {
            return true;
        }
    }
    
    /**
     * Gets the ItemStack dropped.
     * 
     * @return Complete ItemStack with all its upgrades.
     */
    private ItemStack getBoatItemStack()
    {
    	ItemStack boat = new ItemStack(ModItems.itemMotorboat);
		if (getShader().getItem() instanceof ItemShader && ((ItemShader) getShader().getItem()).getShader(getShader()) != null) ItemUtil.addItemToStack(boat, getShader().copy(), "SHADER");
		if (hasPaddles()) ItemUtil.addItemToStack(boat, ModItems.createItemStack(ModItems.itemSimpleUpgrades, "paddles", 1), "PADDLE");
		if (hasStorage()) ItemUtil.addItemToStack(boat, ModItems.createItemStack(ModItems.itemSimpleUpgrades, "storagebox", 1), "STORAGE");
		if (hasBooster()) ItemUtil.addItemToStack(boat, ModItems.createItemStack(ModItems.itemSimpleUpgrades, "boatbooster", 1), "BOOSTER");
		//if (hasMagnet()) ItemUtil.addItemToStack(boat, ModItems.createItemStack(ModItems.itemSimpleUpgrades, "magnetizer", 1), "MAGNET");
		((ItemMotorboat) boat.getItem()).setEnergy(boat, getPowerStored());
		return boat;
    }
    
    /**
     * Called when a user uses the creative pick block button on this entity.
     *
     * @param target The full target the player is looking at
     * @return A ItemStack to add to the player's inventory, empty ItemStack if nothing should be added.
     */
    public ItemStack getPickedResult(RayTraceResult target)
    {
    	return getBoatItemStack();
    }

    /**
     * Applies a velocity to the entities, to push them away from eachother.
     */
    public void applyEntityCollision(Entity entityIn)
    {
        if (entityIn instanceof EntityMotorboat)
        {
            if (entityIn.getEntityBoundingBox().minY < this.getEntityBoundingBox().maxY)
            {
                super.applyEntityCollision(entityIn);
            }
        }
        else if (entityIn.getEntityBoundingBox().minY <= this.getEntityBoundingBox().minY)
        {
            super.applyEntityCollision(entityIn);
        }
    }

    /**
     * Setups the entity to do the hurt animation. Only used by packets in multiplayer.
     */
    @SideOnly(Side.CLIENT)
    public void performHurtAnimation()
    {
        this.setForwardDirection(-this.getForwardDirection());
        this.setTimeSinceHit(10);
        this.setDamageTaken(this.getDamageTaken() * 11.0F);
    }

    /**
     * Returns true if other Entities should be prevented from moving through this Entity.
     */
    public boolean canBeCollidedWith()
    {
        return !this.isDead;
    }

    /**
     * Set the position and rotation values directly without any clamping.
     */
    @SideOnly(Side.CLIENT)
    public void setPositionAndRotationDirect(double x, double y, double z, float yaw, float pitch, int posRotationIncrements, boolean teleport)
    {
        this.boatPitch = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.boatYaw = (double)yaw;
        this.lerpXRot = (double)pitch;
        this.lerpSteps = 10;
    }

    /**
     * Gets the horizontal facing direction of this Entity, adjusted to take specially-treated entity types into
     * account.
     */
    public EnumFacing getAdjustedHorizontalFacing()
    {
        return this.getHorizontalFacing().rotateY();
    }

    /**
     * Called to update the entity's position/logic.
     */
    public void onUpdate()
    {
        this.previousStatus = this.status;
        this.status = this.getBoatStatus();

        if (this.status != EntityMotorboat.Status.UNDER_WATER && this.status != EntityMotorboat.Status.UNDER_FLOWING_WATER)
        {
            this.outOfControlTicks = 0.0F;
        }
        else
        {
            ++this.outOfControlTicks;
        }

        if (!this.world.isRemote && this.outOfControlTicks >= 60.0F)
        {
            this.removePassengers();
        }

        if (this.getTimeSinceHit() > 0)
        {
            this.setTimeSinceHit(this.getTimeSinceHit() - 1);
        }

        if (this.getDamageTaken() > 0.0F)
        {
            this.setDamageTaken(this.getDamageTaken() - 1.0F);
        }

        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;
        
        //super.onUpdate();
        if (!this.world.isRemote)
        {
            this.setFlag(6, this.isGlowing());
        }

        this.onEntityUpdate();
        
        this.tickLerp();

        if (this.canPassengerSteer())
        {
            if (this.getPassengers().isEmpty() || !(this.getPassengers().get(0) instanceof EntityPlayer))
            {
                this.setPaddleState(false, false);
            }

            this.updateMotion();

            if (this.world.isRemote)
            {
                this.controlBoat();
                this.world.sendPacketToServer(new CPacketSteerBoat(this.getPaddleState(0), this.getPaddleState(1)));
            }

            this.move(MoverType.SELF, this.motionX, this.motionY, this.motionZ);
        }
        else
        {
            this.motionX = 0.0D;
            this.motionY = 0.0D;
            this.motionZ = 0.0D;
        }
        
        if (!this.isUsingPaddles())
		{
			if (this.getPaddleState(0))
			{
				this.paddlePositions[0] = (float)((double)this.paddlePositions[0] + (isBoosting ? 0.02D : 0.01D));
			}
			else if (this.getPaddleState(1))
			{
				this.paddlePositions[0] = (float)((double)this.paddlePositions[0] - 0.01D);
			}
		}
		else
		{
			for (int i = 0; i <= 1; ++i)
			{
				if (this.getPaddleState(i))
				{
					if (!this.isSilent() && (double)(this.paddlePositions[i] % ((float)Math.PI * 2F)) <= (Math.PI / 4D) && ((double)this.paddlePositions[i] + 0.39269909262657166D) % (Math.PI * 2D) >= (Math.PI / 4D))
	                {
	                    SoundEvent soundevent = this.getPaddleSound();

	                    if (soundevent != null)
	                    {
	                        Vec3d vec3d = this.getLook(1.0F);
	                        double d0 = i == 1 ? -vec3d.z : vec3d.z;
	                        double d1 = i == 1 ? vec3d.x : -vec3d.x;
	                        this.world.playSound((EntityPlayer)null, this.posX + d0, this.posY, this.posZ + d1, soundevent, this.getSoundCategory(), 1.0F, 0.8F + 0.4F * this.rand.nextFloat());
	                    }
	                }
					this.paddlePositions[i] = (float)((double)this.paddlePositions[i] + 0.39269909262657166D);
				}
				else
				{
					this.paddlePositions[i] = 0.0F;
				}
			}
		}

        this.doBlockCollisions();
        List<Entity> list = this.world.getEntitiesInAABBexcluding(this, this.getEntityBoundingBox().grow(0.20000000298023224D, -0.009999999776482582D, 0.20000000298023224D), EntitySelectors.getTeamCollisionPredicate(this));

        if (!list.isEmpty())
        {
            boolean flag = !this.world.isRemote && !(this.getControllingPassenger() instanceof EntityPlayer);

            for (int j = 0; j < list.size(); ++j)
            {
                Entity entity = list.get(j);

                if (!entity.isPassenger(this))
                {
                    if (flag && this.getPassengers().size() < 2 && !entity.isRiding() && entity.width < this.width && entity instanceof EntityLivingBase && !(entity instanceof EntityWaterMob) && !(entity instanceof EntityPlayer))
                    {
                        entity.startRiding(this);
                    }
                    else
                    {
                        this.applyEntityCollision(entity);
                    }
                }
            }
        }
    }

    @Nullable
    protected SoundEvent getPaddleSound()
    {
        switch (this.getBoatStatus())
        {
            case IN_WATER:
            case UNDER_WATER:
            case UNDER_FLOWING_WATER:
                return SoundEvents.ENTITY_BOAT_PADDLE_WATER;
            case ON_LAND:
                return SoundEvents.ENTITY_BOAT_PADDLE_LAND;
            case IN_AIR:
            default:
                return null;
        }
    }

    private void tickLerp()
    {
        if (this.lerpSteps > 0 && !this.canPassengerSteer())
        {
            double d0 = this.posX + (this.boatPitch - this.posX) / (double)this.lerpSteps;
            double d1 = this.posY + (this.lerpY - this.posY) / (double)this.lerpSteps;
            double d2 = this.posZ + (this.lerpZ - this.posZ) / (double)this.lerpSteps;
            double d3 = MathHelper.wrapDegrees(this.boatYaw - (double)this.rotationYaw);
            this.rotationYaw = (float)((double)this.rotationYaw + d3 / (double)this.lerpSteps);
            this.rotationPitch = (float)((double)this.rotationPitch + (this.lerpXRot - (double)this.rotationPitch) / (double)this.lerpSteps);
            --this.lerpSteps;
            this.setPosition(d0, d1, d2);
            this.setRotation(this.rotationYaw, this.rotationPitch);
        }
    }

    public void setPaddleState(boolean left, boolean right)
    {
        this.dataManager.set(DATA_ID_PADDLE[0], Boolean.valueOf(left));
        this.dataManager.set(DATA_ID_PADDLE[1], Boolean.valueOf(right));
    }

    @SideOnly(Side.CLIENT)
    public float getRowingTime(int side, float limbSwing)
    {
        if (isUsingPaddles())
		{
        	return this.getPaddleState(side) ? (float)MathHelper.clampedLerp((double)this.paddlePositions[side] - 0.39269909262657166D, (double)this.paddlePositions[side], (double)limbSwing) : 0.0F;
		}
		
		if (this.getPaddleState(0))
		{
			return (float)MathHelper.clampedLerp((double)this.paddlePositions[side] -  (isBoosting ? 0.02D : 0.01D), (double)this.paddlePositions[side], (double)limbSwing);
		}
		else
		{
			return this.getPaddleState(1) ? (float)MathHelper.clampedLerp((double)this.paddlePositions[side] +  0.01D, (double)this.paddlePositions[side], (double)limbSwing) : this.paddlePositions[side];
		}
    }

    /**
     * Determines whether the boat is in water, gliding on land, or in air
     */
    private EntityMotorboat.Status getBoatStatus()
    {
        EntityMotorboat.Status EntityMotorboat$status = this.getUnderwaterStatus();

        if (EntityMotorboat$status != null)
        {
            this.waterLevel = this.getEntityBoundingBox().maxY;
            return EntityMotorboat$status;
        }
        else if (this.checkInWater())
        {
            return EntityMotorboat.Status.IN_WATER;
        }
        else
        {
            float f = this.getBoatGlide();

            if (f > 0.0F)
            {
                this.boatGlide = f;
                return EntityMotorboat.Status.ON_LAND;
            }
            else
            {
                return EntityMotorboat.Status.IN_AIR;
            }
        }
    }

    public float getWaterLevelAbove()
    {
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.maxY);
        int l = MathHelper.ceil(axisalignedbb.maxY - this.lastYd);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        BlockPos.PooledMutableBlockPos blockpos$pooledmutableblockpos = BlockPos.PooledMutableBlockPos.retain();

        try
        {
            label108:

            for (int k1 = k; k1 < l; ++k1)
            {
                float f = 0.0F;
                int l1 = i;

                while (true)
                {
                    if (l1 >= j)
                    {
                        if (f < 1.0F)
                        {
                            float f2 = (float)blockpos$pooledmutableblockpos.getY() + f;
                            return f2;
                        }

                        break;
                    }

                    for (int i2 = i1; i2 < j1; ++i2)
                    {
                        blockpos$pooledmutableblockpos.setPos(l1, k1, i2);
                        IBlockState iblockstate = this.world.getBlockState(blockpos$pooledmutableblockpos);

                        if (iblockstate.getMaterial() == Material.WATER)
                        {
                            f = Math.max(f, BlockLiquid.getBlockLiquidHeight(iblockstate, this.world, blockpos$pooledmutableblockpos));
                        }

                        if (f >= 1.0F)
                        {
                            continue label108;
                        }
                    }

                    ++l1;
                }
            }

            float f1 = (float)(l + 1);
            return f1;
        }
        finally
        {
            blockpos$pooledmutableblockpos.release();
        }
    }

    /**
     * Decides how much the boat should be gliding on the land (based on any slippery blocks)
     */
    public float getBoatGlide()
    {
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        AxisAlignedBB axisalignedbb1 = new AxisAlignedBB(axisalignedbb.minX, axisalignedbb.minY - 0.001D, axisalignedbb.minZ, axisalignedbb.maxX, axisalignedbb.minY, axisalignedbb.maxZ);
        int i = MathHelper.floor(axisalignedbb1.minX) - 1;
        int j = MathHelper.ceil(axisalignedbb1.maxX) + 1;
        int k = MathHelper.floor(axisalignedbb1.minY) - 1;
        int l = MathHelper.ceil(axisalignedbb1.maxY) + 1;
        int i1 = MathHelper.floor(axisalignedbb1.minZ) - 1;
        int j1 = MathHelper.ceil(axisalignedbb1.maxZ) + 1;
        List<AxisAlignedBB> list = Lists.<AxisAlignedBB>newArrayList();
        float f = 0.0F;
        int k1 = 0;
        BlockPos.PooledMutableBlockPos blockpos$pooledmutableblockpos = BlockPos.PooledMutableBlockPos.retain();

        try
        {
            for (int l1 = i; l1 < j; ++l1)
            {
                for (int i2 = i1; i2 < j1; ++i2)
                {
                    int j2 = (l1 != i && l1 != j - 1 ? 0 : 1) + (i2 != i1 && i2 != j1 - 1 ? 0 : 1);

                    if (j2 != 2)
                    {
                        for (int k2 = k; k2 < l; ++k2)
                        {
                            if (j2 <= 0 || k2 != k && k2 != l - 1)
                            {
                                blockpos$pooledmutableblockpos.setPos(l1, k2, i2);
                                IBlockState iblockstate = this.world.getBlockState(blockpos$pooledmutableblockpos);
                                iblockstate.addCollisionBoxToList(this.world, blockpos$pooledmutableblockpos, axisalignedbb1, list, this, false);

                                if (!list.isEmpty())
                                {
                                    f += iblockstate.getBlock().getSlipperiness(iblockstate, this.world, blockpos$pooledmutableblockpos, this);
                                    ++k1;
                                }

                                list.clear();
                            }
                        }
                    }
                }
            }
        }
        finally
        {
            blockpos$pooledmutableblockpos.release();
        }

        return f / (float)k1;
    }

    private boolean checkInWater()
    {
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.ceil(axisalignedbb.minY + 0.001D);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        this.waterLevel = Double.MIN_VALUE;
        BlockPos.PooledMutableBlockPos blockpos$pooledmutableblockpos = BlockPos.PooledMutableBlockPos.retain();

        try
        {
            for (int k1 = i; k1 < j; ++k1)
            {
                for (int l1 = k; l1 < l; ++l1)
                {
                    for (int i2 = i1; i2 < j1; ++i2)
                    {
                        blockpos$pooledmutableblockpos.setPos(k1, l1, i2);
                        IBlockState iblockstate = this.world.getBlockState(blockpos$pooledmutableblockpos);

                        if (iblockstate.getMaterial() == Material.WATER)
                        {
                            float f = BlockLiquid.getLiquidHeight(iblockstate, this.world, blockpos$pooledmutableblockpos);
                            this.waterLevel = Math.max((double)f, this.waterLevel);
                            flag |= axisalignedbb.minY < (double)f;
                        }
                    }
                }
            }
        }
        finally
        {
            blockpos$pooledmutableblockpos.release();
        }

        return flag;
    }

    /**
     * Decides whether the boat is currently underwater.
     */
    @Nullable
    private EntityMotorboat.Status getUnderwaterStatus()
    {
        AxisAlignedBB axisalignedbb = this.getEntityBoundingBox();
        double d0 = axisalignedbb.maxY + 0.001D;
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.ceil(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.maxY);
        int l = MathHelper.ceil(d0);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.ceil(axisalignedbb.maxZ);
        boolean flag = false;
        BlockPos.PooledMutableBlockPos blockpos$pooledmutableblockpos = BlockPos.PooledMutableBlockPos.retain();

        try
        {
            for (int k1 = i; k1 < j; ++k1)
            {
                for (int l1 = k; l1 < l; ++l1)
                {
                    for (int i2 = i1; i2 < j1; ++i2)
                    {
                        blockpos$pooledmutableblockpos.setPos(k1, l1, i2);
                        IBlockState iblockstate = this.world.getBlockState(blockpos$pooledmutableblockpos);

                        if (iblockstate.getMaterial() == Material.WATER && d0 < (double)BlockLiquid.getLiquidHeight(iblockstate, this.world, blockpos$pooledmutableblockpos))
                        {
                            if (((Integer)iblockstate.getValue(BlockLiquid.LEVEL)).intValue() != 0)
                            {
                                EntityMotorboat.Status EntityMotorboat$status = EntityMotorboat.Status.UNDER_FLOWING_WATER;
                                return EntityMotorboat$status;
                            }

                            flag = true;
                        }
                    }
                }
            }
        }
        finally
        {
            blockpos$pooledmutableblockpos.release();
        }

        return flag ? EntityMotorboat.Status.UNDER_WATER : null;
    }

    /**
     * Update the boat's speed, based on momentum.
     */
    private void updateMotion()
    {
        double d0 = -0.03999999910593033D;
        double d1 = this.hasNoGravity() ? 0.0D : -0.03999999910593033D;
        double d2 = 0.0D;
        this.momentum = 0.05F;

        if (this.previousStatus == EntityMotorboat.Status.IN_AIR && this.status != EntityMotorboat.Status.IN_AIR && this.status != EntityMotorboat.Status.ON_LAND)
        {
            this.waterLevel = this.getEntityBoundingBox().minY + (double)this.height;
            this.setPosition(this.posX, (double)(this.getWaterLevelAbove() - this.height) + 0.101D, this.posZ);
            this.motionY = 0.0D;
            this.lastYd = 0.0D;
            this.status = EntityMotorboat.Status.IN_WATER;
        }
        else
        {
            if (this.status == EntityMotorboat.Status.IN_WATER)
            {
                d2 = (this.waterLevel - this.getEntityBoundingBox().minY) / (double)this.height;
                this.momentum = 0.9F;
            }
            else if (this.status == EntityMotorboat.Status.UNDER_FLOWING_WATER)
            {
                d1 = -7.0E-4D;
                this.momentum = 0.9F;
            }
            else if (this.status == EntityMotorboat.Status.UNDER_WATER)
            {
                d2 = 0.009999999776482582D;
                this.momentum = 0.45F;
            }
            else if (this.status == EntityMotorboat.Status.IN_AIR)
            {
                this.momentum = 0.9F;
            }
            else if (this.status == EntityMotorboat.Status.ON_LAND)
            {
                this.momentum = this.boatGlide;

                if (this.getControllingPassenger() instanceof EntityPlayer)
                {
                    this.boatGlide /= 2.0F;
                }
            }

            this.motionX *= (double)this.momentum;
            this.motionZ *= (double)this.momentum;
            this.deltaRotation *= this.momentum;
            this.motionY += d1;

            if (d2 > 0.0D)
            {
                double d3 = 0.65D;
                this.motionY += d2 * 0.06153846016296973D;
                double d4 = 0.75D;
                this.motionY *= 0.75D;
            }
        }
    }

    private void controlBoat()
    {
        if (this.isBeingRidden())
        {
            float f = 0.0F;
            if (isUsingPaddles())
			{
	            if (this.leftInputDown)
	            {
	                this.deltaRotation += -1.0F;
	            }
	
	            if (this.rightInputDown)
	            {
	                ++this.deltaRotation;
	            }
	            
	            if (this.rightInputDown != this.leftInputDown && !this.forwardInputDown && !this.backInputDown)
	            {
	                f += 0.005F;
	            }
	            
	            this.rotationYaw += this.deltaRotation;
	            
	            if (this.forwardInputDown)
	            {
	                f += 0.04F;
	            }
	            
	            if (this.backInputDown)
	            {
	                f -= 0.005F;
	            }
	            
	            this.motionX += (double)(MathHelper.sin(-this.rotationYaw * 0.017453292F) * f);
	            this.motionZ += (double)(MathHelper.cos(this.rotationYaw * 0.017453292F) * f);
	            this.setPaddleState(this.rightInputDown && !this.leftInputDown || this.forwardInputDown, this.leftInputDown && !this.rightInputDown || this.forwardInputDown);
			}
            else
			{
				this.rotationYaw += this.deltaRotation;
				
				if (this.getPowerStored() >= this.boatPowerUsage && (forwardInputDown || backInputDown))
				{
					int usage = this.boatPowerUsage;
					if (this.forwardInputDown)
					{
						f += 0.04F * 1.25F;
						if (this.isBoosting && this.getPowerStored() >= this.boatBoostModifier * this.boatPowerUsage)
						{
							f *= 1.6;
							usage *= this.boatBoostModifier;
						}
					}
		
					if (this.backInputDown)
					{
						f -= 0.005F * 2F;
					}
					this.setPowerStored(this.getPowerStored() - usage);
					DSEPacketHandler.INSTANCE.sendToServer(new ConsumeBoatPowerPacket(usage));
					
					this.setPaddleState(this.forwardInputDown, this.backInputDown);
				}
				else
				{
					this.setPaddleState(false, false);
				}
				
				this.motionX += (double)(MathHelper.sin(-this.rotationYaw * 0.017453292F) * f);
				this.motionZ += (double)(MathHelper.cos(this.rotationYaw * 0.017453292F) * f);
				
				float speed = (float) Math.sqrt(motionX * motionX + motionZ * motionZ);
				
				if (this.leftInputDown)
				{
					if (this.backInputDown && !this.forwardInputDown) this.deltaRotation += 1.1F  * speed * 1.5F * (isBoosting ? 0.5F : 1) * (backInputDown && !forwardInputDown ? 2F : 1F);
					else this.deltaRotation += -1.1F * speed * 1.5F * (isBoosting ? 0.5F : 1) * (backInputDown && !forwardInputDown ? 2F : 1F);
				}
				
				if (this.rightInputDown)
				{
					if (this.backInputDown && !this.forwardInputDown) this.deltaRotation += -1.1F * speed * 1.5F * (isBoosting ? 0.5F : 1) * (backInputDown && !forwardInputDown ? 2F : 1F);
					else this.deltaRotation += 1.1F  * speed * 1.5F * (isBoosting ? 0.5F : 1) * (backInputDown && !forwardInputDown ? 2F : 1F);
				}
			}
        }
    }

    public void updatePassenger(Entity passenger)
    {
        if (this.isPassenger(passenger))
        {
            float f = 0.0F;
            float f1 = (float)((this.isDead ? 0.009999999776482582D : this.getMountedYOffset()) + passenger.getYOffset());
            
            if (this.getPassengers().size() > 1)
            {
                int i = this.getPassengers().indexOf(passenger);
                
                if (i == 0)
                {
                    f = 0.2F;
                }
                else
                {
                    f = -0.6F;
                }
                
                if (passenger instanceof EntityAnimal)
                {
                    f = (float)((double)f + 0.2D);
                }
            }
            
            Vec3d vec3d = (new Vec3d((double)f, 0.0D, 0.0D)).rotateYaw(-this.rotationYaw * 0.017453292F - ((float)Math.PI / 2F));
            passenger.setPosition(this.posX + vec3d.x, this.posY + (double)f1, this.posZ + vec3d.z);
            passenger.rotationYaw += this.deltaRotation;
            passenger.setRotationYawHead(passenger.getRotationYawHead() + this.deltaRotation);
            this.applyYawToEntity(passenger);
            
            if (passenger instanceof EntityAnimal && this.getPassengers().size() > 1)
            {
                int j = passenger.getEntityId() % 2 == 0 ? 90 : 270;
                passenger.setRenderYawOffset(((EntityAnimal)passenger).renderYawOffset + (float)j);
                passenger.setRotationYawHead(passenger.getRotationYawHead() + (float)j);
            }
        }
    }

    /**
     * Applies this boat's yaw to the given entity. Used to update the orientation of its passenger.
     */
    protected void applyYawToEntity(Entity entityToUpdate)
    {
        entityToUpdate.setRenderYawOffset(this.rotationYaw);
        float f = MathHelper.wrapDegrees(entityToUpdate.rotationYaw - this.rotationYaw);
        float f1 = MathHelper.clamp(f, -105.0F, 105.0F);
        entityToUpdate.prevRotationYaw += f1 - f;
        entityToUpdate.rotationYaw += f1 - f;
        entityToUpdate.setRotationYawHead(entityToUpdate.rotationYaw);
    }

    /**
     * Applies this entity's orientation (pitch/yaw) to another entity. Used to update passenger orientation.
     */
    @SideOnly(Side.CLIENT)
    public void applyOrientationToEntity(Entity entityToUpdate)
    {
        this.applyYawToEntity(entityToUpdate);
    }

    /**
     * (abstract) Protected helper method to write subclass entity data to NBT.
     */
    protected void writeEntityToNBT(NBTTagCompound compound)
    {
        compound.setString("Type", this.getBoatType().getName());
        
        NBTTagCompound shaderTag = new NBTTagCompound();
    	if (!getShader().isEmpty())
    	{
    		getShader().writeToNBT(shaderTag);
    		compound.setTag("SHADER", shaderTag);
    	}
    	compound.setBoolean("PADDLES", hasPaddles());
    	compound.setBoolean("STORAGE", hasStorage());
    	compound.setBoolean("BOOSTER", hasBooster());
    	//compound.setBoolean("MAGNET", hasMagnet());
    	compound.setInteger("POWER", getPowerStored());
    }

    /**
     * (abstract) Protected helper method to read subclass entity data from NBT.
     */
    protected void readEntityFromNBT(NBTTagCompound compound)
    {
        if (compound.hasKey("Type", 8))
        {
            this.setBoatType(EntityMotorboat.Type.getTypeFromString(compound.getString("Type")));
        }
        
        if (compound.hasKey("SHADER")) setShader(new ItemStack((NBTTagCompound) compound.getTag("SHADER")));
        if (compound.hasKey("PADDLES") && compound.getBoolean("PADDLES")) setHasPaddles(true);
        if (compound.hasKey("STORAGE") && compound.getBoolean("STORAGE")) setHasStorage(true);
        if (compound.hasKey("BOOSTER") && compound.getBoolean("BOOSTER")) setHasBooster(true);
        //if (compound.hasKey("MAGNET") && compound.getBoolean("MAGNET")) setHasMagnet();
        if (compound.hasKey("POWER")) setPowerStored(compound.getInteger("POWER"));
    }

    public boolean processInitialInteract(EntityPlayer player, EnumHand hand)
    {
        if (player.isSneaking())
        {
        	if (ItemHelper.isToolWrench(player, player.getHeldItemMainhand(), getPosition().getX(), getPosition().getY(), getPosition().getZ()))
            {
            	if (!this.world.isRemote)
            	{
            		ItemStack boat = getBoatItemStack();
            		if (!this.world.isRemote && !player.inventory.addItemStackToInventory(boat))
            		{
            			this.entityDropItem(boat, 0.0F);
            		}
            		else this.entityDropItem(boat, 0.0F);
                	this.setDead();
                	ItemHelper.useWrench(player, player.getHeldItemMainhand(), getPosition().getX(), getPosition().getY(), getPosition().getZ());
            	}
            	player.swingArm(hand);
                return true;
            }
        	return false;
        }
        else
        {
            if (!this.world.isRemote && this.outOfControlTicks < 60.0F)
            {
                player.startRiding(this);
            }

            return true;
        }
    }

    protected void updateFallState(double y, boolean onGroundIn, IBlockState state, BlockPos pos)
    {
        this.lastYd = this.motionY;

        if (!this.isRiding())
        {
            if (onGroundIn)
            {
                if (this.fallDistance > 3.0F)
                {
                    if (this.status != EntityMotorboat.Status.ON_LAND)
                    {
                        this.fallDistance = 0.0F;
                        return;
                    }

                    this.fall(this.fallDistance, 1.0F);

                    if (!this.world.isRemote && !this.isDead)
                    {
                        this.setDead();

                        if (this.world.getGameRules().getBoolean("doEntityDrops"))
                        {
                            for (int i = 0; i < 3; ++i)
                            {
                                this.entityDropItem(new ItemStack(Item.getItemFromBlock(Blocks.PLANKS), 1, this.getBoatType().getMetadata()), 0.0F);
                            }

                            for (int j = 0; j < 2; ++j)
                            {
                                //this.dropItemWithOffset(Items.STICK, 1, 0.0F);
                            	this.entityDropItem(getBoatItemStack(), 0.0F);
                            }
                        }
                    }
                }

                this.fallDistance = 0.0F;
            }
            else if (this.world.getBlockState((new BlockPos(this)).down()).getMaterial() != Material.WATER && y < 0.0D)
            {
                this.fallDistance = (float)((double)this.fallDistance - y);
            }
        }
    }

    public boolean getPaddleState(int side)
    {
        return ((Boolean)this.dataManager.get(DATA_ID_PADDLE[side])).booleanValue() && this.getControllingPassenger() != null;
    }

    /**
     * Sets the damage taken from the last hit.
     */
    public void setDamageTaken(float damageTaken)
    {
        this.dataManager.set(DAMAGE_TAKEN, Float.valueOf(damageTaken));
    }

    /**
     * Gets the damage taken from the last hit.
     */
    public float getDamageTaken()
    {
        return ((Float)this.dataManager.get(DAMAGE_TAKEN)).floatValue();
    }

    /**
     * Sets the time to count down from since the last time entity was hit.
     */
    public void setTimeSinceHit(int timeSinceHit)
    {
        this.dataManager.set(TIME_SINCE_HIT, Integer.valueOf(timeSinceHit));
    }

    /**
     * Gets the time since the last hit.
     */
    public int getTimeSinceHit()
    {
        return ((Integer)this.dataManager.get(TIME_SINCE_HIT)).intValue();
    }

    /**
     * Sets the forward direction of the entity.
     */
    public void setForwardDirection(int forwardDirection)
    {
        this.dataManager.set(FORWARD_DIRECTION, Integer.valueOf(forwardDirection));
    }
    
    /**
     * Gets the forward direction of the entity.
     */
    public int getForwardDirection()
    {
        return ((Integer)this.dataManager.get(FORWARD_DIRECTION)).intValue();
    }
    
    public void setBoatType(EntityMotorboat.Type boatType)
    {
        this.dataManager.set(BOAT_TYPE, Integer.valueOf(boatType.ordinal()));
    }
    
    public EntityMotorboat.Type getBoatType()
    {
        return EntityMotorboat.Type.byId(((Integer)this.dataManager.get(BOAT_TYPE)).intValue());
    }
    
    protected boolean canFitPassenger(Entity passenger)
    {
        return this.getPassengers().size() < 1;
    }
    
    /**
     * For vehicles, the first passenger is generally considered the controller and "drives" the vehicle. For example,
     * Pigs, Horses, and Boats are generally "steered" by the controlling passenger.
     */
    @Nullable
    public Entity getControllingPassenger()
    {
        List<Entity> list = this.getPassengers();
        return list.isEmpty() ? null : (Entity)list.get(0);
    }
    
    @SideOnly(Side.CLIENT)
    public void updateInputs(boolean p_184442_1_, boolean p_184442_2_, boolean p_184442_3_, boolean p_184442_4_)
    {
        this.leftInputDown = p_184442_1_;
        this.rightInputDown = p_184442_2_;
        this.forwardInputDown = p_184442_3_;
        this.backInputDown = p_184442_4_;
        this.isBoosting = isUsingPaddles() ? false : (forwardInputDown && Minecraft.getMinecraft().gameSettings.keyBindJump.isKeyDown() && hasBooster());
    }
    
    public static enum Status
    {
        IN_WATER,
        UNDER_WATER,
        UNDER_FLOWING_WATER,
        ON_LAND,
        IN_AIR;
    }
    
    // Forge: Fix MC-119811 by instantly completing lerp on board
    @Override
    protected void addPassenger(Entity passenger)
    {
        super.addPassenger(passenger);
        if(this.canPassengerSteer() && this.lerpSteps > 0)
        {
            this.lerpSteps = 0;
            this.posX = this.boatPitch;
            this.posY = this.lerpY;
            this.posZ = this.lerpZ;
            this.rotationYaw = (float)this.boatYaw;
            this.rotationPitch = (float)this.lerpXRot;
        }
    }
    
    public boolean isUsingPaddles()
    {
    	return (hasPaddles() ? (this.getPowerStored() < this.boatPowerUsage) : false);
    }
    
    public void setShader(ItemStack stack)
	{
		this.dataManager.set(SHADER, stack);
	}
	
	public ItemStack getShader()
	{
		return this.dataManager.get(SHADER);
	}
    
    public void setHasPaddles(boolean val)
	{
		this.dataManager.set(PADDLES, val);
	}
	
	public boolean hasPaddles()
	{
		return this.dataManager.get(PADDLES);
	}
    
    public void setHasStorage(boolean val)
	{
		this.dataManager.set(STORAGE, val);
	}
	
	public boolean hasStorage()
	{
		return this.dataManager.get(STORAGE);
	}
    
    public void setHasBooster(boolean val)
	{
		this.dataManager.set(BOOSTER, val);
	}
	
	public boolean hasBooster()
	{
		return this.dataManager.get(BOOSTER);
	}
    
    public void setHasMagnet()
	{
		this.dataManager.set(MAGNET, true);
	}
	
	public boolean hasMagnet()
	{
		return this.dataManager.get(MAGNET);
	}
	
	public void setPowerStored(int power)
	{
		this.dataManager.set(POWER, Math.min(this.boatPowerCapacity, Math.max(0, power)));
	}
	
	public int getPowerStored()
	{
		return Math.min(this.boatPowerCapacity, Math.max(0, this.dataManager.get(POWER)));
	}
	
	public int getMaxPowerStored()
	{
		return this.boatPowerCapacity;
	}
}