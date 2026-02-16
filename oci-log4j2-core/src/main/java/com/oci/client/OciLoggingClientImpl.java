package com.oci.client;

import com.oracle.bmc.loggingingestion.LoggingClient;
import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.loggingingestion.responses.PutLogsResponse;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;


public class OciLoggingClientImpl implements OciLoggingClient { 
    private final LoggingClient client; 
    public OciLoggingClientImpl(AbstractAuthenticationDetailsProvider provider) {
        this.client = LoggingClient.builder().build(provider);
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