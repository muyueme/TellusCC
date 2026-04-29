package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OceanClassificationTest {
   @Test
   void classifiesMarineWaterWithoutOceanHintAsOcean() {
      boolean ocean = OceanClassification.isOcean(false, TellusLandMaskSource.LandMaskSample.known(false), 62, 80, 63);

      assertTrue(ocean);
   }

   @Test
   void keepsBelowSeaInlandWaterAsInland() {
      boolean ocean = OceanClassification.isOcean(false, TellusLandMaskSource.LandMaskSample.known(true), 62, 80, 63);

      assertFalse(ocean);
   }
}
