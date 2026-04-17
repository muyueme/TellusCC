package com.yucareux.tellus;

import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.network.TellusWeatherPayload;
import com.yucareux.tellus.world.realtime.SnowGrid;
import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import java.util.Objects;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

@Environment(EnvType.CLIENT)
public class TellusClient implements ClientModInitializer {
   @Override
   public void onInitializeClient() {
      ClientPlayNetworking.registerGlobalReceiver(Objects.requireNonNull(GeoTpOpenMapPayload.TYPE, "GeoTpOpenMapPayload.TYPE"), (payload, player, responseSender) -> {
         Minecraft minecraft = Minecraft.getInstance();
         minecraft.execute(() -> {
            Screen parent = minecraft.screen;
            minecraft.setScreen(new EarthTeleportScreen(parent, payload.latitude(), payload.longitude()));
         });
      });
      ClientPlayNetworking.registerGlobalReceiver(
         Objects.requireNonNull(TellusWeatherPayload.TYPE, "TellusWeatherPayload.TYPE"),
         (payload, player, responseSender) -> Minecraft.getInstance()
            .execute(
               () -> {
                  SnowGrid grid = payload.historicalSnowEnabled() && payload.spacingBlocks() > 0
                     ? new SnowGrid(payload.centerX(), payload.centerZ(), payload.spacingBlocks(), payload.snowIndex())
                     : SnowGrid.empty();
                  TellusRealtimeState.updateWeatherState(payload.weatherEnabled(), payload.precipitationMode(), payload.historicalSnowEnabled(), grid);
               }
            )
      );
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> TellusRealtimeState.clearRealtimeWeather());
   }
}
