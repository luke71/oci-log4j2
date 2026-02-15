package com.oci.client;

import com.oracle.bmc.loggingingestion.LoggingClient;
import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.loggingingestion.responses.PutLogsResponse;

public class OciLoggingClientImpl implements OciLoggingClient { 
    private final LoggingClient client; 
    public OciLoggingClientImpl(LoggingClient client) { 
        this.client = client; 
    } 
    @Override 
    public PutLogsResponse putLogs(PutLogsRequest request) { 
        return client.putLogs(request); 
    }

    @Override
    public void close() {
       client.close();
    }
}