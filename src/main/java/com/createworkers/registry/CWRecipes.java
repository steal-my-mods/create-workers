package com.createworkers.registry;

import java.util.function.Supplier;

import com.createworkers.CreateWorkers;
import com.createworkers.recipe.ClearProgramRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Crafting recipes that only this mod knows how to assemble. */
public class CWRecipes {

	public static final DeferredRegister<RecipeSerializer<?>> REGISTER =
		DeferredRegister.create(Registries.RECIPE_SERIALIZER, CreateWorkers.ID);

	/** Crafting a hard hat by itself, which blanks the inventories programmed into it. */
	public static final Supplier<SimpleCraftingRecipeSerializer<ClearProgramRecipe>> CLEAR_PROGRAM =
		REGISTER.register("clear_program", () -> new SimpleCraftingRecipeSerializer<>(ClearProgramRecipe::new));
}
