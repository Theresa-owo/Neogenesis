package net.minecraft.client.resources;

import java.util.List;

public interface IReloadableResourceManager extends IResourceManager {

    void reloadResources(List<IResourcePack> resourcesPacksList, boolean isLang);

    void registerReloadListener(IResourceManagerReloadListener reloadListener);

}
