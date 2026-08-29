package net.theresa.neogenesis.modules.culling.entityculling;

public abstract class EntityCullingBase {

    public OcclusionCullingInstance culling;
    public CullTask cullTask;
    private Thread cullThread;
    protected boolean pressed = false;

    public void onInitialize() {
        if (Config.aggressiveMode) {
            this.culling = new OcclusionCullingInstance(Config.tracingDistance, new Provider(),
                                                        new ArrayOcclusionCache(Config.tracingDistance), 0.0);
        } else {
            this.culling = new OcclusionCullingInstance(Config.tracingDistance, new Provider());
        }

        this.cullTask = new CullTask(this.culling);
        this.cullThread = new Thread(this.cullTask, "CullThread");
        this.cullThread.setUncaughtExceptionHandler((thread, ex) -> {
            System.out.println("The CullingThread has crashed! Please report the following stacktrace!");
            ex.printStackTrace();
        });
        this.cullThread.start();
    }

    public void worldTick() {
        this.cullTask.requestCull = true;
    }

    public void clientTick() {
        this.cullTask.requestCull = true;
    }

}
