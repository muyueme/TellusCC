package com.yucareux.tellus.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(OceanMonumentStructure.class)
public class OceanMonumentStructureMixin {
   @Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"), cancellable = true)
   private static void tellus$preserveShiftedMonumentY(
      ChunkPos chunkPos, long seed, PiecesContainer loadedPieces, CallbackInfoReturnable<PiecesContainer> cir
   ) {
      PiecesContainer regeneratedPieces = cir.getReturnValue();
      if (!loadedPieces.isEmpty() && !regeneratedPieces.isEmpty()) {
         StructurePiece loadedRoot = loadedPieces.pieces().get(0);
         StructurePiece regeneratedRoot = regeneratedPieces.pieces().get(0);
         int offsetY = loadedRoot.getBoundingBox().minY() - regeneratedRoot.getBoundingBox().minY();
         if (offsetY != 0) {
            List<StructurePiece> movedPieces = new ArrayList<>(regeneratedPieces.pieces().size());

            for (StructurePiece piece : regeneratedPieces.pieces()) {
               piece.move(0, offsetY, 0);
               movedPieces.add(piece);
            }

            cir.setReturnValue(new PiecesContainer(movedPieces));
         }
      }
   }
}
