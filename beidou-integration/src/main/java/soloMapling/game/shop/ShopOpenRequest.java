package soloMapling.game.shop;

/** A configured NPC template resolved in the role's actual current map. */
public record ShopOpenRequest(int npcId) {
    public ShopOpenRequest {
        if (npcId <= 0) throw new IllegalArgumentException("invalid_shop_npc_id");
    }
}
