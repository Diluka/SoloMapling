package soloMapling.beidou;

import org.gms.client.Job;

/** Pure adapters for types (such as Java enums) that AspectJ cannot extend. */
public final class HostCompatibility {
    private HostCompatibility() { }

    /** ChatLogger's two validated general-chat branches, including hidden GMs. */
    public static boolean isGeneralChatKind(String kind) {
        return "General".equals(kind) || "GM General".equals(kind);
    }

    public static int jobTier(Job job) {
        if (job == null || job.getId() == 0) return 0;
        return switch (job.getId() % 100) {
            case 0 -> 1;
            case 10, 20, 30 -> 2;
            case 11, 21, 31 -> 3;
            case 12, 22, 32 -> 4;
            default -> 0;
        };
    }
}
