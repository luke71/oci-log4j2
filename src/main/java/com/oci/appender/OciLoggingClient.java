package com.oci.appender;

import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.loggingingestion.responses.PutLogsResponse;

public interface OciLoggingClient { 
    PutLogsResponse putLogs(PutLogsRequest request); 
}