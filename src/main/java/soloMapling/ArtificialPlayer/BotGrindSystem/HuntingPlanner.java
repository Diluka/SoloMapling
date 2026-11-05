package soloMapling.ArtificialPlayer.BotGrindSystem;

import org.gms.client.Character;
import org.gms.server.life.Monster;
import org.gms.server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotSpotClaims;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.game.combat.CombatActionModule;
import soloMapling.game.tactics.EngagementArea;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** One V2 hunting activity's placement and target decisions; native execution belongs to action modules. */
public final class HuntingPlanner {
    public enum Kind { MOVE, FIGHT, WAIT }

    public record Decision(Kind kind, Point destination, int targetOid, Set<Integer> allowedTargetOids,
                           String reason) {
        public Decision {
            destination = destination == null ? null : new Point(destination);
            allowedTargetOids = Set.copyOf(allowedTargetOids);
        }
    }

    private enum Phase { SELECT, TRAVEL, FIGHT, WAIT, RELEASED }

    private static final int ACQUIRE_MARGIN = 130;
    private static final int CLUSTER_RADIUS = 160;
    private static final long FALLBACK_MS = 2500;
    private static final long EXCLUDE_MS = 30000;

    private final Character actor;
    private final MapleMap map;
    private final int mapId;
    private final int characterId;
    private final MapGrindProfile profile;
    private final MovementStyle movementStyle;
    private final GrindStyle style;
    private final Map<Integer, Long> deferredTargets = new LinkedHashMap<>();
    private Phase phase = Phase.SELECT;
    private Spot spot;
    private SpotStack stack;
    private List<Spot> members = List.of();
    private Spot assigned;
    private int claimIndex = -1;
    private int claimSlot = -1;
    private int excludedIndex = -1;
    private long excludedUntil;
    private List<Integer> ring = List.of();
    private int ringPosition = -1;
    private Monster target;
    private int lastObservedTargetOid = -1;
    private int lastObservedHp;
    private long lastKillAt;
    private long waitStartedAt = -1;
    private long personalEmptySince = -1;
    private boolean personalFallback;
    private long starvedSince = -1;
    private long offAnchorSince = -1;
    private boolean saturated;
    private Point progressAnchor;
    private long retargetDeadline;
    private String lastReason = "not_selected";

    /** Caller supplies a current, bound role and performs all subsequent observation on its original lane. */
    public HuntingPlanner(Character actor, GrindStyle forcedStyle, long now) {
        this.actor = actor;
        map = actor.getMap();
        mapId = map.getId();
        characterId = actor.getId();
        profile = SpotFinder.profile(map);
        movementStyle = MovementStylePolicy.forBot(actor);
        style = forcedStyle == null ? GrindStylePolicy.natural(profile, movementStyle) : forcedStyle;
        lastKillAt = now;
        resetProgress(now);
    }

    public GrindStyle style() { return style; }

    /** Capture the real assignment; target keep and tactical positions consume the same existing geometry. */
    public EngagementArea activityArea() {
        var selectedSpot = spot;
        var selectedStack = stack;
        return position -> acceptsStickyPosition(position, selectedSpot, selectedStack);
    }

