package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.cover.TellusLandCoverSource;
import com.yucareux.tellus.world.data.elevation.TellusElevationSource;
import com.yucareux.tellus.world.data.koppen.TellusKoppenSource;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.world.data.osm.TellusOsmBuildingSource;
import com.yucareux.tellus.world.data.osm.TellusOsmRoadSource;
import com.yucareux.tellus.world.data.osm.TellusOsmSandSource;
import com.yucareux.tellus.world.data.osm.TellusOsmWaterSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;

public final class TellusWorldgenSources {
   private static final TellusLandCoverSource LAND_COVER = new TellusLandCoverSource();
   private static final TellusLandMaskSource LAND_MASK = new TellusLandMaskSource();
   private static final TellusElevationSource ELEVATION = new TellusElevationSource();
   private static final TellusKoppenSource KOPPEN = new TellusKoppenSource();
   private static final TellusOsmRoadSource OSM_ROADS = new TellusOsmRoadSource();
   private static final TellusOsmBuildingSource OSM_BUILDINGS = new TellusOsmBuildingSource();
   private static final TellusOsmWaterSource OSM_WATER = new TellusOsmWaterSource();
   private static final TellusOsmSandSource OSM_SAND = new TellusOsmSandSource();
   private static final boolean PREFETCH_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.prefetch.enabled", "true"));
   private static final int LAND_COVER_PREFETCH_RADIUS = intProperty("tellus.prefetch.landcover.radius", 1);
   private static final int ELEVATION_PREFETCH_RADIUS = intProperty("tellus.prefetch.elevation.radius", 1);
   private static final int LAND_MASK_PREFETCH_RADIUS = intProperty("tellus.prefetch.landmask.radius", 1);
   private static final boolean WATER_PREFETCH_ENABLED = Boolean.parseBoolean(System.getProperty("tellus.prefetch.water.enabled", "true"));
   private static final int WATER_PREFETCH_RADIUS = intProperty("tellus.prefetch.water.radius", 1);
   private static final int SAND_PREFETCH_RADIUS = intProperty("tellus.prefetch.sand.radius", 1);
   private static final int CHUNK_DETAIL_PREFETCH_RADIUS = intProperty("tellus.chunkdetail.prefetchRadius", 2);
   private static final int ROADS_PREFETCH_RADIUS = intProperty("tellus.prefetch.roads.radius", 1);
   private static final int BUILDINGS_PREFETCH_RADIUS = intProperty("tellus.prefetch.buildings.radius", 1);
   private static final ExecutorService PREFETCH_EXECUTOR = createPrefetchExecutor();
   private static final ExecutorService TERRAIN_DETAIL_EXECUTOR = createTerrainDetailExecutor();
   private static final ConcurrentMap<EarthGeneratorSettings, WaterSurfaceResolver> WATER_RESOLVERS = new ConcurrentHashMap<>();

   private TellusWorldgenSources() {
   }

   static TellusLandCoverSource landCover() {
      return LAND_COVER;
   }

   static TellusElevationSource elevation() {
      return ELEVATION;
   }

   static TellusKoppenSource koppen() {
      return KOPPEN;
   }

   public static TellusLandMaskSource landMask() {
      return LAND_MASK;
   }

   public static TellusOsmRoadSource osmRoads() {
      return OSM_ROADS;
   }

   public static TellusOsmBuildingSource osmBuildings() {
      return OSM_BUILDINGS;
   }

   public static TellusOsmWaterSource osmWater() {
      return OSM_WATER;
   }

   public static TellusOsmSandSource osmSand() {
      return OSM_SAND;
   }

   static WaterSurfaceResolver waterResolver(EarthGeneratorSettings settings) {
      Objects.requireNonNull(settings, "settings");
      WaterSurfaceResolver resolver = WATER_RESOLVERS.computeIfAbsent(settings, value -> new WaterSurfaceResolver(LAND_COVER, LAND_MASK, ELEVATION, value));
      return Objects.requireNonNull(resolver, "waterResolver");
   }

