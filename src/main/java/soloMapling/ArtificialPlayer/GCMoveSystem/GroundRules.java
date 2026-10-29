package soloMapling.ArtificialPlayer.GCMoveSystem;

import org.gms.client.Character;
import org.gms.server.maps.Foothold;
import org.gms.server.maps.MapleMap;

/** Shared ordinary/ice ground rules used by legacy physics and managed movement. */
public final class GroundRules {
    static final double CLIENT_STEP_MS = 8.0;
    static final double CLIENT_STEP_S = CLIENT_STEP_MS / 1000.0;

    private final BotMovementProfile profile;
    private final double slipScale;
    private final boolean supportedSurface;

    private GroundRules(MapleMap map, BotMovementProfile profile) {
        this.profile = profile;
        float raw = map == null ? 1.0f : map.getFootholdSpeed();
        supportedSurface = Float.isFinite(raw) && raw > 0.0f;
        slipScale = profile.snowShoes() || !Float.isFinite(raw) || raw >= 1.0f || raw <= 0.0f ? 1.0 : raw;
    }

    public static GroundRules fromCharacter(Character actor) {
        return of(actor.getMap(), BotMovementProfile.fromNativeCharacter(actor));
    }

    static GroundRules of(MapleMap map, BotMovementProfile profile) {
        return new GroundRules(map, profile);
    }

    public boolean supportedSurface() { return supportedSurface; }
    public boolean slippery() { return slipScale < 1.0; }
    public double slipScale() { return slipScale; }

    double forcePerClientStep() { return profile.hForcePxs() * CLIENT_STEP_S; }
    double speedPerClientStep() {
        return forcePerClientStep() * BotPhysicsEngine.cfg.GROUNDSLIP
                / (BotPhysicsEngine.cfg.FRICTION + BotPhysicsEngine.cfg.SLOPEFACTOR);
    }

    double step(double hspeed, Foothold foothold, int desiredDir) {
        double force = desiredDir * forcePerClientStep();
        if (force == 0.0 && Math.abs(hspeed) < 0.1) return 0.0;
        var cfg = BotPhysicsEngine.cfg;
        if (slippery()) {
            if (desiredDir != 0) {
                double delta = cfg.SLIP_WALK_ACCEL_PXSS * slipScale * CLIENT_STEP_S * CLIENT_STEP_S;
                return Math.clamp(hspeed + desiredDir * delta, -speedPerClientStep(), speedPerClientStep());
            }
            double delta = cfg.SLIP_GLIDE_DECEL_PXSS * slipScale * CLIENT_STEP_S * CLIENT_STEP_S;
            return hspeed - Math.copySign(Math.min(Math.abs(hspeed), delta), hspeed);
        }
        double inertia = hspeed / cfg.GROUNDSLIP;
        double slope = foothold == null ? 0.0 : Math.clamp(foothold.slope(), -0.5, 0.5);
        double drag = (cfg.FRICTION + cfg.SLOPEFACTOR * (1.0 + slope * -inertia)) * inertia;
        return hspeed + (force - drag) * slipScale;
    }

    int counterBrakeDirection(double hspeed) {
        if (!slippery()) return 0;
        double delta = BotPhysicsEngine.cfg.SLIP_WALK_ACCEL_PXSS * slipScale * CLIENT_STEP_S * CLIENT_STEP_S
                * Math.max(1.0, BotPhysicsEngine.cfg.TICK_MS / CLIENT_STEP_MS);
        return Math.abs(hspeed) > delta ? (hspeed > 0 ? -1 : 1) : 0;
    }

    int approachDirection(double hspeed, int distance, int overshootSlackPx) {
        int toward = Integer.signum(distance);
        if (toward == 0 || !slippery()) return toward;
        var cfg = BotPhysicsEngine.cfg;
        double brake = cfg.SLIP_WALK_ACCEL_PXSS * slipScale * CLIENT_STEP_S * CLIENT_STEP_S;
        double glide = cfg.SLIP_GLIDE_DECEL_PXSS * slipScale * CLIENT_STEP_S * CLIENT_STEP_S;
        int steps = Math.max(1, (int) Math.ceil(cfg.TICK_MS / CLIENT_STEP_MS));
        double release = brake * steps;
        double velocity = hspeed * toward;
        double travel = 0.0;
        for (int i = 0; i < steps; i++) {
            velocity = Math.min(velocity + brake, speedPerClientStep());
            travel += velocity;
        }
        while (velocity > release) {
            velocity -= brake;
            travel += velocity;
        }
        while (velocity > 0.0) {
            velocity -= glide;
            travel += Math.max(velocity, 0.0);
        }
        return travel < Math.abs(distance) + Math.max(0, overshootSlackPx)
                ? toward : counterBrakeDirection(hspeed);
    }
}
