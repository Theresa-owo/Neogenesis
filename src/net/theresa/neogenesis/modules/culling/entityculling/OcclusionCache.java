package net.theresa.neogenesis.modules.culling.entityculling;

public interface OcclusionCache {

    void resetCache();

    void setVisible(int var1, int var2, int var3);

    void setHidden(int var1, int var2, int var3);

    int getState(int var1, int var2, int var3);

    void setLastHidden();

    void setLastVisible();

}
