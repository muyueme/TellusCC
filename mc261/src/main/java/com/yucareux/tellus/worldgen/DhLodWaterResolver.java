package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.OsmPerf;
import com.yucareux.tellus.world.data.osm.OsmQueryMode;
import com.yucareux.tellus.world.data.osm.OsmWaterFeature;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import net.minecraft.util.Mth;

public final class DhLodWaterResolver {
   private static final int ESA_NO_DATA = 0;
   private static final int ESA_MANGROVES = 95;
   private static final int MAX_RASTER_CACHE = intProperty("tellus.dhWaterRasterCacheSize", 128, 16, 2048);
   private static final int[] SAMPLE_OFFSETS_SINGLE = new int[]{0, 0};
   private static final int[] SAMPLE_OFFSETS_CELL_2 = new int[]{-1, -1, 0, -1, -1, 0, 0, 0};
   private static final int[] SAMPLE_OFFSETS_SMALL_GRID = new int[]{-1, -1, 0, -1, 1, -1, -1, 0, 0, 0, 1, 0, -1, 1, 0, 1, 1, 1};
   private static final int[] NEIGHBOR_OFFSETS = new int[]{1, 0, -1, 0, 0, 1, 0, -1};
   private final EarthChunkGenerator generator;
   private final EarthGeneratorSettings settings;
   private final TellusLandMaskSource landMaskSource;
   private final TellusOsmWaterSource osmWaterSource;
   private final Cache<DhLodWaterResolver.AreaKey, DhLodWaterResolver.RasterizedWaterArea> rasterCache;

   public DhLodWaterResolver(EarthChunkGenerator generator) {
      this.generator = generator;
      this.settings = generator.settings();
      this.landMaskSource = TellusWorldgenSources.landMask();
      this.osmWaterSource = TellusWorldgenSources.osmWater();
      this.rasterCache = CacheBuilder.newBuilder().maximumSize(MAX_RASTER_CACHE).build();
   }

