package net.optifine.entity.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRabbit;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderRabbit;
import net.minecraft.entity.passive.EntityRabbit;

public class ModelAdapterRabbit extends ModelAdapter {

    public ModelAdapterRabbit() {
        super(EntityRabbit.class, "rabbit", 0.3F);
    }

    public ModelBase makeModel() {
        return new ModelRabbit();
    }

    public ModelRenderer getModelRenderer(ModelBase model, String modelPart) {
        if (!(model instanceof ModelRabbit)) {
            return null;
        } else {
            ModelRabbit modelrabbit = (ModelRabbit) model;
                return "left_foot".equals(modelPart) ? modelrabbit.rabbitLeftFoot :
                    "right_foot".equals(modelPart) ? modelrabbit.rabbitRightFoot :
                        "left_thigh".equals(modelPart) ? modelrabbit.rabbitLeftThigh :
                            "right_thigh".equals(modelPart) ? modelrabbit.rabbitRightThigh :
                                "body".equals(modelPart) ? modelrabbit.rabbitBody :
                                    "left_arm".equals(modelPart) ? modelrabbit.rabbitLeftArm :
                                        "right_arm".equals(modelPart) ? modelrabbit.rabbitRightArm :
                                            "head".equals(modelPart) ? modelrabbit.rabbitHead :
                                                "right_ear".equals(modelPart) ? modelrabbit.rabbitRightEar :
                                                    "left_ear".equals(modelPart) ? modelrabbit.rabbitLeftEar :
                                                        "tail".equals(modelPart) ? modelrabbit.rabbitTail :
                                                            "nose".equals(modelPart) ? modelrabbit.rabbitNose : null;
        }
    }

    public String[] getModelRendererNames() {
        return new String[] { "left_foot", "right_foot", "left_thigh", "right_thigh", "body", "left_arm", "right_arm",
                "head", "right_ear", "left_ear", "tail", "nose" };
    }

    public IEntityRenderer makeEntityRender(ModelBase modelBase, float shadowSize) {
        RenderManager rendermanager = Minecraft.getMinecraft().getRenderManager();
        RenderRabbit renderrabbit = new RenderRabbit(rendermanager, modelBase, shadowSize);
        return renderrabbit;
    }

}
