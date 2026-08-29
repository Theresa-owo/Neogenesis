package net.optifine.entity.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelOcelot;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderOcelot;
import net.minecraft.entity.passive.EntityOcelot;

public class ModelAdapterOcelot extends ModelAdapter {

    public ModelAdapterOcelot() {
        super(EntityOcelot.class, "ocelot", 0.4F);
    }

    public ModelBase makeModel() {
        return new ModelOcelot();
    }

    public ModelRenderer getModelRenderer(ModelBase model, String modelPart) {
        if (!(model instanceof ModelOcelot)) {
            return null;
        } else {
            ModelOcelot modelocelot = (ModelOcelot) model;
            return "back_left_leg".equals(modelPart) ? modelocelot.ocelotBackLeftLeg :
                "back_right_leg".equals(modelPart) ? modelocelot.ocelotBackRightLeg :
                    "front_left_leg".equals(modelPart) ? modelocelot.ocelotFrontLeftLeg :
                        "front_right_leg".equals(modelPart) ? modelocelot.ocelotFrontRightLeg :
                            "tail".equals(modelPart) ? modelocelot.ocelotTail :
                                "tail2".equals(modelPart) ? modelocelot.ocelotTail2 :
                                    "head".equals(modelPart) ? modelocelot.ocelotHead :
                                        "body".equals(modelPart) ? modelocelot.ocelotBody : null;
        }
    }

    public String[] getModelRendererNames() {
        return new String[] { "back_left_leg", "back_right_leg", "front_left_leg", "front_right_leg", "tail", "tail2",
                "head", "body" };
    }

    public IEntityRenderer makeEntityRender(ModelBase modelBase, float shadowSize) {
        RenderManager rendermanager = Minecraft.getMinecraft().getRenderManager();
        RenderOcelot renderocelot = new RenderOcelot(rendermanager, modelBase, shadowSize);
        return renderocelot;
    }

}
