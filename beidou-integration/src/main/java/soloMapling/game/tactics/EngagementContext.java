package soloMapling.game.tactics;

import soloMapling.game.execution.ExecutionScope;

public record EngagementContext(EngagementCandidate candidate, ExecutionScope.WorkHandle work) { }
