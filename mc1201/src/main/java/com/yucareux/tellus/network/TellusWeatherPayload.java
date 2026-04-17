package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Arrays;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

public record TellusWeatherPayload(
   boolean weatherEnabled,
   TellusRealtimeState.PrecipitationMode precipitationMode,
   boolean historicalSnowEnabled,
   int centerX,
   int centerZ,
   int spacingBlocks,
   float[] snowIndex
) implements FabricPacket {
   
   public static final PacketType<TellusWeatherPayload> TYPE = PacketType.create(Tellus.id("realtime_weather"), TellusWeatherPayload::new);
   private static final int GRID_POINTS = 9;

   public TellusWeatherPayload(FriendlyByteBuf buffer) {
      this(
         buffer.readBoolean(),
         decodePrecipitation(buffer.readByte()),
         buffer.readBoolean(),
         buffer.readVarInt(),
         buffer.readVarInt(),
         buffer.readVarInt(),
         readSnowIndex(buffer)
      );
   }

   public TellusWeatherPayload(
      boolean weatherEnabled,
      TellusRealtimeState.PrecipitationMode precipitationMode,
      boolean historicalSnowEnabled,
      int centerX,
      int centerZ,
      int spacingBlocks,
      float[] snowIndex
   ) {
      if (snowIndex != null && snowIndex.length == GRID_POINTS) {
         snowIndex = Arrays.copyOf(snowIndex, GRID_POINTS);
      } else {
         snowIndex = new float[GRID_POINTS];
      }

      this.weatherEnabled = weatherEnabled;
      this.precipitationMode = precipitationMode;
      this.historicalSnowEnabled = historicalSnowEnabled;
      this.centerX = centerX;
      this.centerZ = centerZ;
      this.spacingBlocks = spacingBlocks;
      this.snowIndex = snowIndex;
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeBoolean(this.weatherEnabled());
      buffer.writeByte(TellusWeatherPayload.encodePrecipitation(this.precipitationMode()));
      buffer.writeBoolean(this.historicalSnowEnabled());
      buffer.writeVarInt(this.centerX());
      buffer.writeVarInt(this.centerZ());
      buffer.writeVarInt(this.spacingBlocks());
      float[] snowIndex = this.snowIndex();
      if (snowIndex == null || snowIndex.length < GRID_POINTS) {
         snowIndex = new float[GRID_POINTS];
      }

      for (int i = 0; i < GRID_POINTS; i++) {
         buffer.writeFloat(snowIndex[i]);
      }
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }

   private static float[] readSnowIndex(FriendlyByteBuf buffer) {
      float[] snowIndex = new float[GRID_POINTS];
      for (int i = 0; i < GRID_POINTS; i++) {
         snowIndex[i] = buffer.readFloat();
      }
      return snowIndex;
   }

   private static byte encodePrecipitation(TellusRealtimeState.PrecipitationMode mode) {
      return mode == null ? 0 : (byte)mode.ordinal();
   }

   private static TellusRealtimeState.PrecipitationMode decodePrecipitation(byte id) {
      TellusRealtimeState.PrecipitationMode[] values = TellusRealtimeState.PrecipitationMode.values();
      int index = id < 0 ? 0 : Math.min(id, values.length - 1);
      return values[index];
   }
}
