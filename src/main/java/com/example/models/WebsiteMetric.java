// In src/main/java/com/example/models/WebsiteMetric.java
package com.example.models;

import java.sql.Timestamp;

public class WebsiteMetric {
    public String monitorName;
    public int monitorStatus;
    public String monitorType;
    public String attributeValue;
    public String unit;
    public Timestamp lastPolledTime;
    public long downtimeMillis;
    public String duration;
    public String downReason;
}

