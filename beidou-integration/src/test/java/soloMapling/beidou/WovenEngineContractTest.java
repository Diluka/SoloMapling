package soloMapling.beidou;

import org.gms.server.maps.Foothold;
import org.gms.server.maps.FootholdTree;
import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** These tests exercise woven pure geometry without initializing Spring or a database. */
class WovenEngineContractTest {
    @Test
    void footholdMetadataAndEnumerationSurviveTreePartitioning() {
        FootholdTree tree = new FootholdTree(new Point(-500, -500), new Point(500, 500));
        Foothold left = new Foothold(new Point(-400, -100), new Point(-200, 0), 1);
        Foothold right = new Foothold(new Point(200, 0), new Point(400, 100), 2);
        left.setForbidFallDown(true);
        tree.insert(left);
        tree.insert(right);
        assertEquals(2, tree.getAllFootholds().size());
        assertTrue(tree.getAllFootholds().containsAll(List.of(left, right)));
        assertTrue(left.isForbidFallDown());
        assertEquals(0.5, left.slope());
    }

    @Test
    void onlyWallChainsAttachedToGroundAreCollidable() {
        Foothold wall = new Foothold(new Point(0, -100), new Point(0, 0), 1);
        Foothold ground = new Foothold(new Point(0, 0), new Point(100, 0), 2);
        wall.setNext(ground.getId());
        assertTrue(Foothold.isCollidableWall(wall, Map.of(1, wall, 2, ground)));
        assertFalse(Foothold.isCollidableWall(wall, Map.of(1, wall)));
        assertFalse(Foothold.isCollidableWall(ground, Map.of(1, wall, 2, ground)));
    }
}
