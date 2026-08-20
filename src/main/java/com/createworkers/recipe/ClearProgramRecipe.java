package com.createworkers.recipe;

import com.createworkers.item.HardHatItem;
import com.createworkers.program.WorkerProgram;
import com.createworkers.registry.CWItems;
import com.createworkers.registry.CWRecipes;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/**
 * Crafting a hard hat on its own wipes the inventories programmed into it — the same gesture that
 * blanks a Create filter, clipboard or train schedule.
 *
 * <p>Create writes those as plain shapeless recipes, because a vanilla crafting result is a
 * factory-fresh stack and a filter has nothing else worth keeping. A hard hat is armour: it wears
 * out and it can be enchanted, so a blank result would quietly repair it for free and eat its
 * enchantments. This recipe hands the very same hat back with only the program removed.
 */
public class ClearProgramRecipe extends ShapelessRecipe {

	public ClearProgramRecipe(CraftingBookCategory category) {
		super("", category, CWItems.HARD_HAT.get()
			.getDefaultInstance(), NonNullList.of(Ingredient.EMPTY, Ingredient.of(CWItems.HARD_HAT.get())));
	}

	@Override
	public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
		for (ItemStack stack : input.items()) {
			if (!stack.is(CWItems.HARD_HAT.get()))
				continue;
			ItemStack cleared = stack.copyWithCount(1);
			HardHatItem.setProgram(cleared, WorkerProgram.EMPTY);
			return cleared;
		}
		return super.assemble(input, registries);
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return CWRecipes.CLEAR_PROGRAM.get();
	}
}
