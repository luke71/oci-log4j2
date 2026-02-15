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

import com.oracle.bmc.ConfigFileReader;
import com.oracle.bmc.auth.AuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.loggingingestion.LoggingClient;
import com.oracle.bmc.loggingingestion.model.*;
import com.oracle.bmc.loggingingestion.requests.PutLogsRequest;
import com.oracle.bmc.util.VisibleForTesting;

import java.io.Serializable;
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
        LogMessage(String message, String level) { 
            this.message = message; 
            this.level = level; 
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
    protected OciAsyncAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                               boolean ignoreExceptions,
                               int batchSize, long flushIntervalMs) {
        super(name, filter, layout, ignoreExceptions,Property.EMPTY_ARRAY);
        this.client = null;
        this.logId = null;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        isTest=true;

        scheduler.scheduleAtFixedRate(this::flushBatch,  0L, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    protected OciAsyncAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                               boolean ignoreExceptions, String logId, String configFile, String profile,
                               int batchSize, long flushIntervalMs) {
        super(name, filter, layout, ignoreExceptions,Property.EMPTY_ARRAY);
        try {
            ConfigFileReader.ConfigFile config = ConfigFileReader.parse(configFile, profile);
            AuthenticationDetailsProvider provider = new ConfigFileAuthenticationDetailsProvider(config);
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

    @PluginFactory
    public static OciAsyncAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute("logId") String logId,
            @PluginAttribute("configFile") String configFile,
            @PluginAttribute("profile") String profile,
            @PluginAttribute(value = "batchSize", defaultInt = 50) int batchSize,
            @PluginAttribute(value = "flushIntervalMs", defaultLong = 2000) long flushIntervalMs,
            @PluginElement("Layout") Layout<? extends Serializable> layout,
            @PluginElement("Filter") Filter filter) {

        if (configFile == null || configFile.isEmpty()) { 
            configFile = System.getProperty("user.home") + "/.oci/config"; 
        }
        
        if (profile == null || profile.isEmpty()) { 
            profile = "DEFAULT"; 
        }

        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }

        return new OciAsyncAppender(name, filter, layout, true, logId, configFile, profile, batchSize, flushIntervalMs);
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
            LogMessage lm=new LogMessage(msg, level);
            System.out.println("Appending "+lm);
            queue.offer(lm); // non blocca mai
        } catch (Exception e) {
            if (!ignoreExceptions()) throw new RuntimeException(e);
        }
    }

    private int flushBatch() {
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
                LogMessage err=new LogMessage("[OCI-APPENDER-ERROR] Exception occurred during oci flush "+e.getClass()+" "+e.getMessage()+" this may cause duplicated messages", "ERROR");
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
        int attempt = 0;
        long backoff = 1000;

        while (attempt < 5) {
            try {
                List<LogEntry> entries = new ArrayList<>(); 
                for (LogMessage msg : messages) {
                     entries.add(LogEntry.builder().data(msg.message).build()); 
                }

                String level = messages.get(0).level;
                
                if (getClient()!=null) {
                    sendToOci(entries,level);
                } else {
                    sendToStdout(entries,level);
                }
                messages.clear();

            } catch (Exception e) {
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
    }

    private void sendToStdout(List<LogEntry> entries, String level) {
        if (entries!=null&&!entries.isEmpty()) {
            entries.forEach((le)->{System.out.println("stdout flush:"+le.getData());});
        }
    }

    @Override
    public void stop() {
        super.stop();
        scheduler.shutdown();
        try { 
            scheduler.awaitTermination(5, TimeUnit.SECONDS); 
        } catch (InterruptedException e) { 
        }
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
}
