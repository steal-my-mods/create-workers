package com.createworkers.registry;

import java.util.List;
import java.util.Map;

import com.createworkers.CreateWorkers;
import com.createworkers.item.HardHatItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CWItems {

	public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
		DeferredRegister.create(Registries.ARMOR_MATERIAL, CreateWorkers.ID);

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateWorkers.ID);

	public static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateWorkers.ID);

	public static final DeferredHolder<ArmorMaterial, ArmorMaterial> HARD_HAT_MATERIAL =
		ARMOR_MATERIALS.register("hard_hat", () -> new ArmorMaterial(
			Map.of(ArmorItem.Type.HELMET, 2),
			9,
			SoundEvents.ARMOR_EQUIP_IRON,
			() -> Ingredient.of(Items.IRON_INGOT),
			List.of(new ArmorMaterial.Layer(CreateWorkers.asResource("hard_hat"))),
			0.0F,
			0.0F));

	public static final DeferredItem<HardHatItem> HARD_HAT = ITEMS.registerItem("hard_hat",
		props -> new HardHatItem(HARD_HAT_MATERIAL, ArmorItem.Type.HELMET, props),
		new Item.Properties().stacksTo(1)
			.durability(240));

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.createworkers"))
			.icon(() -> HARD_HAT.get()
				.getDefaultInstance())
			.displayItems((params, output) -> output.accept(HARD_HAT.get()))
			.build());
}
