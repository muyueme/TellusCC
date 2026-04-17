package com.yucareux.tellus.world.realtime;

import java.util.Objects;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome.Precipitation;

public final class TellusRealtimeState {
   private static final float SNOW_THRESHOLD = 0.35F;
   private static final float SNOW_JITTER = 0.15F;
   private static final float FREEZING_TEMPERATURE_C = 0.0F;
   private static volatile boolean weatherEnabled;
   private static volatile boolean historicalSnowEnabled;
   private static volatile TellusRealtimeState.PrecipitationMode precipitationMode = TellusRealtimeState.PrecipitationMode.CLEAR;
   private static volatile SnowGrid snowGrid = SnowGrid.empty();
   private static volatile float weatherTemperatureC = Float.NaN;

   private TellusRealtimeState() {
   }

   public static void updateWeatherState(
      boolean weatherEnabled, TellusRealtimeState.PrecipitationMode precipitationMode, boolean historicalSnowEnabled, SnowGrid snowGrid
   ) {
      updateWeatherState(weatherEnabled, precipitationMode, historicalSnowEnabled, snowGrid, Float.NaN);
   }

   public static void updateWeatherState(
      boolean weatherEnabled,
      TellusRealtimeState.PrecipitationMode precipitationMode,
      boolean historicalSnowEnabled,
      SnowGrid snowGrid,
      float weatherTemperatureC
   ) {
      TellusRealtimeState.weatherEnabled = weatherEnabled;
      TellusRealtimeState.historicalSnowEnabled = historicalSnowEnabled;
      TellusRealtimeState.precipitationMode = Objects.requireNonNull(precipitationMode, "precipitationMode");
      TellusRealtimeState.snowGrid = snowGrid == null ? SnowGrid.empty() : snowGrid;
      TellusRealtimeState.weatherTemperatureC = weatherTemperatureC;
   }

   public static void clearRealtimeWeather() {
      TellusRealtimeState.weatherEnabled = false;
      TellusRealtimeState.historicalSnowEnabled = false;
      TellusRealtimeState.precipitationMode = TellusRealtimeState.PrecipitationMode.CLEAR;
      TellusRealtimeState.snowGrid = SnowGrid.empty();
      TellusRealtimeState.weatherTemperatureC = Float.NaN;
   }

   public static void reset() {
      clearRealtimeWeather();
   }

   public static boolean isWeatherEnabled() {
      return weatherEnabled;
   }

   public static boolean isHistoricalSnowEnabled() {
      return historicalSnowEnabled;
   }

   public static TellusRealtimeState.PrecipitationMode precipitationMode() {
      return precipitationMode;
   }

   public static SnowGrid snowGrid() {
      return snowGrid;
   }

   public static boolean shouldAllowWaterFreeze() {
      return weatherEnabled && !Float.isNaN(weatherTemperatureC) && weatherTemperatureC <= FREEZING_TEMPERATURE_C;
   }

   public static Precipitation precipitationOverride() {
      TellusRealtimeState.PrecipitationMode mode = precipitationMode;
      if (weatherEnabled && mode != TellusRealtimeState.PrecipitationMode.CLEAR) {
         return mode == TellusRealtimeState.PrecipitationMode.SNOW ? Precipitation.SNOW : Precipitation.RAIN;
      } else {
         return null;
      }
   }

   public static float sampleSnowCoverage(int blockX, int blockZ) {
      if (!historicalSnowEnabled) {
         return 0.0F;
      } else {
         SnowGrid grid = snowGrid;
         if (grid != null && !grid.isEmpty()) {
            float base = grid.sample(blockX, blockZ);
            if (base <= 0.0F) {
               return 0.0F;
            } else {
               float jitter = (hashToUnit(blockX, blockZ) - 0.5F) * SNOW_JITTER;
               return Mth.clamp(base + jitter, 0.0F, 1.0F);
            }
         } else {
            return 0.0F;
         }
      }
   }

   public static boolean shouldApplySnow(int blockX, int blockZ) {
      return weatherEnabled && precipitationMode == TellusRealtimeState.PrecipitationMode.SNOW
         ? true
         : sampleSnowCoverage(blockX, blockZ) >= SNOW_THRESHOLD;
   }

   private static float hashToUnit(int x, int z) {
      long seed = x * 341873128712L + z * 132897987541L;
      seed ^= seed >>> 33;
      seed *= -49064778989728563L;
      seed ^= seed >>> 33;
      seed *= -4265267296055464877L;
      seed ^= seed >>> 33;
      return (float)(seed & 16777215L) / 1.6777215E7F;
   }

   public static enum PrecipitationMode {
      CLEAR,
      RAIN,
      SNOW,
      THUNDER;
   }
}
