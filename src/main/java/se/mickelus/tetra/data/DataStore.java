package se.mickelus.tetra.data;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.client.resources.ReloadListener;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.profiler.IProfiler;
import net.minecraft.resources.IResource;
import net.minecraft.resources.IResourceManager;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.forgespi.Environment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import se.mickelus.tetra.network.PacketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class DataStore<V> extends ReloadListener<Map<ResourceLocation, JsonElement>> {
    private static final Logger logger = LogManager.getLogger();
    private static final int jsonExtLength = ".json".length();

    private Gson gson;
    private String directory;
    private Class<V> dataClass;

    private Map<ResourceLocation, JsonElement> rawData;
    private Map<ResourceLocation, V> dataMap;
    private List<Runnable> listeners;

    public DataStore(Gson gson, String directory, Class<V> dataClass) {
        this.gson = gson;
        this.directory = directory;

        this.dataClass = dataClass;

        dataMap = Collections.emptyMap();

        listeners = new LinkedList<>();
    }

    protected Map<ResourceLocation, JsonElement> prepare(IResourceManager resourceManager, IProfiler profiler) {
        logger.debug("Reading data for {} data store...", directory);
        Map<ResourceLocation, JsonElement> map = Maps.newHashMap();
        int i = this.directory.length() + 1;

        for(ResourceLocation fullLocation : resourceManager.getAllResourceLocations(directory, rl -> rl.endsWith(".json"))) {
            String path = fullLocation.getPath();
            ResourceLocation location = new ResourceLocation(fullLocation.getNamespace(), path.substring(i, path.length() - jsonExtLength));

            try (
                    IResource resource = resourceManager.getResource(fullLocation);
                    InputStream inputStream = resource.getInputStream();
                    Reader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            ) {
                JsonElement json;

                if (dataClass.isArray()) {
                    json = JSONUtils.fromJson(gson, reader, JsonArray.class);
                } else {
                    json = JSONUtils.fromJson(gson, reader, JsonElement.class);
                }
                if (json != null) {
                    JsonElement duplicate = map.put(location, json);
                    if (duplicate != null) {
                        throw new IllegalStateException("Duplicate data file ignored with ID " + location);
                    }
                } else {
                    logger.error("Couldn't load data file {} from {} as it's null or empty", location, fullLocation);
                }
            } catch (IllegalArgumentException | IOException | JsonParseException jsonparseexception) {
                logger.error("Couldn't parse data file {} from {}", location, fullLocation, jsonparseexception);
            }
        }

        return map;
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> splashList, IResourceManager resourceManager, IProfiler profiler) {
        rawData = splashList;

        if (Environment.get().getDist().isDedicatedServer()) {
            PacketHandler.sendToAllPlayers(new UpdateDataPacket(directory, rawData));
        }

        parseData(splashList);
    }

    public void sendToPlayer(ServerPlayerEntity player) {
        PacketHandler.sendTo(new UpdateDataPacket(directory, rawData), player);
    }

    public void loadFromPacket(Map<ResourceLocation, String> data) {
        Map<ResourceLocation, JsonElement> splashList = data.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            if (dataClass.isArray()) {
                                return JSONUtils.fromJson(gson, entry.getValue(), JsonArray.class);
                            } else {
                                return JSONUtils.fromJson(gson, entry.getValue(), JsonElement.class);
                            }
                        }
                ));

        parseData(splashList);
    }

    public void parseData(Map<ResourceLocation, JsonElement> splashList) {
        logger.info("Loaded {} {}", String.format("%3d", splashList.values().size()), directory);
        dataMap = splashList.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> gson.fromJson(entry.getValue(), dataClass)
                ));

        listeners.forEach(Runnable::run);
    }

    public Map<ResourceLocation, JsonElement> getRawData() {
        return rawData;
    }

    public String getDirectory() {
        return directory;
    }

    /**
     * Get the resource at the given location from the set of resources that this listener is managing.
     *
     * @param resourceLocation A resource location
     * @return An object matching the type of this listener, or null if none exists at the given location
     */
    public V getData(ResourceLocation resourceLocation) {
        return dataMap.get(resourceLocation);
    }

    /**
     * @return all data from this store.
     */
    public Map<ResourceLocation, V> getData() {
        return dataMap;
    }

    /**
     * Listen to changes on resources in this store
     *
     * @param callback A runnable that is to be called when the store is reloaded
     */
    public void onReload(Runnable callback) {
        listeners.add(callback);
    }
}