    public Decision decide(long now) {
        if (phase == Phase.RELEASED) return decision(Kind.WAIT, null, null, "released");
        deferredTargets.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (style != GrindStyle.ROAM && phase == Phase.SELECT) {
            if (!selectAndClaim(now)) return decision(Kind.WAIT, null, null, "no_reachable_spot");
            lastKillAt = now;
            phase = Phase.TRAVEL;
            resetProgress(now);
        }
        if (phase == Phase.TRAVEL) {
            var destination = stack == null ? spot : assigned != null ? assigned : members.get(members.size() - 1);
            var position = actor.getPosition();
            if (Math.abs(position.x - destination.anchor().x) > destination.radius()
                    || Math.abs(position.y - destination.anchor().y) > 120) {
                return decision(Kind.MOVE, destination.anchor(), null, "travel_to_spot");
            }
            phase = Phase.FIGHT;
            resetProgress(now);
        }

        Monster selected = target != null && !deferredTargets.containsKey(target.getObjectId())
                && acceptsSticky(target) ? target : acquireTarget(now);
        if (selected != null) {
            if (selected != target) {
                resetProgress(now);
                lastObservedTargetOid = selected.getObjectId();
                lastObservedHp = selected.getHp();
            }
            target = selected;
            phase = Phase.FIGHT;
            waitStartedAt = -1;
            starvedSince = -1;
            if (style == GrindStyle.ROAM) saturated = false;
            if (spot != null && offAnchorLedge() && !inAttackRange(selected)) {
                return decision(Kind.MOVE, spot.anchor(), null, "return_to_anchor");
            }
            return decision(Kind.FIGHT, null, selected, "target_selected");
        }
        target = null;
        if (style == GrindStyle.ROAM) {
            if (starvedSince < 0) starvedSince = now;
            saturated = now - starvedSince >= 12000;
            phase = Phase.WAIT;
            return decision(Kind.WAIT, null, null, saturated ? "roam_starved" : "no_target");
        }
        if (waitStartedAt < 0) waitStartedAt = now;
        phase = Phase.WAIT;
        if (spot != null && offAnchorLedge()) {
            if (offAnchorSince < 0) offAnchorSince = now;
            if (now - offAnchorSince >= 4000) {
                resetProgress(now);
                return decision(Kind.MOVE, spot.anchor(), null, "return_to_anchor");
            }
        } else {
            offAnchorSince = -1;
        }
        long patience = stack != null ? 5000 : switch (profile.regime()) {
            case COMPACT -> 8000;
            case SPREAD -> 4000;
            case SPARSE -> 2500;
        };
        long unproductive = stack != null ? 20000 : switch (profile.regime()) {
            case COMPACT -> 35000;
            case SPREAD -> 18000;
            case SPARSE -> 10000;
        };
        if (now - waitStartedAt >= patience && now - lastKillAt >= unproductive) {
            relocate(now);
            return decision(Kind.WAIT, null, null, "relocating");
        }
        var position = actor.getPosition();
        if (stack != null && assigned != null
                && (Math.abs(position.x - assigned.anchor().x) > 100
                    || Math.abs(position.y - assigned.anchor().y) > 90)) {
            resetProgress(now);
            return decision(Kind.MOVE, assigned.anchor(), null, "wait_recenter");
        }
        var band = personalBand();
        if (stack == null && band != null && Math.abs(position.x - (band[0] + band[1]) / 2) > 100) {
            int x = (band[0] + band[1]) / 2;
            var ground = GCMovement.groundPointInRegion(map, spot.regionId(), x);
            resetProgress(now);
            return decision(Kind.MOVE, ground != null ? ground : new Point(x, spot.anchor().y), null, "wait_recenter");
        }
        return decision(Kind.WAIT, null, null, "respawn_lull");
    }

    public String cancellationReason(Decision current, long now) {
        if (phase == Phase.RELEASED) return "released";
        if (current.kind() == Kind.WAIT) return null;
        if (current.kind() == Kind.FIGHT) {
            var object = map.getMapObject(current.targetOid());
            // Let the action report its own confirmed death rather than cancelling its final observation.
            if (target != null && target.getObjectId() == current.targetOid() && !target.isAlive()) return null;
            if (!(object instanceof Monster monster) || !SpotFinder.isHostile(monster)) return "target_invalid";
            if (!acceptsSticky(monster)) return "target_outside_bounds";
            int hp = monster.getHp();
            if (lastObservedTargetOid == current.targetOid() && hp < lastObservedHp) {
                retargetDeadline = now + 4000;
            }
            lastObservedTargetOid = current.targetOid();
            lastObservedHp = hp;
            if (inAttackRange(monster)) return null;
        }
        var position = actor.getPosition();
        if (Math.abs(position.x - progressAnchor.x) + Math.abs(position.y - progressAnchor.y) > 20) {
            progressAnchor = new Point(position);
            retargetDeadline = now + 4000;
        }
        return now > retargetDeadline ? "no_progress" : null;
    }