   public DhLodWaterResolver.AreaResult resolveArea(
      int baseX,
      int baseZ,
      int lodSizePoints,
      int cellSize,
      int[] worldXs,
      int[] worldZs,
      int[] baseTerrainSurface,
      int[] coverClasses,
      boolean applyShorelineBlend
   ) {
      int area = lodSizePoints * lodSizePoints;
      if (area == 0) {
         return new DhLodWaterResolver.AreaResult(new int[0], new int[0], new boolean[0], new boolean[0]);
      } else if (worldXs.length != lodSizePoints
         || worldZs.length != lodSizePoints
         || baseTerrainSurface.length != area
         || coverClasses.length != area) {
         throw new IllegalArgumentException("Invalid DH water area input dimensions");
      } else {
         DhLodWaterResolver.RasterizedWaterArea rasterized = this.rasterizedWaterArea(baseX, baseZ, lodSizePoints, cellSize);
         int[] terrainSurface = Arrays.copyOf(baseTerrainSurface, area);
         int[] waterSurface = Arrays.copyOf(baseTerrainSurface, area);
         boolean[] hasWater = new boolean[area];
         boolean[] ocean = new boolean[area];
         boolean osmWaterEnabled = this.settings.enableWater();
         double worldScale = this.settings.worldScale();
         boolean[] renderWater = rasterized.renderWater();
         boolean[] rasterizedOcean = rasterized.ocean();
         boolean[] sampledOcean = rasterized.sampledOcean();
         int seaLevel = this.generator.getSeaLevel();

         for (int localZ = 0; localZ < lodSizePoints; localZ++) {
            throwIfCancelled();
            int worldZ = worldZs[localZ];
            int row = localZ * lodSizePoints;

            for (int localX = 0; localX < lodSizePoints; localX++) {
               int index = row + localX;
               int worldX = worldXs[localX];
               int coverClass = coverClasses[index];
               int surface = terrainSurface[index];
               boolean overtureWater = osmWaterEnabled && renderWater[index];
               boolean overtureOcean = overtureWater && rasterizedOcean[index];
               boolean sampledOceanCell = sampledOcean[index];
               boolean belowSeaLevel = surface <= seaLevel;
               boolean needsLandMask = shouldSampleLandMask(overtureWater, sampledOceanCell, belowSeaLevel);
               TellusLandMaskSource.LandMaskSample landMaskSample = needsLandMask
                  ? this.landMaskSource.sampleLandMask(worldX, worldZ, worldScale)
                  : null;
               boolean fallbackOcean = needsLandMask && isOceanFallback(landMaskSample, surface, coverClass, seaLevel);
               boolean cellHasWater = overtureWater || fallbackOcean;
               boolean cellOcean = overtureOcean || (!overtureWater && fallbackOcean);
               // Fast LODs should bias toward flooding sampled ocean cells that already sit below sea level.
               // Otherwise shallow shelves flash as exposed sand until full chunks replace the LOD.
               if (!cellHasWater && sampledOceanCell && belowSeaLevel) {
                  cellHasWater = true;
                  cellOcean = true;
               }
               int cellWaterSurface = surface;
               if (cellHasWater) {
                  cellWaterSurface = cellOcean ? seaLevel : Math.max(surface + 1, seaLevel);
                  if (coverClass == ESA_MANGROVES) {
                     int mangroveSurface = this.generator.resolveLodMangroveWaterSurface(worldX, worldZ, Math.max(seaLevel, cellWaterSurface));
                     cellWaterSurface = Math.max(cellWaterSurface, mangroveSurface);
                  }

                  WaterSurfaceResolver.WaterColumnData column = normalizeLodWaterColumn(this.generator.applyLodWaterDepthProfile(
                     new WaterSurfaceResolver.WaterColumnData(true, cellOcean, surface, cellWaterSurface)
                  ));
                  terrainSurface[index] = column.terrainSurface();
                  waterSurface[index] = column.waterSurface();
               } else if (coverClass == ESA_MANGROVES) {
                  int mangroveSurface = this.generator.resolveLodMangroveWaterSurface(worldX, worldZ, seaLevel);
                  if (mangroveSurface > surface) {
                     cellHasWater = true;
                     WaterSurfaceResolver.WaterColumnData column = normalizeLodWaterColumn(this.generator.applyLodWaterDepthProfile(
                        new WaterSurfaceResolver.WaterColumnData(true, false, surface, mangroveSurface)
                     ));
                     terrainSurface[index] = column.terrainSurface();
                     waterSurface[index] = column.waterSurface();
                  }
               }

               hasWater[index] = cellHasWater;
               ocean[index] = cellOcean;
               if (!cellHasWater) {
                  waterSurface[index] = terrainSurface[index];
               }
            }
         }

         if (applyShorelineBlend) {
            int inlandBlendCells = blendCells(this.settings.riverLakeShorelineBlend(), cellSize);
            int oceanBlendCells = blendCells(this.settings.oceanShorelineBlend(), cellSize);
            if (inlandBlendCells > 0 || oceanBlendCells > 0) {
               boolean[] inlandMask = new boolean[area];
               boolean[] oceanMask = new boolean[area];

               for (int i = 0; i < area; i++) {
                  if (hasWater[i]) {
                     if (ocean[i]) {
                        oceanMask[i] = true;
                     } else {
                        inlandMask[i] = true;
                     }
                  }
               }

               if (inlandBlendCells > 0) {
                  this.applyShorelineBlend(terrainSurface, baseTerrainSurface, waterSurface, inlandMask, lodSizePoints, inlandBlendCells);
               }

               if (oceanBlendCells > 0) {
                  this.applyShorelineBlend(terrainSurface, baseTerrainSurface, waterSurface, oceanMask, lodSizePoints, oceanBlendCells);
               }
            }
         }

         return new DhLodWaterResolver.AreaResult(terrainSurface, waterSurface, hasWater, ocean);
      }
   }

   private DhLodWaterResolver.RasterizedWaterArea rasterizedWaterArea(int baseX, int baseZ, int lodSizePoints, int cellSize) {
      int area = lodSizePoints * lodSizePoints;
      if (!this.settings.enableWater() || area == 0) {
         return DhLodWaterResolver.RasterizedWaterArea.dry(area);
      } else {
         DhLodWaterResolver.AreaKey key = new DhLodWaterResolver.AreaKey(baseX, baseZ, lodSizePoints, cellSize);
         DhLodWaterResolver.RasterizedWaterArea cached = this.rasterCache.getIfPresent(key);
         if (cached != null) {
            return cached;
         } else {
            DhLodWaterResolver.RasterizedWaterArea built = this.buildRasterizedWaterArea(baseX, baseZ, lodSizePoints, cellSize);
            this.rasterCache.put(key, built);
            return built;
         }
      }
   }

