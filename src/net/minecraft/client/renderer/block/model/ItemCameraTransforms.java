package net.minecraft.client.renderer.block.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.lang.reflect.Type;

import net.theresa.neogenesis.modules.itemanimations.ItemAnimations;
import net.theresa.neogenesis.modules.itemanimations.Model;
import net.theresa.neogenesis.modules.itemanimations.ThirdPersonViewBlockhitting;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import libsrc.lwjglx.util.vector.Vector3f;

public class ItemCameraTransforms {

    public static final ItemCameraTransforms DEFAULT = new ItemCameraTransforms();
    public static float field_181690_b = 0.0F;
    public static float field_181691_c = 0.0F;
    public static float field_181692_d = 0.0F;
    public static float field_181693_e = 0.0F;
    public static float field_181694_f = 0.0F;
    public static float field_181695_g = 0.0F;
    public static float field_181696_h = 0.0F;
    public static float field_181697_i = 0.0F;
    public static float field_181698_j = 0.0F;
    public final ItemTransformVec3f thirdPerson;
    public final ItemTransformVec3f firstPerson;
    public final ItemTransformVec3f head;
    public final ItemTransformVec3f gui;
    public final ItemTransformVec3f ground;
    public final ItemTransformVec3f fixed;

    private ItemCameraTransforms() {
        this(ItemTransformVec3f.DEFAULT, ItemTransformVec3f.DEFAULT, ItemTransformVec3f.DEFAULT,
                ItemTransformVec3f.DEFAULT, ItemTransformVec3f.DEFAULT, ItemTransformVec3f.DEFAULT);
    }

    public ItemCameraTransforms(ItemCameraTransforms transforms) {
        this.thirdPerson = transforms.thirdPerson;
        this.firstPerson = transforms.firstPerson;
        this.head = transforms.head;
        this.gui = transforms.gui;
        this.ground = transforms.ground;
        this.fixed = transforms.fixed;
    }

    public ItemCameraTransforms(ItemTransformVec3f thirdPersonIn, ItemTransformVec3f firstPersonIn,
                                ItemTransformVec3f headIn, ItemTransformVec3f guiIn, ItemTransformVec3f groundIn,
                                ItemTransformVec3f fixedIn) {
        this.thirdPerson = thirdPersonIn;
        this.firstPerson = firstPersonIn;
        this.head = headIn;
        this.gui = guiIn;
        this.ground = groundIn;
        this.fixed = fixedIn;
    }

    public static ItemTransformVec3f Animation1 =
            new ItemTransformVec3f(new Vector3f(0.0f, -130.0f, 25.0f), new Vector3f(-0.125f, 0.2875f, 0.10625f),
                    new Vector3f(1.5f, 1.5f, 1.5f));

    public static ItemTransformVec3f Animation2 =
            new ItemTransformVec3f(new Vector3f(0.0f, -130.0f, 25.0f), new Vector3f(0.084375f, 0.1375f, 0.0875f),
                    new Vector3f(1.9f, 1.9f, 1.9f));

    public static ItemTransformVec3f Animation3 =
            new ItemTransformVec3f(new Vector3f(0.0f, -130.0f, 25.0f), new Vector3f(0.03125f, 0.1875f, 0.13125f),
                    new Vector3f(1.8f, 2.0f, 1.9f));

    public static ItemTransformVec3f thirdBlockhit =
            new ItemTransformVec3f(new Vector3f(75.0f, 50.0f, -40.0f), new Vector3f(0.1875f, 0.053125f, -0.15625f),
                    new Vector3f(0.85f, 0.85f, 0.85f));

