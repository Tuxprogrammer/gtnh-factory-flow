package com.gtnhplanner;

import com.gtnhplanner.annotation.VersionedInjector;
import com.gtnhplanner.config.ConfigMain;
import com.gtnhplanner.machines.game.PackRates;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(final FMLPreInitializationEvent event) {
        VersionedInjector.injectAll(event.getAsmData());

        Config.synchronizeConfiguration(event.getSuggestedConfigurationFile());
        ConfigMain.registerConfigs();
    }

    public void init(final FMLInitializationEvent event) {}

    public void postInit(final FMLPostInitializationEvent event) {
        // The pack's rate mod is optional and loaded by now: the core's machine math reads its rates from here on.
        PackRates.install();
    }

    public void serverStarting(final FMLServerStartingEvent event) {}
}
