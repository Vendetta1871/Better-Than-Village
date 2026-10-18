package net.betterthanvillage.colony;

public enum JobStatus {
    QUEUED,
    BUILDING,
    COMPLETE,
    BLOCKED;

    public static JobStatus byName(String name) {
        for (JobStatus status : values()) {
            if (status.name().equals(name)) {
                return status;
            }
        }
        return QUEUED;
    }
}
