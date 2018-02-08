package com.nhave.dse.registry;

import com.nhave.dse.Reference;
import com.nhave.dse.blocks.BlockCompressor;
import com.nhave.dse.client.render.RenderTileCompressor;
import com.nhave.dse.tileentity.TileEntityCompressor;
import com.nhave.nhc.itemblocks.ItemBlockBase;
import com.nhave.nhc.util.StringUtils;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent.Register;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class ModBlocks
{
	public static Block blockCompressor;
	
	public static void init()
	{
		blockCompressor = new BlockCompressor("compressor");
		
		GameRegistry.registerTileEntity(com.nhave.dse.tileentity.TileEntityCompressor.class, "TileCompressor");
	}
	
	public static void register(Register<Block> event)
	{
		event.getRegistry().register(blockCompressor);
	}
	
	public static void registerItemBlocks(Register<Item> event)
	{
		registerItemBlock(event, blockCompressor);
	}
	
	@SideOnly(Side.CLIENT)
	public static void registerRenders()
	{
		registerRender(blockCompressor);
	}
	
	@SideOnly(Side.CLIENT)
	public static void registerRenderData()
	{
		ClientRegistry.bindTileEntitySpecialRenderer(TileEntityCompressor.class, new RenderTileCompressor());
	}
	
	public static void registerItemBlock(Register<Item> event, Block block)
	{
		event.getRegistry().register(new ItemBlockBase(block, StringUtils.LIGHT_BLUE).setRegistryName(block.getRegistryName()));
	}
	
	@SideOnly(Side.CLIENT)
	public static void registerRender(Block block)
	{
		Item item = Item.getItemFromBlock(block);
		RenderItem renderItem = Minecraft.getMinecraft().getRenderItem();
		
		ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(Reference.MODID + ":" + item.getRegistryName().getResourcePath(), "inventory"));
	}
}