package soloMapling.game.training;

public record TrainingScheduleStartResult(Outcome outcome, TrainingScheduleStatus status) {
    public enum Outcome { STARTED, ALREADY_RUNNING, BUSY, UNAVAILABLE }
}
