package soloMapling.game.training;

public record TrainingScheduleStopResult(Outcome outcome, TrainingScheduleStatus status) {
    public enum Outcome { STOPPED, ALREADY_IDLE }
}