   private DhLodWaterResolver.RasterizedWaterArea buildRasterizedWaterArea(int baseX, int baseZ, int lodSizePoints, int cellSize) {
      int area = lodSizePoints * lodSizePoints;
      int cellOffset = cellSize >> 1;
      int halfCell = cellSize >> 1;
      int minBlockX = baseX + cellOffset - halfCell;
      int minBlockZ = baseZ + cellOffset - halfCell;
      int maxBlockX = baseX + (lodSizePoints - 1) * cellSize + cellOffset + halfCell - 1;
      int maxBlockZ = baseZ + (lodSizePoints - 1) * cellSize + cellOffset + halfCell - 1;
      double worldScale = this.settings.worldScale();
      long queryStartNs = OsmPerf.now();
      TellusOsmWaterSource.WaterQueryResult result = this.osmWaterSource
         .waterForAreaWithStatus(minBlockX, minBlockZ, maxBlockX, maxBlockZ, worldScale, 0, OsmQueryMode.BLOCKING);
      OsmPerf.recordWaterQuery(OsmPerf.elapsedSince(queryStartNs), result.features().size());
      List<OsmWaterFeature> features = result.features();
      if (features.isEmpty()) {
         return DhLodWaterResolver.RasterizedWaterArea.dry(area);
      } else {
         int[] wetSampleMask = new int[area];
         boolean[] oceanSample = new boolean[area];
         boolean[] lineSample = new boolean[area];
         int[] sampleOffsets = sampleOffsetsForCellSize(cellSize);
         int maxSampleOffset = maxSampleOffset(sampleOffsets);
         double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);

         for (OsmWaterFeature feature : features) {
            throwIfCancelled();
            this.rasterizeFeature(
               feature,
               baseX,
               baseZ,
               lodSizePoints,
               cellSize,
               cellOffset,
               sampleOffsets,
               maxSampleOffset,
               worldScale,
               blocksPerDegree,
               wetSampleMask,
               oceanSample,
               lineSample
            );
         }

         int totalSamples = sampleOffsets.length / 2;
         boolean[] renderWater = new boolean[area];
         boolean[] ocean = new boolean[area];

         for (int i = 0; i < area; i++) {
            int wetMask = wetSampleMask[i];
            if (wetMask != 0) {
               boolean render = shouldRenderExactWaterFootprint(Integer.bitCount(wetMask), totalSamples, oceanSample[i], lineSample[i]);
               renderWater[i] = render;
               ocean[i] = render && oceanSample[i];
            }
         }

         return new DhLodWaterResolver.RasterizedWaterArea(renderWater, ocean, oceanSample);
      }
   }

   private void rasterizeFeature(
      OsmWaterFeature feature,
      int baseX,
      int baseZ,
      int lodSizePoints,
      int cellSize,
      int cellOffset,
      int[] sampleOffsets,
      int maxSampleOffset,
      double worldScale,
      double blocksPerDegree,
      int[] wetSampleMask,
      boolean[] oceanSample,
      boolean[] lineSample
   ) {
      double minWorldX = feature.minLon() * blocksPerDegree;
      double maxWorldX = feature.maxLon() * blocksPerDegree;
      double minLatWorldZ = EarthProjection.latToBlockZ(feature.minLat(), worldScale);
      double maxLatWorldZ = EarthProjection.latToBlockZ(feature.maxLat(), worldScale);
      double minWorldZ = Math.min(minLatWorldZ, maxLatWorldZ);
      double maxWorldZ = Math.max(minLatWorldZ, maxLatWorldZ);
      int searchRadius = Math.max(cellSize >> 1, maxSampleOffset);
      int minCellX = cellIndexForWorld(minWorldX - searchRadius, baseX, cellOffset, cellSize, lodSizePoints);
      int maxCellX = cellIndexForWorld(maxWorldX + searchRadius, baseX, cellOffset, cellSize, lodSizePoints);
      int minCellZ = cellIndexForWorld(minWorldZ - searchRadius, baseZ, cellOffset, cellSize, lodSizePoints);
      int maxCellZ = cellIndexForWorld(maxWorldZ + searchRadius, baseZ, cellOffset, cellSize, lodSizePoints);
      if (maxCellX < minCellX || maxCellZ < minCellZ) {
         return;
      }

      for (int localZ = minCellZ; localZ <= maxCellZ; localZ++) {
         throwIfCancelled();
         int worldZ = baseZ + localZ * cellSize + cellOffset;
         int row = localZ * lodSizePoints;

         for (int localX = minCellX; localX <= maxCellX; localX++) {
            int worldX = baseX + localX * cellSize + cellOffset;
            int index = row + localX;
            int mask = wetSampleMask[index];

            for (int offsetIndex = 0, sampleBit = 1; offsetIndex < sampleOffsets.length; offsetIndex += 2, sampleBit <<= 1) {
               if ((mask & sampleBit) != 0) {
                  continue;
               }

               int sampleX = worldX + sampleOffsets[offsetIndex];
               int sampleZ = worldZ + sampleOffsets[offsetIndex + 1];
               if (feature.containsBlock(sampleX, sampleZ, worldScale)) {
                  mask |= sampleBit;
                  if (feature.oceanHint()) {
                     oceanSample[index] = true;
                  }

                  if (feature.lineGeometry()) {
                     lineSample[index] = true;
                  }
               }
            }

            wetSampleMask[index] = mask;
         }
      }
   }

   private void applyShorelineBlend(
      int[] terrainSurface, int[] baseTerrainSurface, int[] waterSurface, boolean[] waterMask, int lodSizePoints, int blendCells
   ) {
      if (blendCells > 0) {
         int area = lodSizePoints * lodSizePoints;
         int[] distance = new int[area];
         int[] nearestWaterSurface = new int[area];
         int[] queue = new int[area];
         Arrays.fill(distance, -1);
         int queueHead = 0;
         int queueTail = 0;
         boolean hasBoundary = false;

         for (int index = 0; index < area; index++) {
            if (waterMask[index]) {
               int x = index % lodSizePoints;
               int z = index / lodSizePoints;

               for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
                  int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
                  int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
                  if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                     int neighbor = nz * lodSizePoints + nx;
                     if (!waterMask[neighbor] && distance[neighbor] == -1) {
                        distance[neighbor] = 1;
                        nearestWaterSurface[neighbor] = waterSurface[index];
                        queue[queueTail++] = neighbor;
                        hasBoundary = true;
                     }
                  }
               }
            }
         }

         if (hasBoundary) {
            while (queueHead < queueTail) {
               int index = queue[queueHead++];
               int currentDistance = distance[index];
               if (currentDistance < blendCells) {
                  int x = index % lodSizePoints;
                  int z = index / lodSizePoints;

                  for (int offsetIndex = 0; offsetIndex < NEIGHBOR_OFFSETS.length; offsetIndex += 2) {
                     int nx = x + NEIGHBOR_OFFSETS[offsetIndex];
                     int nz = z + NEIGHBOR_OFFSETS[offsetIndex + 1];
                     if (nx >= 0 && nz >= 0 && nx < lodSizePoints && nz < lodSizePoints) {
                        int neighbor = nz * lodSizePoints + nx;
                        if (!waterMask[neighbor] && distance[neighbor] == -1) {
                           distance[neighbor] = currentDistance + 1;
                           nearestWaterSurface[neighbor] = nearestWaterSurface[index];
                           queue[queueTail++] = neighbor;
                        }
                     }
                  }
               }
            }

            for (int index = 0; index < area; index++) {
               int currentDistance = distance[index];
               if (currentDistance > 0 && currentDistance <= blendCells) {
                  int sourceSurface = nearestWaterSurface[index];
                  int baseSurface = baseTerrainSurface[index];
                  int blended = (int)Math.round(Mth.lerp(currentDistance / (double)blendCells, sourceSurface, baseSurface));
                  if (blended < terrainSurface[index]) {
                     terrainSurface[index] = blended;
                  }
               }
            }
         }
      }
   }

   private static boolean isOceanFallback(TellusLandMaskSource.LandMaskSample landMaskSample, int surface, int coverClass, int seaLevel) {
      if (landMaskSample.known()) {
         return !landMaskSample.land() && surface <= seaLevel;
      } else {
         return coverClass == ESA_NO_DATA && surface <= seaLevel;
      }
   }

   private static int blendCells(int blendBlocks, int cellSize) {
      if (blendBlocks <= 0 || cellSize <= 0) {
         return 0;
      } else {
         return (blendBlocks + cellSize - 1) / cellSize;
      }
   }

   private static int cellIndexForWorld(double world, int base, int cellOffset, int cellSize, int lodSizePoints) {
      int index = (int)Math.floor((world - (base + cellOffset)) / cellSize);
      return Mth.clamp(index, 0, lodSizePoints - 1);
   }

   private static int maxSampleOffset(int[] sampleOffsets) {
      int max = 0;

      for (int offset : sampleOffsets) {
         max = Math.max(max, Math.abs(offset));
      }

      return max;
   }

   private static void throwIfCancelled() {
      if (Thread.currentThread().isInterrupted()) {
         throw new CancellationException("DH water area resolution interrupted");
      }
   }

   private static boolean shouldSampleLandMask(boolean overtureWater, boolean sampledOcean, boolean belowSeaLevel) {
      return belowSeaLevel && !overtureWater && !sampledOcean;
   }

   private static int[] sampleOffsetsForCellSize(int cellSize) {
      if (cellSize <= 1) {
         return SAMPLE_OFFSETS_SINGLE;
      } else if (cellSize == 2) {
         return SAMPLE_OFFSETS_CELL_2;
      } else if (cellSize <= 4) {
         return SAMPLE_OFFSETS_SMALL_GRID;
      } else {
         int edge = Math.max(1, (cellSize >> 1) - 1);
         int quarter = Math.max(1, edge >> 1);
         return buildGridOffsets(new int[]{-edge, -quarter, 0, quarter, edge});
      }
   }

   private static int[] buildGridOffsets(int[] axisOffsets) {
      int[] offsets = new int[axisOffsets.length * axisOffsets.length * 2];
      int cursor = 0;

      for (int offsetZ : axisOffsets) {
         for (int offsetX : axisOffsets) {
            offsets[cursor++] = offsetX;
            offsets[cursor++] = offsetZ;
         }
      }

      return offsets;
   }

   private static boolean shouldRenderExactWaterFootprint(int wetSamples, int totalSamples, boolean ocean, boolean lineWater) {
      if (wetSamples <= 0 || totalSamples <= 0) {
         return false;
      } else if (lineWater) {
         return true;
      } else {
         return ocean ? wetSamples > totalSamples / 2 : wetSamples >= inlandExactWaterFootprintThreshold(totalSamples);
      }
   }

   private static int inlandExactWaterFootprintThreshold(int totalSamples) {
      // Near-player DH cells can be represented by just 1 or 4 probe samples.
      // Requiring multiple hits there either makes water impossible (1 sample)
      // or drops narrow OSM polygons too aggressively at close range.
      if (totalSamples <= 4) {
         return 1;
      } else {
         return Math.max(2, (totalSamples + 2) / 3);
      }
   }

   private static WaterSurfaceResolver.WaterColumnData normalizeLodWaterColumn(WaterSurfaceResolver.WaterColumnData column) {
      if (!column.hasWater()) {
         return column;
      } else {
         int terrainSurface = column.terrainSurface();
         int waterSurface = column.waterSurface();
         if (terrainSurface >= waterSurface) {
            terrainSurface = waterSurface - 1;
         }

         return terrainSurface == column.terrainSurface()
            ? column
            : new WaterSurfaceResolver.WaterColumnData(true, column.isOcean(), terrainSurface, waterSurface);
      }
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Mth.clamp(Integer.parseInt(value), minInclusive, maxInclusive);
         } catch (NumberFormatException error) {
            return defaultValue;
         }
      }
   }

   public record AreaResult(int[] terrainSurface, int[] waterSurface, boolean[] hasWater, boolean[] ocean) {
   }

   private record AreaKey(int baseX, int baseZ, int lodSizePoints, int cellSize) {
   }

   private record RasterizedWaterArea(boolean[] renderWater, boolean[] ocean, boolean[] sampledOcean) {
      private static DhLodWaterResolver.RasterizedWaterArea dry(int area) {
         return new DhLodWaterResolver.RasterizedWaterArea(new boolean[area], new boolean[area], new boolean[area]);
      }
   }
}
