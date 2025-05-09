// In src/main/java/com/example/models/MonitorAlarm.java
package com.example.models;

import java.sql.Timestamp;

public class MonitorAlarm {
    public String outageId;
    public String monitorId;
    public String name;
    public String monitorType;
    public int status;
    public String downReason;
    public String duration;
    public long downtimeMillis;
    public Timestamp lastPolledTime;
    public String technician;
    public boolean isMonitorMuted;
    public String userId;
}