    public void completed(Decision previous, boolean targetKilled, boolean actionSucceeded, long now) {
        if (phase == Phase.RELEASED) return;
        if (targetKilled) lastKillAt = now;
        if (previous.kind() == Kind.MOVE) {
            if (!actionSucceeded) relocate(now);
            else if (phase == Phase.TRAVEL) phase = Phase.FIGHT;
        } else if (previous.kind() == Kind.FIGHT) {
            target = null;
        }
        resetProgress(now);
    }

    /** A confirmed unreachable target gets one old retarget window before it is considered again. */
    public void deferTarget(int oid, long now) {
        if (phase != Phase.RELEASED) deferredTargets.put(oid, now + 4000);
    }

    public void release() {
        releaseClaim();
        spot = null;
        stack = null;
        members = List.of();
        assigned = null;
        target = null;
        deferredTargets.clear();
        lastObservedTargetOid = -1;
        ring = List.of();
        phase = Phase.RELEASED;
    }

    public Map<String, Object> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        result.put("style", style.name());
        result.put("phase", phase.name());
        result.put("map_id", mapId);
        result.put("character_id", characterId);
        result.put("target_oid", target == null ? -1 : target.getObjectId());
        result.put("deferred_target_oids", List.copyOf(deferredTargets.keySet()));
        result.put("spot_index", claimIndex);
        result.put("claim_slot", claimSlot);
        result.put("saturated", saturated);
        result.put("personal_fallback", personalFallback);
        result.put("assigned_floor_y", assigned == null ? null : assigned.anchor().y);
        result.put("patrol_ring", ring);
        result.put("patrol_position", ringPosition);
        result.put("last_reason", lastReason);
        return result;
    }

    private boolean selectAndClaim(long now) {
        releaseClaim();
        if (style == GrindStyle.STACK) {
            SpotStack chosen = null;
            int index = -1;
            int slot = -1;
            for (int attempt = 0; attempt < 3; attempt++) {
                chosen = pickStack(now);
                if (chosen == null) { saturated = false; return false; }
                index = primaryIndex(chosen);
                slot = BotSpotClaims.claim(mapId, index, Math.min(chosen.spotIndices().size(), SpotFinder.SHARE_CAP_MAX), characterId);
                if (slot >= 0) break;
            }
            saturated = slot < 0;
            if (slot < 0) slot = BotSpotClaims.claim(mapId, index, Integer.MAX_VALUE, characterId);
            stack = chosen;
            members = chosen.spotIndices().stream().map(profile.spots()::get).toList();
            assigned = slot < members.size() ? members.get(members.size() - 1 - slot) : null;
            claimIndex = index;
            claimSlot = slot;
        } else {
            Spot chosen = null;
            int index = -1;
            int slot = -1;
            for (int attempt = 0; attempt < 3; attempt++) {
                chosen = pickCamp(now);
                if (chosen == null) { saturated = false; return false; }
                var candidate = chosen;
                index = IntStream.range(0, profile.spots().size()).filter(i -> profile.spots().get(i) == candidate)
                        .findFirst().orElseThrow();
                slot = BotSpotClaims.claim(mapId, index, chosen.shareCap(), characterId);
                if (slot >= 0) break;
            }
            saturated = slot < 0;
            if (slot < 0) slot = BotSpotClaims.claim(mapId, index, Integer.MAX_VALUE, characterId);
            spot = chosen;
            claimIndex = index;
            claimSlot = slot;
        }
        personalEmptySince = -1;
        personalFallback = false;
        waitStartedAt = -1;
        return true;
    }

    private Spot pickCamp(long now) {
        if (style == GrindStyle.PATROL) {
            if (ring.isEmpty()) {
                var position = actor.getPosition();
                var reachable = GCMovement.reachableRegions(map, position.x, position.y);
                var candidates = new ArrayList<Integer>();
                for (int i = 0; i < profile.spots().size(); i++) {
                    var candidate = profile.spots().get(i);
                    if (reachable.isEmpty() || candidate.regionId() < 0 || reachable.contains(candidate.regionId())) candidates.add(i);
                }
                candidates.sort(Comparator.comparingInt((Integer i) -> profile.spots().get(i).sameLedgeSpawnCount()).reversed());
                var selected = new ArrayList<>(candidates.subList(0, Math.min(4, candidates.size())));
                selected.sort(Comparator.comparingInt(i -> profile.spots().get(i).anchor().x));
                ring = List.copyOf(selected);
                ringPosition = -1;
            }
            if (ring.size() >= 2) {
                int fallback = -1;
                int holdersAtFallback = Integer.MAX_VALUE;
                for (int i = 1; i <= ring.size(); i++) {
                    int position = (Math.max(ringPosition, 0) + i) % ring.size();
                    int index = ring.get(position);
                    int holders = BotSpotClaims.holders(mapId, index);
                    if (holders < profile.spots().get(index).shareCap()) {
                        ringPosition = position;
                        return profile.spots().get(index);
                    }
                    if (holders < holdersAtFallback) {
                        fallback = position;
                        holdersAtFallback = holders;
                    }
                }
                ringPosition = fallback;
                return profile.spots().get(ring.get(fallback));
            }
        }
        // The shared picker uses wall time internally; this activity's supplied clock owns exclusion expiry.
        return SpotFinder.pickBest(actor, profile, excludedIndex, now < excludedUntil ? Long.MAX_VALUE : 0);
    }

    private SpotStack pickStack(long now) {
        SpotStack best = null;
        double bestScore = -Double.MAX_VALUE;
        var position = actor.getPosition();
        for (var candidate : profile.stacks()) {
            if (!GrindStylePolicy.canTraverse(movementStyle, candidate)) continue;
            int primary = primaryIndex(candidate);
            if (primary == excludedIndex && now < excludedUntil) continue;
            double distance = position.distance(new Point((candidate.x0() + candidate.x1()) / 2,
                    (candidate.topY() + candidate.bottomY()) / 2));
            double score = 10.0 * candidate.totalFeed() - 30.0 * BotSpotClaims.holders(mapId, primary)
                    - .015 * distance + ThreadLocalRandom.current().nextDouble() * 12.0;
            if (score > bestScore) { best = candidate; bestScore = score; }
        }
        return best;
    }

    private int primaryIndex(SpotStack selected) {
        int index = selected.spotIndices().get(0);
        int feed = -1;
        for (int candidate : selected.spotIndices()) {
            int candidateFeed = profile.spots().get(candidate).sameLedgeSpawnCount();
            if (candidateFeed > feed) { feed = candidateFeed; index = candidate; }
        }
        return index;
    }

    private Monster acquireTarget(long now) {
        var position = actor.getPosition();
        var candidates = map.getAllMonsters().stream()
                .filter(monster -> !deferredTargets.containsKey(monster.getObjectId())).toList();
        if (style == GrindStyle.ROAM) {
            return SpotFinder.nearestHostileCrossLedge(map, position, 900, 140, profile, 400, candidates);
        }
        Monster selected;
        if (stack != null) {
            selected = SpotFinder.bestStackHostile(map, members, assigned, stack.x0() - ACQUIRE_MARGIN,
                    stack.x1() + ACQUIRE_MARGIN, stack.topY() - 60, stack.bottomY() + 60, position, CLUSTER_RADIUS, candidates);
            if (assigned == null) return selected;
        } else {
            var band = personalBand();
            selected = SpotFinder.bestClusterHostileInBand(map, spot.anchor(), spot.radius(),
                    band == null ? spot.anchor().x - spot.radius() - ACQUIRE_MARGIN : band[0],
                    band == null ? spot.anchor().x + spot.radius() + ACQUIRE_MARGIN : band[1], position, CLUSTER_RADIUS, candidates);
            if (band == null) return selected;
        }
        if (selected != null) {
            personalEmptySince = -1;
            personalFallback = false;
            return selected;
        }
        if (personalEmptySince < 0) personalEmptySince = now;
        if (!personalFallback && now - personalEmptySince < FALLBACK_MS) return null;
        personalFallback = true;
        return stack != null
                ? SpotFinder.bestStackHostile(map, members, null, stack.x0() - ACQUIRE_MARGIN,
                        stack.x1() + ACQUIRE_MARGIN, stack.topY() - 60, stack.bottomY() + 60, position, CLUSTER_RADIUS, candidates)
                : SpotFinder.bestClusterHostileInBand(map, spot.anchor(), spot.radius(),
                        spot.anchor().x - spot.radius() - ACQUIRE_MARGIN,
                        spot.anchor().x + spot.radius() + ACQUIRE_MARGIN, position, CLUSTER_RADIUS, candidates);
    }

    private boolean acceptsSticky(Monster monster) {
        if (!SpotFinder.isHostile(monster)) return false;
        var enemy = monster.getPosition();
        return enemy != null && acceptsStickyPosition(enemy, spot, stack);
    }

    private boolean acceptsStickyPosition(Point enemy, Spot selectedSpot, SpotStack selectedStack) {
        if (style == GrindStyle.ROAM) {
            var position = actor.getPosition();
            int dy = Math.abs(enemy.y - position.y);
            return Math.abs(enemy.x - position.x) <= 900
                    && (dy <= 140 || dy <= 400 && SpotFinder.sameStack(profile, map, position, enemy));
        }
        if (selectedStack != null) {
            return enemy.x >= selectedStack.x0() - ACQUIRE_MARGIN && enemy.x <= selectedStack.x1() + ACQUIRE_MARGIN
                    && enemy.y >= selectedStack.topY() - 60 && enemy.y <= selectedStack.bottomY() + 60;
        }
        return Math.abs(enemy.x - selectedSpot.anchor().x) <= selectedSpot.radius() + ACQUIRE_MARGIN
                && !GCMovement.onDifferentLedge(map, selectedSpot.anchor().x, selectedSpot.anchor().y, enemy.x, enemy.y);
    }

    private boolean acceptsAdditional(Monster monster) {
        if (!acceptsSticky(monster)) return false;
        if (style == GrindStyle.ROAM) return true;
        var enemy = monster.getPosition();
        if (stack != null) {
            return personalFallback || assigned == null
                    ? members.stream().anyMatch(member -> !GCMovement.onDifferentLedge(map,
                            member.anchor().x, member.anchor().y, enemy.x, enemy.y))
                    : !GCMovement.onDifferentLedge(map, assigned.anchor().x, assigned.anchor().y, enemy.x, enemy.y);
        }
        var band = personalFallback ? null : personalBand();
        return Math.abs(enemy.y - spot.anchor().y) <= spot.radius()
                && (band == null || enemy.x >= band[0] && enemy.x <= band[1]);
    }

    private int[] personalBand() {
        if (spot == null || claimSlot < 0 || claimSlot >= spot.shareCap() || spot.shareCap() <= 1
                || BotSpotClaims.holders(mapId, claimIndex) <= 1) return null;
        return BotSpotClaims.section(spot.anchor().x - spot.radius(), spot.anchor().x + spot.radius(), claimSlot, spot.shareCap());
    }

    private boolean inAttackRange(Monster monster) {
        return CombatActionModule.inAttackRange(actor, monster);
    }

    private boolean offAnchorLedge() {
        var position = actor.getPosition();
        return GCMovement.onDifferentLedge(map, spot.anchor().x, spot.anchor().y, position.x, position.y);
    }

    private Decision decision(Kind kind, Point destination, Monster enemy, String reason) {
        lastReason = reason;
        Set<Integer> allowed = enemy == null ? Set.of() : Stream.concat(Stream.of(enemy),
                        map.getAllMonsters().stream().filter(monster -> monster != enemy
                                && !deferredTargets.containsKey(monster.getObjectId()) && acceptsAdditional(monster)))
                .map(Monster::getObjectId).distinct().limit(32)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Decision(kind, destination, enemy == null ? -1 : enemy.getObjectId(), allowed, reason);
    }

    private void relocate(long now) {
        excludedIndex = claimIndex;
        excludedUntil = now + EXCLUDE_MS;
        releaseClaim();
        spot = null;
        stack = null;
        members = List.of();
        assigned = null;
        target = null;
        personalEmptySince = -1;
        personalFallback = false;
        waitStartedAt = -1;
        offAnchorSince = -1;
        phase = Phase.SELECT;
    }

    private void releaseClaim() {
        if (claimIndex >= 0) BotSpotClaims.release(mapId, claimIndex, characterId);
        claimIndex = -1;
        claimSlot = -1;
    }

    private void resetProgress(long now) {
        progressAnchor = new Point(actor.getPosition());
        retargetDeadline = now + 4000;
    }
}
