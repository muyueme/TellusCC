package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;

final class OceanClassification {
   private static final int ESA_NO_DATA = 0;

   private OceanClassification() {
   }

   static boolean isOcean(
      boolean oceanHint,
      TellusLandMaskSource.LandMaskSample landMaskSample,
      int surface,
      int coverClass,
      int seaLevel
   ) {
      if (oceanHint) {
         return true;
      } else if (landMaskSample != null && landMaskSample.known()) {
         return !landMaskSample.land() && surface <= seaLevel;
      } else {
         return coverClass == ESA_NO_DATA && surface <= seaLevel;
      }
   }
}
