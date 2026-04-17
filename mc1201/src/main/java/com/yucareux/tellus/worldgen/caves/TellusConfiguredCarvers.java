package com.yucareux.tellus.worldgen.caves;

import java.util.List;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.util.valueproviders.TrapezoidFloat;
import net.minecraft.util.valueproviders.UniformFloat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.CarverDebugSettings;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.CanyonCarverConfiguration.CanyonShapeConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;

final class TellusConfiguredCarvers {
   private static final float CAVE_PROBABILITY = 0.33F;
   private static final float CAVE_EXTRA_PROBABILITY = 0.18F;
   private static final float CANYON_PROBABILITY = 0.0225F;
   private final List<ConfiguredWorldCarver<?>> orderedCarvers;

   private TellusConfiguredCarvers(List<ConfiguredWorldCarver<?>> orderedCarvers) {
      this.orderedCarvers = orderedCarvers;
   }

   static TellusConfiguredCarvers create(Registry<Block> blockRegistry, int tellusMinY, int tellusHeight) {
      HolderSet<Block> replaceables = blockRegistry.getOrCreateTag(BlockTags.OVERWORLD_CARVER_REPLACEABLES);
      int caveCeilingY = TellusHeightRemapper.remapVanillaAbsolute(220, tellusMinY, tellusHeight);
      int caveExtraCeilingY = TellusHeightRemapper.remapVanillaAbsolute(96, tellusMinY, tellusHeight);
      int canyonFloorY = TellusHeightRemapper.remapVanillaAbsolute(24, tellusMinY, tellusHeight);
      int canyonCeilingY = TellusHeightRemapper.remapVanillaAbsolute(110, tellusMinY, tellusHeight);
      if (canyonFloorY > canyonCeilingY) {
         int tmp = canyonFloorY;
         canyonFloorY = canyonCeilingY;
         canyonCeilingY = tmp;
      }

      TellusCaveCarver cave = new TellusCaveCarver(
         new CaveCarverConfiguration(
            CAVE_PROBABILITY,
            UniformHeight.of(VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(caveCeilingY)),
            UniformFloat.of(0.1F, 0.9F),
            VerticalAnchor.aboveBottom(8),
            CarverDebugSettings.of(false, Blocks.CRIMSON_BUTTON.defaultBlockState()),
            replaceables,
            UniformFloat.of(0.7F, 1.4F),
            UniformFloat.of(0.8F, 1.3F),
            UniformFloat.of(-1.0F, -0.4F)
         )
      );
      TellusCaveCarver caveExtraUnderground = new TellusCaveCarver(
         new CaveCarverConfiguration(
            CAVE_EXTRA_PROBABILITY,
            UniformHeight.of(VerticalAnchor.aboveBottom(8), VerticalAnchor.absolute(caveExtraCeilingY)),
            UniformFloat.of(0.1F, 0.9F),
            VerticalAnchor.aboveBottom(8),
            CarverDebugSettings.of(false, Blocks.OAK_BUTTON.defaultBlockState()),
            replaceables,
            UniformFloat.of(0.7F, 1.4F),
            UniformFloat.of(0.8F, 1.3F),
            UniformFloat.of(-1.0F, -0.4F)
         )
      );
      CanyonShapeConfiguration canyonShape = new CanyonShapeConfiguration(
         UniformFloat.of(0.75F, 1.0F), TrapezoidFloat.of(0.0F, 6.0F, 2.0F), 3, UniformFloat.of(0.75F, 1.0F), 1.0F, 0.0F
      );
      TellusRavineCarver canyon = new TellusRavineCarver(
         new CanyonCarverConfiguration(
            CANYON_PROBABILITY,
            UniformHeight.of(VerticalAnchor.absolute(canyonFloorY), VerticalAnchor.absolute(canyonCeilingY)),
            ConstantFloat.of(3.0F),
            VerticalAnchor.aboveBottom(8),
            CarverDebugSettings.of(false, Blocks.WARPED_BUTTON.defaultBlockState()),
            replaceables,
            UniformFloat.of(-0.125F, 0.125F),
            canyonShape
         )
      );
      return new TellusConfiguredCarvers(List.of(cave.configured(), caveExtraUnderground.configured(), canyon.configured()));
   }

   List<ConfiguredWorldCarver<?>> orderedCarvers() {
      return this.orderedCarvers;
   }
}
