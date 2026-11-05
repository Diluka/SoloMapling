package soloMapling.game.tactics;

import java.awt.Point;

/** The hunting owner's existing geometric keep rule, captured from its real spot/stack assignment. */
@FunctionalInterface
public interface EngagementArea {
    boolean contains(Point position);
}
