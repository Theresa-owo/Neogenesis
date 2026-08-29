package net.optifine.entity.model;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelHorse;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderHorse;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.passive.EntityHorse;

public class ModelAdapterHorse extends ModelAdapter {

    public ModelAdapterHorse() {
        super(EntityHorse.class, "horse", 0.75F);
    }

    protected ModelAdapterHorse(Class entityClass, String name, float shadowSize) {
        super(entityClass, name, shadowSize);
    }

    public ModelBase makeModel() {
        return new ModelHorse();
    }

    public ModelRenderer getModelRenderer(ModelBase model, String modelPart) {
        if (!(model instanceof ModelHorse)) {
            return null;
        } else {
            ModelHorse modelhorse = (ModelHorse) model;
            return "head".equals(modelPart) ? modelhorse.head :
                "upper_mouth".equals(modelPart) ? modelhorse.field_178711_b :
                    "lower_mouth".equals(modelPart) ? modelhorse.field_178712_c :
                    "horse_left_ear".equals(modelPart) ? modelhorse.horseLeftEar :
                    "horse_right_ear".equals(modelPart) ? modelhorse.horseRightEar :
                    "mule_left_ear".equals(modelPart) ? modelhorse.muleLeftEar :
                    "mule_right_ear".equals(modelPart) ? modelhorse.muleRightEar :
                    "neck".equals(modelPart) ? modelhorse.neck :
                    "horse_face_ropes".equals(modelPart) ? modelhorse.horseFaceRopes :
                    "mane".equals(modelPart) ? modelhorse.mane :
                    "body".equals(modelPart) ? modelhorse.body :
                    "tail_base".equals(modelPart) ? modelhorse.tailBase :
                    "tail_middle".equals(modelPart) ? modelhorse.tailMiddle :
                    "tail_tip".equals(modelPart) ? modelhorse.tailTip :
                    "back_left_leg".equals(modelPart) ? modelhorse.backLeftLeg :
                    "back_left_shin".equals(modelPart) ? modelhorse.backLeftShin :
                    "back_left_hoof".equals(modelPart) ? modelhorse.backLeftHoof :
                    "back_right_leg".equals(modelPart) ? modelhorse.backRightLeg :
                    "back_right_shin".equals(modelPart) ? modelhorse.backRightShin :
                    "back_right_hoof".equals(modelPart) ? modelhorse.backRightHoof :
                    "front_left_leg".equals(modelPart) ? modelhorse.frontLeftLeg :
                    "front_left_shin".equals(modelPart) ? modelhorse.frontLeftShin :
                    "front_left_hoof".equals(modelPart) ? modelhorse.frontLeftHoof :
                    "front_right_leg".equals(modelPart) ? modelhorse.frontRightLeg :
                    "front_right_shin".equals(modelPart) ? modelhorse.frontRightShin :
                    "front_right_hoof".equals(modelPart) ? modelhorse.frontRightHoof :
                    "mule_left_chest".equals(modelPart) ? modelhorse.muleLeftChest :
                    "mule_right_chest".equals(modelPart) ? modelhorse.muleRightChest :
                    "horse_saddle_bottom".equals(modelPart) ? modelhorse.horseSaddleBottom :
                    "horse_saddle_front".equals(modelPart) ? modelhorse.horseSaddleFront :
                    "horse_saddle_back".equals(modelPart) ? modelhorse.horseSaddleBack :
                    "horse_left_saddle_rope".equals(modelPart) ? modelhorse.horseLeftSaddleRope :
                    "horse_left_saddle_metal".equals(modelPart) ? modelhorse.horseLeftSaddleMetal :
                    "horse_right_saddle_rope".equals(modelPart) ? modelhorse.horseRightSaddleRope :
                    "horse_right_saddle_metal".equals(modelPart) ? modelhorse.horseRightSaddleMetal :
                    "horse_left_face_metal".equals(modelPart) ? modelhorse.horseLeftFaceMetal :
                    "horse_right_face_metal".equals(modelPart) ? modelhorse.horseRightFaceMetal :
                    "horse_left_rein".equals(modelPart) ? modelhorse.horseLeftRein :
                    "horse_right_rein".equals(modelPart) ? modelhorse.horseRightRein : null;
        }
    }

    public String[] getModelRendererNames() {
        return new String[] { "head", "upper_mouth", "lower_mouth", "horse_left_ear", "horse_right_ear",
                "mule_left_ear", "mule_right_ear", "neck", "horse_face_ropes", "mane", "body", "tail_base",
                "tail_middle", "tail_tip", "back_left_leg", "back_left_shin", "back_left_hoof", "back_right_leg",
                "back_right_shin", "back_right_hoof", "front_left_leg", "front_left_shin", "front_left_hoof",
                "front_right_leg", "front_right_shin", "front_right_hoof", "mule_left_chest", "mule_right_chest",
                "horse_saddle_bottom", "horse_saddle_front", "horse_saddle_back", "horse_left_saddle_rope",
                "horse_left_saddle_metal", "horse_right_saddle_rope", "horse_right_saddle_metal",
                "horse_left_face_metal", "horse_right_face_metal", "horse_left_rein", "horse_right_rein" };
    }

    public IEntityRenderer makeEntityRender(ModelBase modelBase, float shadowSize) {
        RenderManager rendermanager = Minecraft.getMinecraft().getRenderManager();
        RenderHorse renderhorse = new RenderHorse(rendermanager, (ModelHorse) modelBase, shadowSize);
        return renderhorse;
    }

}