    public void applyTransform(ItemCameraTransforms.TransformType type) {
        ItemTransformVec3f itemtransformvec3f = this.getTransform(type);

        if (LayerHeldItem.isDrawingThird && LayerHeldItem.isDrawingBlockhit &&
                ThirdPersonViewBlockhitting.Instance.getToggled() && ItemAnimations.Instance.getToggled()) {
            GlStateManager.rotate(-20.0f, 1.0F, 0.0F, 0.0F);
            itemtransformvec3f = thirdBlockhit;
        } else if (ItemRenderer.isRenderingArm && !ItemRenderer.isItem3D && ItemRenderer.isItemSword) {
            int model = Model.Instance.getSelectedValue();
            if (model == 1 && ItemAnimations.Instance.getToggled()) {
                itemtransformvec3f = Animation1;
            } else if (model == 2 && ItemAnimations.Instance.getToggled()) {
                itemtransformvec3f = Animation2;
            } else if (model == 3 && ItemAnimations.Instance.getToggled()) {
                itemtransformvec3f = Animation3;
            }
        }

        if (itemtransformvec3f != ItemTransformVec3f.DEFAULT) {
            GlStateManager.translate(itemtransformvec3f.translation.x + field_181690_b,
                    itemtransformvec3f.translation.y + field_181691_c,
                    itemtransformvec3f.translation.z + field_181692_d);
            GlStateManager.rotate(itemtransformvec3f.rotation.y + field_181694_f, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(itemtransformvec3f.rotation.x + field_181693_e, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(itemtransformvec3f.rotation.z + field_181695_g, 0.0F, 0.0F, 1.0F);
            GlStateManager.scale(itemtransformvec3f.scale.x + field_181696_h,
                    itemtransformvec3f.scale.y + field_181697_i,
                    itemtransformvec3f.scale.z + field_181698_j);
        }
    }

    public ItemTransformVec3f getTransform(ItemCameraTransforms.TransformType type) {
        switch (type) {
            case THIRD_PERSON:
                return this.thirdPerson;

            case FIRST_PERSON:
                return this.firstPerson;

            case HEAD:
                return this.head;

            case GUI:
                return this.gui;

            case GROUND:
                return this.ground;

            case FIXED:
                return this.fixed;

            default:
                return ItemTransformVec3f.DEFAULT;
        }
    }

    public boolean func_181687_c(ItemCameraTransforms.TransformType type) {
        return !this.getTransform(type).equals(ItemTransformVec3f.DEFAULT);
    }

    static class Deserializer implements JsonDeserializer<ItemCameraTransforms> {

        public ItemCameraTransforms deserialize(JsonElement p_deserialize_1_, Type p_deserialize_2_,
                                                JsonDeserializationContext p_deserialize_3_) throws JsonParseException {
            JsonObject jsonobject = p_deserialize_1_.getAsJsonObject();
            ItemTransformVec3f itemtransformvec3f = this.func_181683_a(p_deserialize_3_, jsonobject, "thirdperson");
            ItemTransformVec3f itemtransformvec3f1 = this.func_181683_a(p_deserialize_3_, jsonobject, "firstperson");
            ItemTransformVec3f itemtransformvec3f2 = this.func_181683_a(p_deserialize_3_, jsonobject, "head");
            ItemTransformVec3f itemtransformvec3f3 = this.func_181683_a(p_deserialize_3_, jsonobject, "gui");
            ItemTransformVec3f itemtransformvec3f4 = this.func_181683_a(p_deserialize_3_, jsonobject, "ground");
            ItemTransformVec3f itemtransformvec3f5 = this.func_181683_a(p_deserialize_3_, jsonobject, "fixed");
            return new ItemCameraTransforms(itemtransformvec3f, itemtransformvec3f1, itemtransformvec3f2,
                    itemtransformvec3f3, itemtransformvec3f4, itemtransformvec3f5);
        }

        private ItemTransformVec3f func_181683_a(JsonDeserializationContext p_181683_1_, JsonObject p_181683_2_,
                                                 String p_181683_3_) {
            return p_181683_2_.has(p_181683_3_) ? (ItemTransformVec3f) p_181683_1_.deserialize(
                    p_181683_2_.get(p_181683_3_), ItemTransformVec3f.class) : ItemTransformVec3f.DEFAULT;
        }

    }

    public enum TransformType {
        THIRD_PERSON, FIRST_PERSON, HEAD, GUI, GROUND, FIXED
    }

}
