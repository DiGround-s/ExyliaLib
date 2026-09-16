package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A place on a body something can be attached to.
 *
 * <p>Not the same thing as a {@link RagdollPart}. A part is a slab of the model
 * — the whole right arm — and its position is its middle. A joint is the spot on
 * that part where you would tie something: the hand at the end of the arm, the
 * crown of the head, the sole of a foot. Hanging a hat off the head's middle
 * puts it through the face.
 *
 * <p>Every joint moves with the choreography, because it is solved from the
 * part's own flight. Whatever is attached to it is solved the same way, once,
 * before the first frame is sent — so a hat in a hand stays in that hand through
 * a cartwheel without a single extra packet.
 *
 * @since 1.173.0
 */
public enum RagdollJoint {

    /** The top of the skull, where a hat sits. */
    HEAD(RagdollPart.HEAD, 4f),

    /** The middle of the head, where a mask or a pair of glasses sits. */
    FACE(RagdollPart.HEAD, 0f),

    /** The middle of the chest, where a badge or a necklace sits. */
    CHEST(RagdollPart.TORSO, 2f),

    /** The waist, where a belt sits. */
    HIPS(RagdollPart.TORSO, -6f),

    /** The right hand, at the end of the right arm. */
    HAND_RIGHT(RagdollPart.ARM_RIGHT, -7f),

    /** The left hand. */
    HAND_LEFT(RagdollPart.ARM_LEFT, -7f),

    /** The right foot. */
    FOOT_RIGHT(RagdollPart.LEG_RIGHT, -6f),

    /** The left foot. */
    FOOT_LEFT(RagdollPart.LEG_LEFT, -6f);

    private final RagdollPart part;
    private final float pixelsUp;

    RagdollJoint(RagdollPart part, float pixelsUp) {
        this.part = part;
        this.pixelsUp = pixelsUp;
    }

    /** The part this joint belongs to, whose flight carries it. */
    public @NotNull RagdollPart part() {
        return part;
    }

    /**
     * How far along the part the joint sits, in blocks, before scaling.
     *
     * <p>Measured from the part's own middle, in the part's own axes, so it is
     * turned by whatever the part has turned to.
     */
    public float up() {
        return pixelsUp * RagdollPart.PIXEL;
    }

    /** The joint of that name, or {@code null} when there is none. */
    public static RagdollJoint of(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "head", "hat", "crown" -> HEAD;
            case "face", "eyes" -> FACE;
            case "chest", "torso", "body" -> CHEST;
            case "hips", "waist", "belt" -> HIPS;
            case "hand_r", "hand_right", "right_hand", "main_hand", "hand" -> HAND_RIGHT;
            case "hand_l", "hand_left", "left_hand", "off_hand" -> HAND_LEFT;
            case "foot_r", "foot_right", "right_foot" -> FOOT_RIGHT;
            case "foot_l", "foot_left", "left_foot" -> FOOT_LEFT;
            default -> null;
        };
    }
}