   static void warmCriticalTerrainInputsForChunk(ChunkPos pos, EarthGeneratorSettings settings, double previewResolutionMeters) {
      Objects.requireNonNull(pos, "pos");
      Objects.requireNonNull(settings, "settings");
      int centerX = pos.getMinBlockX() + 8;
      int centerZ = pos.getMinBlockZ() + 8;
      double worldScale = settings.worldScale();
      List<CompletableFuture<Void>> futures = new ArrayList<>(3);
      futures.add(
         submitCriticalWarmup(() -> LAND_COVER.prefetchTiles(centerX, centerZ, worldScale, Math.max(0, LAND_COVER_PREFETCH_RADIUS), previewResolutionMeters))
      );
      futures.add(
         submitCriticalWarmup(
            () -> ELEVATION.prefetchTiles(centerX, centerZ, worldScale, Math.max(0, ELEVATION_PREFETCH_RADIUS), settings.demSelection(), previewResolutionMeters)
         )
      );
      futures.add(submitCriticalWarmup(() -> LAND_MASK.prefetchTiles(centerX, centerZ, worldScale, Math.max(1, LAND_MASK_PREFETCH_RADIUS))));
      if (SAND_PREFETCH_RADIUS > 0 && worldScale > 0.0) {
         futures.add(submitCriticalWarmup(() -> OSM_SAND.prefetchTiles(centerX, centerZ, worldScale, SAND_PREFETCH_RADIUS)));
      }

      for (CompletableFuture<Void> future : futures) {
         try {
            future.join();
         } catch (RuntimeException error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            Tellus.LOGGER.debug("Failed to warm Tellus terrain input for chunk {}", pos, cause);
         }
      }
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings) {
      prefetchForChunk(pos, settings, true, true, true, settings.worldScale());
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, true, true, settings.worldScale());
   }

   static void prefetchForChunk(ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, true, settings.worldScale());
   }

   static void prefetchForChunk(
      ChunkPos pos, EarthGeneratorSettings settings, boolean includeRoadsPrefetch, boolean includeDetailedWaterPrefetch, boolean includeBuildingsPrefetch
   ) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, settings.worldScale());
   }

   static void prefetchForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters
   ) {
      prefetchForChunk(pos, settings, includeRoadsPrefetch, includeDetailedWaterPrefetch, includeBuildingsPrefetch, previewResolutionMeters, true);
   }

   static void prefetchForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeDetailedWaterPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         prefetchTerrainForChunk(pos, settings, previewResolutionMeters, allowInlineExecution);
         prefetchWaterForChunk(pos, settings, includeDetailedWaterPrefetch, previewResolutionMeters, allowInlineExecution);
         prefetchOsmDetailsForChunk(pos, settings, includeRoadsPrefetch, includeBuildingsPrefetch, previewResolutionMeters, allowInlineExecution);
      }
   }

   static void prefetchTerrainForChunk(
      ChunkPos pos, EarthGeneratorSettings settings, double previewResolutionMeters, boolean allowInlineExecution
   ) {
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         if (LAND_COVER_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> LAND_COVER.prefetchTiles(centerX, centerZ, worldScale, LAND_COVER_PREFETCH_RADIUS, previewResolutionMeters), allowInlineExecution);
         }

         if (ELEVATION_PREFETCH_RADIUS > 0) {
            submitPrefetch(
               () -> ELEVATION.prefetchTiles(centerX, centerZ, worldScale, ELEVATION_PREFETCH_RADIUS, settings.demSelection(), previewResolutionMeters),
               allowInlineExecution
            );
         }

         if (LAND_MASK_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> LAND_MASK.prefetchTiles(centerX, centerZ, worldScale, LAND_MASK_PREFETCH_RADIUS), allowInlineExecution);
         }
      }
   }

   static void prefetchWaterForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeDetailedWaterPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         if (WATER_PREFETCH_ENABLED && WATER_PREFETCH_RADIUS > 0 && settings.enableWater()) {
            submitPrefetch(() -> OSM_WATER.prefetchTiles(centerX, centerZ, worldScale, WATER_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (SAND_PREFETCH_RADIUS > 0 && worldScale > 0.0) {
            submitPrefetch(() -> OSM_SAND.prefetchTiles(centerX, centerZ, worldScale, SAND_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (includeDetailedWaterPrefetch && CHUNK_DETAIL_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> waterResolver(settings).prefetchRegionsForChunk(pos.x, pos.z, CHUNK_DETAIL_PREFETCH_RADIUS), allowInlineExecution);
         }
      }
   }

   static void prefetchOsmDetailsForChunk(
      ChunkPos pos,
      EarthGeneratorSettings settings,
      boolean includeRoadsPrefetch,
      boolean includeBuildingsPrefetch,
      double previewResolutionMeters,
      boolean allowInlineExecution
   ) {
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null) {
         int centerX = pos.getMinBlockX() + 8;
         int centerZ = pos.getMinBlockZ() + 8;
         double worldScale = settings.worldScale();
         if (includeRoadsPrefetch && settings.enableRoads() && worldScale <= 15.0 && ROADS_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> OSM_ROADS.prefetchTiles(centerX, centerZ, worldScale, ROADS_PREFETCH_RADIUS), allowInlineExecution);
         }

         if (includeBuildingsPrefetch && settings.enableBuildings() && worldScale > 0.0 && worldScale <= 15.0 && BUILDINGS_PREFETCH_RADIUS > 0) {
            submitPrefetch(() -> OSM_BUILDINGS.prefetchTiles(centerX, centerZ, worldScale, BUILDINGS_PREFETCH_RADIUS), allowInlineExecution);
         }
      }
   }

   static void prefetchWaterRegionsForArea(int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, EarthGeneratorSettings settings) {
      prefetchWaterRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ, settings, true);
   }

   static void prefetchWaterRegionsForArea(
      int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ, EarthGeneratorSettings settings, boolean allowInlineExecution
   ) {
      if (PREFETCH_ENABLED && PREFETCH_EXECUTOR != null && WATER_PREFETCH_ENABLED) {
         submitPrefetch(() -> waterResolver(settings).prefetchRegionsForArea(minBlockX, minBlockZ, maxBlockX, maxBlockZ), allowInlineExecution);
      }
   }

   static CompletableFuture<Void> submitTerrainDetailTask(Runnable task) {
      if (TERRAIN_DETAIL_EXECUTOR != null) {
         try {
            return CompletableFuture.runAsync(task, TERRAIN_DETAIL_EXECUTOR);
         } catch (RejectedExecutionException error) {
         }
      }

      try {
         task.run();
         return CompletableFuture.completedFuture(null);
      } catch (RuntimeException error) {
         CompletableFuture<Void> failed = new CompletableFuture<>();
         failed.completeExceptionally(error);
         return failed;
      }
   }

   private static void submitPrefetch(Runnable task, boolean allowInlineExecution) {
      try {
         PREFETCH_EXECUTOR.execute(task);
      } catch (RejectedExecutionException error) {
         if (allowInlineExecution) {
            try {
               task.run();
            } catch (RuntimeException inlineError) {
               Tellus.LOGGER.debug("Failed to execute Tellus prefetch task inline", inlineError);
            }
         } else {
            EarthChunkGenerator.recordTerrainStreamingPrefetchQueueRejection();
            Tellus.LOGGER.debug("Skipped Tellus prefetch task because the queue is full");
         }
      } catch (RuntimeException error) {
         Tellus.LOGGER.debug("Failed to schedule Tellus prefetch task", error);
      }
   }

   private static CompletableFuture<Void> submitCriticalWarmup(Runnable task) {
      if (PREFETCH_EXECUTOR != null) {
         try {
            return CompletableFuture.runAsync(task, PREFETCH_EXECUTOR);
         } catch (RejectedExecutionException error) {
         }
      }

      try {
         task.run();
         return CompletableFuture.completedFuture(null);
      } catch (RuntimeException error) {
         CompletableFuture<Void> failed = new CompletableFuture<>();
         failed.completeExceptionally(error);
         return failed;
      }
   }

   private static ExecutorService createPrefetchExecutor() {
      if (!PREFETCH_ENABLED) {
         return null;
      } else {
         TellusWorldgenSources.ThreadBounds bounds = resolveThreadBounds(
            "tellus.prefetch.threads.min", "tellus.prefetch.threads.max", "tellus.prefetch.threads", 2, 8
         );
         int minThreads = bounds.min();
         int maxThreads = bounds.max();
         int queueSize = intProperty("tellus.prefetch.queue", 256);
         return createAdaptiveExecutor("tellus-prefetch-", minThreads, maxThreads, queueSize);
      }
   }

   private static ExecutorService createTerrainDetailExecutor() {
      TellusWorldgenSources.ThreadBounds bounds = resolveThreadBounds(
         "tellus.terraindetail.threads.min", "tellus.terraindetail.threads.max", null, 4, 12
      );
      int queueSize = intProperty("tellus.terraindetail.queue", 1024);
      return createAdaptiveExecutor("tellus-terrain-", bounds.min(), bounds.max(), queueSize);
   }

   private static ExecutorService createAdaptiveExecutor(String threadPrefix, int minThreads, int maxThreads, int queueSize) {
      ThreadFactory factory = new ThreadFactory() {
         private final AtomicInteger index = new AtomicInteger();

         @Override
         public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, threadPrefix + this.index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
         }
      };
      TellusWorldgenSources.AdaptiveThreadPoolExecutor executor = new TellusWorldgenSources.AdaptiveThreadPoolExecutor(
         Math.max(1, minThreads),
         Math.max(1, maxThreads),
         30L,
         TimeUnit.SECONDS,
         new ArrayBlockingQueue<>(Math.max(1, queueSize)),
         factory,
         new AbortPolicy()
      );
      executor.allowCoreThreadTimeOut(true);
      return executor;
   }

   private static TellusWorldgenSources.ThreadBounds resolveThreadBounds(
      String minKey, String maxKey, String legacyKey, int defaultMinThreads, int defaultMaxThreads
   ) {
      Integer maxOverride = intPropertyNullable(maxKey);
      Integer minOverride = intPropertyNullable(minKey);
      Integer legacyThreads = legacyKey == null ? null : intPropertyNullable(legacyKey);
      int maxThreads;
      if (maxOverride != null) {
         maxThreads = maxOverride;
      } else if (legacyThreads != null) {
         maxThreads = legacyThreads;
      } else {
         int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
         maxThreads = Math.min(defaultMaxThreads, Math.max(defaultMinThreads, cores * 2));
      }

      int minThreads = minOverride != null ? minOverride : Math.min(defaultMinThreads, maxThreads);
      minThreads = Math.max(1, Math.min(minThreads, maxThreads));
      maxThreads = Math.max(1, maxThreads);
      return new TellusWorldgenSources.ThreadBounds(minThreads, maxThreads);
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      } else {
         try {
            return Math.max(0, Integer.parseInt(value));
         } catch (NumberFormatException var4) {
            return defaultValue;
         }
      }
   }

   private static Integer intPropertyNullable(String key) {
      String value = System.getProperty(key);
      if (value == null) {
         return null;
      } else {
         try {
            return Integer.parseInt(value);
         } catch (NumberFormatException var3) {
            return null;
         }
      }
   }

   private static final class AdaptiveThreadPoolExecutor extends ThreadPoolExecutor {
      private final int minThreads;
      private final int maxThreads;

      private AdaptiveThreadPoolExecutor(
         int minThreads,
         int maxThreads,
         long keepAliveTime,
         TimeUnit unit,
         ArrayBlockingQueue<Runnable> workQueue,
         ThreadFactory threadFactory,
         RejectedExecutionHandler handler
      ) {
         super(minThreads, maxThreads, keepAliveTime, unit, workQueue, threadFactory, handler);
         this.minThreads = minThreads;
         this.maxThreads = maxThreads;
      }

      @Override
      public void execute(Runnable command) {
         this.maybeAdjustCore();
         super.execute(command);
      }

      private void maybeAdjustCore() {
         int queueSize = this.getQueue().size();
         int active = this.getActiveCount();
         int core = this.getCorePoolSize();
         if (queueSize > active * 2 && core < this.maxThreads) {
            int nextCore = Math.min(this.maxThreads, core + 1);
            this.setCorePoolSize(nextCore);
            this.prestartCoreThread();
         } else {
            if (queueSize == 0 && active <= this.minThreads && core > this.minThreads) {
               this.setCorePoolSize(this.minThreads);
            }
         }
      }
   }

   private record ThreadBounds(int min, int max) {
   }
}
