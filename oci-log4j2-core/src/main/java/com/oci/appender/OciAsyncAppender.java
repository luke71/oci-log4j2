/*
 * Copyright 2026 Luca Scarpa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oci.appender;

import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.*;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;

import com.oci.client.OciLoggingClient;
import com.oci.client.OciLoggingClientImpl;

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ResourcePrincipalAuthenticationDetailsProvider;
import com.oracle.bmc.loggingingestion.LoggingClient;
import com.oracle.bmc.loggingingestion.model.*;
import com.oracle.bmc.loggingingestion.model.LogEntry;
import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.util.VisibleForTesting;

import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/*
<OciAsyncAppender name="OciAppender" logId="ocid1.log.oc1..aaaa" configFile="/Users/luca/.oci/config" profile="DEFAULT" batchSize="50" flushIntervalMs="2000"> <PatternLayout pattern="%d [%t] %-5level %logger - %msg%n"/> </OciAsyncAppender>
*/

@Plugin(name = "OciAsyncAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public class OciAsyncAppender extends AbstractAppender {

    class LogMessage { 
        final String message; 
        final String level; // "INFO", "ERROR", etc. 
        final Instant timestamp; // "INFO", "ERROR", etc. 
        LogMessage(String message, String level, Instant timestamp) { 
            this.message = message; 
            this.level = level; 
            this.timestamp = timestamp; 
        }
        @Override
        public String toString() {
            StringBuffer sb=new StringBuffer();
            sb.append("[");
            sb.append(level);
            sb.append("]");
            sb.append(message);
            return sb.toString();
        }
    }

    private final boolean isTest; 
    private OciLoggingClient testClient=null;

    private final OciLoggingClient client;
    private final String logId;
    private final boolean sysoutTrace;

 //   private final BlockingQueue<String> queue = new LinkedBlockingQueue<>(5000);
    private final BlockingDeque<LogMessage> queue = new LinkedBlockingDeque<>(5000);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final int batchSize;
    private final long flushIntervalMs;
    //OPEN => Circuit Break the circuit closes again after 30 sec
    private enum State { CLOSED, OPEN, HALF_OPEN } 
    //Initial state of circuit breaker
    private State state = State.CLOSED; 
    private int failures = 0; 
    private long openUntil = 0; 

    private boolean canSend() { 
        long now = System.currentTimeMillis(); 
        if (state == State.OPEN && now >= openUntil) { 
            state = State.HALF_OPEN; 
        } 
        return state != State.OPEN; 
    } 
    private void onSuccess() { 
        failures = 0; 
        state = State.CLOSED; 
    } 

    private void onFailure() { 
        failures++; 
        if (failures >= 5) { 
            state = State.OPEN; 
            openUntil = System.currentTimeMillis() + 30000; // break circuit for 30 seconds 
        } 
    }

    //Test Constructor dont' send to oci but prints only in stdout
    @VisibleForTesting
    protected OciAsyncAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                               boolean ignoreExceptions,
                               int batchSize, long flushIntervalMs) {
        super(name, filter, layout, ignoreExceptions,Property.EMPTY_ARRAY);
        sysoutTrace=true;
        sysoutTrace("Test Appender constructor called");
        this.client = null;
        this.logId = null;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        isTest=true;
        scheduler.scheduleAtFixedRate(this::flushBatch,  0L, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    protected OciAsyncAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                               boolean ignoreExceptions, String logId, String configFile, String profile,
                               int batchSize, long flushIntervalMs,boolean sysoutTrace) {
        super(name, filter, layout, ignoreExceptions,Property.EMPTY_ARRAY);
        this.sysoutTrace=sysoutTrace;
        sysoutTrace("Appender constructor called");
        if (sysoutTrace) sysoutTrace("SYSOUT TRACE ENABLED");
        try {
            AbstractAuthenticationDetailsProvider provider;
            if (isValidFile(configFile)) {
                sysoutTrace("Using config file "+configFile+" ignoreExceptions:"+ignoreExceptions);
                ConfigFileReader.ConfigFile config = ConfigFileReader.parse(configFile, profile);
                provider = new ConfigFileAuthenticationDetailsProvider(config);
            } else {
                sysoutTrace("Using resource principal ignoreExceptions:"+ignoreExceptions);
                //No Config file trying Resource principal, this works if you run in VM on oci or OKE
                provider = ResourcePrincipalAuthenticationDetailsProvider.builder().build();
            }
            this.client = new OciLoggingClientImpl(LoggingClient.builder().build(provider));
        } catch (Exception e) {
            throw new RuntimeException("Error initializing OCI LoggingClient", e);
        }

        this.logId = logId;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        isTest=false;
        testClient=null;

        scheduler.scheduleAtFixedRate(this::flushBatch,  0L, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    protected boolean isValidFile(String file) {
        if (file == null) {
            return false;
        }

        Path path = Paths.get(file);

        return Files.exists(path)
                && Files.isRegularFile(path)
                && Files.isReadable(path);
    }

    @PluginFactory
    public static OciAsyncAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute("logId") String logId,
            @PluginAttribute("configFile") String configFile,
            @PluginAttribute("profile") String profile,
            @PluginAttribute(value = "batchSize", defaultInt = 50) int batchSize,
            @PluginAttribute(value = "flushIntervalMs", defaultLong = 2000) long flushIntervalMs,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") Filter filter,
            @PluginAttribute(value = "sysoutTrace", defaultBoolean = false) boolean sysoutTrace,
            @PluginAttribute(value = "ignoreExceptions", defaultBoolean = true) boolean ignoreExceptions) {

        if (configFile == null || configFile.isEmpty()) { 
            configFile = System.getProperty("user.home") + "/.oci/config"; 
        }
        
        if (profile == null || profile.isEmpty()) { 
            profile = "DEFAULT"; 
        }

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new OciAsyncAppender(name, filter, layout, ignoreExceptions, logId, configFile, profile, batchSize, flushIntervalMs,sysoutTrace);
    }

    @Override
    public void append(LogEvent event) {
        try {
            String msg; 
            if (event.getMessage() instanceof SimpleMessage) {
                msg = event.getMessage().toString(); 
            } else {
                msg = new String(getLayout().toByteArray(event)); 
            }
            String level = event.getLevel().name(); 
            LogMessage lm=new LogMessage(msg, level, Instant.ofEpochMilli(event.getTimeMillis()));
           sysoutTrace("Appending "+lm);
            queue.offer(lm); // non blocca mai
        } catch (Exception e) {
            if (!ignoreExceptions()) throw new RuntimeException(e);
        }
    }

    private int flushBatch() {
        sysoutTrace("Starting flush batch");
        int flushed=0;
        List<LogMessage> drained = null;
        try {
            if (!canSend()) {
                return flushed;
            }
            boolean ok=true;
        
            while(canSend()&&!queue.isEmpty()&&ok&&(flushed<batchSize)) {
                drained = drainHomogeneousBatch();

                if (drained.isEmpty()) return flushed;

                List<LogMessage> notSent = sendWithRetry(drained);
                ok=notSent.isEmpty();
                flushed+=drained.size()-notSent.size();

                if (ok) {
                    onSuccess();
                } else {
                    requeueAtHead(notSent); 
                    onFailure();
                }
            }
        } catch (Exception e) {
            if ((drained!=null)&&(!drained.isEmpty())) { 
                requeueAtHead(drained); 
                LogMessage err=new LogMessage("[OCI-APPENDER-ERROR] Exception occurred during oci flush "+e.getClass()+" "+e.getMessage()+" this may cause duplicated messages", "ERROR",Instant.now());
                queue.addFirst(err);
            }
            onFailure();
        }
        return flushed;
    }

    private List<LogMessage> drainHomogeneousBatch() { 
        List<LogMessage> batch = new ArrayList<>(batchSize); 
        LogMessage first = queue.poll(); 
        if (first == null) return batch; 
        batch.add(first); 
        String level = first.level; 
        while (batch.size() < batchSize) { 
            LogMessage next = queue.peek(); 
            if (next == null || !next.level.equals(level)) {
                 break; // severità cambiata → stop 
            } 
            batch.add(queue.poll()); 
        } 
        return batch; 
    }

    private void requeueAtHead(List<LogMessage> messages) { 
        for (int i = messages.size() - 1; i >= 0; i--) { 
            queue.addFirst(messages.get(i)); 
        } 
    }

    private List<LogMessage> sendWithRetry(List<LogMessage> messages) {
        sysoutTrace("Starting sendWithRetry for "+messages.size()+" messages");
        int attempt = 0;
        long backoff = 1000;

        while (attempt < 5 && !messages.isEmpty()) {
            try {
                List<LogEntry> entries = new ArrayList<>(); 
                for (LogMessage msg : messages) {
                     entries.add(LogEntry.builder()
                                 .id(UUID.randomUUID().toString())
                                 .time(new Date(msg.timestamp.toEpochMilli()))
                                 .data(msg.message)
                                 .build()
                                ); 
                }

                String level = messages.get(0).level;
                
                if (getClient()!=null) {
                    sendToOci(entries,level);
                } else {
                    sendToStdout(entries,level);
                }
                messages.clear();

            } catch (Exception e) {
                sysoutTrace("Exception in sendWithRetry "+e.getClass()+" "+e.getMessage());
                attempt++;
                try { 
                    Thread.sleep(backoff); 
                } catch (InterruptedException ignored) {}
                backoff = Math.min(backoff * 2, 30000);
            }
        }
        return messages;
    }

    private void sendToOci(List<LogEntry> entries, String level) {
                LogEntryBatch batch = LogEntryBatch.builder()
                        .entries(entries)
                        .source("log4j2-oci-async")
                        .type(level)
                        .subject("application")
                        .build();

                PutLogsDetails details = PutLogsDetails.builder()
                        .specversion("1.0")
                        .logEntryBatches(Collections.singletonList(batch))
                        .build();

                PutLogsRequest request = PutLogsRequest.builder()
                        .logId(logId)
                        .putLogsDetails(details)
                        .build();

                getClient().putLogs(request);
                sysoutTrace("Sent "+level+" logs("+entries.size()+") to oci");
    }

    private void sendToStdout(List<LogEntry> entries, String level) {
        if (entries!=null&&!entries.isEmpty()) {
            entries.forEach((le)->{System.out.println("stdout flush:"+le.getData());});
        }
    }

    @Override public void start() { 
        sysoutTrace("Appender start() called"); 
        super.start(); 
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        sysoutTrace("Appender stop() called");
        sysoutTrace("shutting down scheduler");
        scheduler.shutdown();
        try {
            try { 
                scheduler.awaitTermination(5, TimeUnit.SECONDS); 
            } catch (InterruptedException e) { 
            }
            sysoutTrace("scheduler stopped");
            int failures=0;
            int maxFailures=20;
            while (!queue.isEmpty()&&failures<maxFailures) {
                try {
                    while (flushBatch()>0) {
                    };
                    Thread.sleep(3000);
                } catch (Exception e) {
                    failures++;
                }
            }
            if (failures>=maxFailures) {
                System.err.println("[OCI-APPENDER-ERROR]  too many failures: cannot flush "+queue.size()+"messages");
            }
        } finally {
            getClient().close();
        }
        return super.stop(timeout,timeUnit);
    }

    private final OciLoggingClient getClient() {
        if (client!=null) return client;
        if (isTest) return testClient;
        return null;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    @VisibleForTesting 
    public int getQueueSize() { 
        return queue.size(); 
    }
    @VisibleForTesting 
    public void setClientForTest(OciLoggingClient client) { 
        this.testClient = client; 
    }

    public void sysoutTrace(String message) {
        if (sysoutTrace) System.out.println(message);
    }
}
